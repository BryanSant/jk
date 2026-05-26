// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import dev.jkbuild.util.JkThreads;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * One {@code jk} invocation's orchestration: a named DAG of
 * {@link Phase}s with collected progress, warnings, errors, and a
 * terminal {@link GoalResult}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><b>Initialize.</b> Topologically order phases, compute initial
 *       scope (sum of {@link Phase#estimateScope} across all phases,
 *       gathered in parallel on {@link JkThreads#io()}).</li>
 *   <li><b>Run.</b> Walk the DAG by readiness levels. Within each
 *       level, dispatch IO phases to {@link JkThreads#io()}, CPU
 *       phases to {@link JkThreads#cpu()}, and run SYNC phases on the
 *       caller's thread sequentially. Wait for the level to complete
 *       before opening the next.</li>
 *   <li><b>Report.</b> Build a {@link GoalResult} listing every
 *       phase's status + duration plus the accumulated diagnostics.
 *       Emit {@link GoalListener#goalFinish} and return.</li>
 * </ol>
 *
 * <p>Cancellation is cooperative — failures or external requests set
 * a flag the running phases poll. A 200ms grace period is enforced via
 * thread interrupt after the flag flips.
 */
public final class Goal {

    /** How long async phases get to notice cancellation before we interrupt them. */
    static final Duration COOPERATIVE_CANCEL_GRACE = Duration.ofMillis(200);

    private final String name;
    private final boolean interactive;
    private final List<Phase> phases;
    private final List<GoalListener> listeners;

    private final LongAdder numerator = new LongAdder();
    private final LongAdder denominator = new LongAdder();
    private final AtomicInteger phasesComplete = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * True only when {@link #requestCancel} was invoked by the host
     * (typically the SIGINT bridge). The {@link #cancelled} flag is
     * also flipped internally when a phase fails so still-pending
     * phases get torn down — so {@code cancelled} alone can't tell us
     * "the user asked to abort" vs "a phase failed and we're winding
     * the others down." Listeners need that distinction to pick the
     * right end-of-goal rendering.
     */
    private final AtomicBoolean userRequestedCancel = new AtomicBoolean(false);
    private final Map<String, PhaseStatus> statuses = new ConcurrentHashMap<>();
    private final List<GoalResult.Diagnostic> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<GoalResult.Diagnostic> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<GoalResult.PhaseReport> reports = Collections.synchronizedList(new ArrayList<>());
    /** Cross-phase shared state — typed via {@link GoalKey}. Reads happen via PhaseContext. */
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    Goal(String name, boolean interactive, List<Phase> phases, List<GoalListener> listeners) {
        this.name = Objects.requireNonNull(name);
        this.interactive = interactive;
        this.phases = List.copyOf(phases);
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        // Validate the DAG up front so misconfigurations fail loudly.
        validate(this.phases);
    }

    public String name() { return name; }
    public boolean interactive() { return interactive; }
    public List<Phase> phases() { return phases; }

    public void addListener(GoalListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Request cancellation; running phases see {@link PhaseContext#cancelled} flip. */
    public void requestCancel() {
        userRequestedCancel.set(true);
        cancelled.set(true);
    }

    public GoalView snapshot() {
        return new GoalView(name, numerator.sum(), denominator.sum(),
                phases.size(), phasesComplete.get(), cancelled.get());
    }

    /**
     * Run the goal. Blocks until every phase reaches a terminal state.
     * Throws no checked exceptions — phase failures are folded into
     * {@link GoalResult#success}.
     */
    public GoalResult run() {
        Instant goalStart = Instant.now();

        // Step 1: scope estimation (parallel on IO).
        List<CompletableFuture<Integer>> scopeFutures = new ArrayList<>(phases.size());
        for (Phase p : phases) {
            scopeFutures.add(CompletableFuture.supplyAsync(p::estimateScope, JkThreads.io()));
        }
        Map<String, Integer> initialScope = new HashMap<>();
        for (int i = 0; i < phases.size(); i++) {
            try {
                int s = scopeFutures.get(i).get();
                initialScope.put(phases.get(i).name(), s);
                denominator.add(s);
            } catch (Exception e) {
                initialScope.put(phases.get(i).name(), 0);
            }
        }

        for (Phase p : phases) {
            statuses.put(p.name(), PhaseStatus.PENDING);
        }
        emit(l -> l.goalStart(snapshot()));

        // Step 2: run phases by readiness levels.
        Set<String> completedOk = new HashSet<>();
        List<Phase> remaining = new ArrayList<>(topoSort(phases));
        while (!remaining.isEmpty() && !cancelled.get()) {
            List<Phase> ready = remaining.stream()
                    .filter(p -> completedOk.containsAll(p.requires()))
                    .toList();
            if (ready.isEmpty()) {
                // Either remaining phases all depend on a failed predecessor
                // (mark them CANCELLED) or there's a programming bug. Bail.
                break;
            }
            remaining.removeAll(ready);
            boolean levelOk = runLevel(ready, initialScope);
            for (Phase p : ready) {
                if (statuses.get(p.name()) == PhaseStatus.SUCCESS) {
                    completedOk.add(p.name());
                }
            }
            if (!levelOk) {
                cancelled.set(true);
                break;
            }
        }

        // Any remaining (un-run) phases are CANCELLED because a dep failed.
        for (Phase p : remaining) {
            statuses.put(p.name(), PhaseStatus.CANCELLED);
            reports.add(new GoalResult.PhaseReport(p.name(), PhaseStatus.CANCELLED, Duration.ZERO));
        }

        boolean success = !cancelled.get() && phases.stream()
                .map(p -> statuses.get(p.name()))
                .allMatch(s -> s == PhaseStatus.SUCCESS);

        // Sort reports back into declaration order so the printed summary
        // matches the user's mental model of the build pipeline.
        Map<String, Integer> declOrder = new HashMap<>();
        for (int i = 0; i < phases.size(); i++) declOrder.put(phases.get(i).name(), i);
        List<GoalResult.PhaseReport> orderedReports = new ArrayList<>(reports);
        orderedReports.sort(Comparator.comparingInt(r -> declOrder.getOrDefault(r.name(), Integer.MAX_VALUE)));

        GoalResult result = new GoalResult(
                name, success,
                Duration.between(goalStart, Instant.now()),
                orderedReports, warnings, errors,
                cancelled.get(), userRequestedCancel.get());
        emit(l -> l.goalFinish(result));
        return result;
    }

    // --- Scheduling ----------------------------------------------------

    /**
     * Dispatch one readiness level. SYNC phases run inline (sequentially);
     * IO/CPU phases run in parallel on their respective pools. Returns
     * true when every phase in the level succeeded; false on the first
     * fail (triggers cooperative cancellation).
     */
    private boolean runLevel(List<Phase> ready, Map<String, Integer> initialScope) {
        List<CompletableFuture<PhaseStatus>> futures = new ArrayList<>();
        for (Phase p : ready) {
            int scope = initialScope.getOrDefault(p.name(), 0);
            statuses.put(p.name(), PhaseStatus.RUNNING);
            String phaseName = p.name();
            emit(l -> l.phaseStart(phaseName, scope));
            Executor exec = executorFor(p.kind());
            if (p.kind() == PhaseKind.SYNC) {
                futures.add(CompletableFuture.completedFuture(runOnePhase(p, scope)));
            } else {
                futures.add(CompletableFuture.supplyAsync(() -> runOnePhase(p, scope), exec));
            }
        }

        boolean ok = true;
        for (CompletableFuture<PhaseStatus> f : futures) {
            try {
                PhaseStatus s = f.get();
                if (s != PhaseStatus.SUCCESS) ok = false;
            } catch (Exception e) {
                ok = false;
            }
        }

        if (!ok && !cancelled.get()) {
            cancelled.set(true);
            // Give cooperative shutdown a window before we interrupt.
            try {
                Thread.sleep(COOPERATIVE_CANCEL_GRACE.toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            for (CompletableFuture<PhaseStatus> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
        }
        return ok;
    }

    private PhaseStatus runOnePhase(Phase phase, int initialScope) {
        Instant start = Instant.now();
        long startNum = numerator.sum();
        DefaultPhaseContext ctx = new DefaultPhaseContext(phase.name(), this);
        try {
            phase.execute(ctx);
            // Auto-fill: if the phase reported less progress than its scope
            // promised, top up the difference on a clean success so the bar
            // visually settles. Failures don't auto-fill — the bar stays
            // where the work actually stopped.
            long phaseProgress = numerator.sum() - startNum;
            long phaseScope = initialScope + ctx.scopeGrowth();
            if (phaseProgress < phaseScope) {
                int gap = (int) Math.min(Integer.MAX_VALUE, phaseScope - phaseProgress);
                numerator.add(gap);
                ctx.notifyProgress(gap);
            }
            statuses.put(phase.name(), PhaseStatus.SUCCESS);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new GoalResult.PhaseReport(phase.name(), PhaseStatus.SUCCESS, dur));
            phasesComplete.incrementAndGet();
            emit(l -> l.phaseFinish(phase.name(), PhaseStatus.SUCCESS, dur));
            return PhaseStatus.SUCCESS;
        } catch (Throwable t) {
            // If the phase body already recorded a specific error via
            // ctx.error(...) and then threw to signal failure, don't
            // pile on a duplicate "exception" diagnostic — the phase
            // told us exactly what went wrong. We only synthesise a
            // generic diagnostic when nothing else was reported.
            boolean cancel = cancelled.get();
            boolean phaseAlreadyReported = !cancel && errors.stream()
                    .anyMatch(d -> phase.name().equals(d.phase()));
            if (!phaseAlreadyReported) {
                errors.add(new GoalResult.Diagnostic(phase.name(),
                        cancel ? "cancelled" : "exception",
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
            PhaseStatus terminal = cancel ? PhaseStatus.CANCELLED : PhaseStatus.FAIL;
            statuses.put(phase.name(), terminal);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new GoalResult.PhaseReport(phase.name(), terminal, dur));
            phasesComplete.incrementAndGet();
            emit(l -> l.phaseFinish(phase.name(), terminal, dur));
            return terminal;
        }
    }

    private static Executor executorFor(PhaseKind kind) {
        return switch (kind) {
            case IO -> JkThreads.io();
            case CPU -> JkThreads.cpu();
            case SYNC -> Runnable::run; // not used; SYNC runs inline
        };
    }

    // --- Fanout helpers ------------------------------------------------

    void emit(java.util.function.Consumer<GoalListener> action) {
        for (GoalListener l : listeners) {
            try {
                action.accept(l);
            } catch (RuntimeException ignored) {
                // Listeners must not impact the goal's success/fail decision.
            }
        }
    }

    LongAdder numeratorRef() { return numerator; }
    LongAdder denominatorRef() { return denominator; }
    AtomicInteger phasesCompleteRef() { return phasesComplete; }
    AtomicBoolean cancelledRef() { return cancelled; }
    List<GoalResult.Diagnostic> warningsRef() { return warnings; }
    List<GoalResult.Diagnostic> errorsRef() { return errors; }
    ConcurrentHashMap<String, Object> stateRef() { return state; }

    /**
     * Read a phase-stashed value after {@link #run} has returned.
     * Command bodies use this to surface state phases produced —
     * resolved lockfile, JDK outcome, etc. — into their summary
     * output without needing a separate holder object.
     */
    public <T> java.util.Optional<T> get(GoalKey<T> key) {
        Object raw = state.get(key.name());
        if (raw == null) return java.util.Optional.empty();
        if (!key.type().isInstance(raw)) {
            throw new ClassCastException("goal state '" + key.name() + "' is "
                    + raw.getClass().getName() + " not " + key.type().getName());
        }
        return java.util.Optional.of(key.type().cast(raw));
    }

    // --- DAG validation + topo sort -----------------------------------

    private static void validate(List<Phase> phases) {
        Set<String> known = new HashSet<>();
        for (Phase p : phases) {
            if (!known.add(p.name())) {
                throw new IllegalArgumentException("duplicate phase name: " + p.name());
            }
        }
        for (Phase p : phases) {
            for (String req : p.requires()) {
                if (!known.contains(req)) {
                    throw new IllegalArgumentException(
                            "phase '" + p.name() + "' requires unknown '" + req + "'");
                }
            }
        }
        // Cheap cycle detection via topo sort attempt.
        topoSort(phases);
    }

    private static List<Phase> topoSort(List<Phase> phases) {
        Map<String, Phase> byName = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> reverse = new HashMap<>();
        for (Phase p : phases) {
            byName.put(p.name(), p);
            inDegree.put(p.name(), 0);
        }
        for (Phase p : phases) {
            for (String r : p.requires()) {
                inDegree.merge(p.name(), 1, Integer::sum);
                reverse.computeIfAbsent(r, k -> new ArrayList<>()).add(p.name());
            }
        }
        List<Phase> out = new ArrayList<>();
        List<String> ready = new ArrayList<>();
        for (var e : inDegree.entrySet()) {
            if (e.getValue() == 0) ready.add(e.getKey());
        }
        while (!ready.isEmpty()) {
            String n = ready.remove(0);
            out.add(byName.get(n));
            for (String down : reverse.getOrDefault(n, List.of())) {
                int newDeg = inDegree.merge(down, -1, Integer::sum);
                if (newDeg == 0) ready.add(down);
            }
        }
        if (out.size() != phases.size()) {
            throw new IllegalArgumentException("phase DAG has a cycle");
        }
        return out;
    }

    // --- Builder -------------------------------------------------------

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private boolean interactive = false;
        private final List<Phase> phases = new ArrayList<>();
        private final List<GoalListener> listeners = new ArrayList<>();

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /**
         * Interactive goals (wizards, prompts) suppress automatic
         * progress visualization — the foreground UI owns the terminal.
         * Listeners still get every event; only the default
         * progress-bar consumer respects this flag.
         */
        public Builder interactive(boolean v) { this.interactive = v; return this; }

        public Builder addPhase(Phase phase) {
            phases.add(phase);
            return this;
        }

        public Builder addListener(GoalListener listener) {
            listeners.add(listener);
            return this;
        }

        public Goal build() {
            return new Goal(name, interactive, phases, listeners);
        }
    }
}
