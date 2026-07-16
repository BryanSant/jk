// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.JkThreads;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * One {@code jk} invocation's orchestration: a named DAG of {@link Step}s with collected progress,
 * warnings, errors, and a terminal {@link PipelineResult}.
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li><b>Initialize.</b> Topologically order steps, compute initial ticks (sum of {@link
 *       Step#estimateTicks} across all steps, gathered in parallel on {@link JkThreads#io()}).
 *   <li><b>Run.</b> Walk the DAG by readiness levels. Within each level, dispatch IO steps to
 *       {@link JkThreads#io()}, CPU steps to {@link JkThreads#cpu()}, and run SYNC steps on the
 *       caller's thread sequentially. Wait for the level to complete before opening the next.
 *   <li><b>Report.</b> Build a {@link PipelineResult} listing every step's status + duration plus the
 *       accumulated diagnostics. Emit {@link PipelineListener#pipelineFinish} and return.
 * </ol>
 *
 * <p>Cancellation is cooperative — failures or external requests set a flag the running steps
 * poll. A 200ms grace period is enforced via thread interrupt after the flag flips.
 */
public final class Pipeline {

    /** How long async steps get to notice cancellation before we interrupt them. */
    static final Duration COOPERATIVE_CANCEL_GRACE = Duration.ofMillis(200);

    /** How often the interpolation interpTimer eases opaque steps forward. */
    private static final long INTERP_TICK_MS = 100;

    /**
     * Expected wall-clock per unit of step weight, the divisor for time-based interpolation. Weights
     * are tuned as rough time shares, so treating a weight unit as a fixed slice of time gives each
     * opaque step a plausible duration; the {@code INTERP_CAP} ceiling absorbs the inevitable
     * misestimate.
     */
    private static final long INTERP_NANOS_PER_WEIGHT = 150_000_000L; // ~150ms

    /** Running steps currently being eased forward by the interpolation interpTimer. */
    private final Set<DefaultStepContext> easing = ConcurrentHashMap.newKeySet();

    private final String name;
    private final boolean interactive;
    private final List<Step> steps;
    private final List<PipelineListener> listeners;

    private final LongAdder numerator = new LongAdder();
    private final LongAdder denominator = new LongAdder();
    private final AtomicInteger stepsComplete = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * True only when {@link #requestCancel} was invoked by the host (typically the SIGINT bridge).
     * The {@link #cancelled} flag is also flipped internally when a step fails so still-pending
     * steps get torn down — so {@code cancelled} alone can't tell us "the user asked to abort" vs "a
     * step failed and we're winding the others down." Listeners need that distinction to pick the
     * right end-of-pipeline rendering.
     */
    private final AtomicBoolean userRequestedCancel = new AtomicBoolean(false);

    private final Map<String, StepStatus> statuses = new ConcurrentHashMap<>();
    private final List<PipelineResult.Diagnostic> warnings = Collections.synchronizedList(new ArrayList<>());
    private final List<PipelineResult.Diagnostic> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<PipelineResult.StepReport> reports = Collections.synchronizedList(new ArrayList<>());

    /** Cross-step shared state — typed via {@link PipelineKey}. Reads happen via StepContext. */
    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    Pipeline(String name, boolean interactive, List<Step> steps, List<PipelineListener> listeners) {
        this.name = Objects.requireNonNull(name);
        this.interactive = interactive;
        this.steps = List.copyOf(steps);
        this.listeners = new CopyOnWriteArrayList<>(listeners);
        // Validate the DAG up front so misconfigurations fail loudly.
        validate(this.steps);
    }

    public String name() {
        return name;
    }

    public boolean interactive() {
        return interactive;
    }

    public List<Step> steps() {
        return steps;
    }

    public void addListener(PipelineListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Request cancellation; running steps see {@link StepContext#cancelled} flip. */
    public void requestCancel() {
        userRequestedCancel.set(true);
        cancelled.set(true);
    }

    public PipelineView snapshot() {
        return new PipelineView(
                name, numerator.sum(), denominator.sum(), steps.size(), stepsComplete.get(), cancelled.get());
    }

    /**
     * Sum of every step's {@link Step#estimateWeight} <em>without running the pipeline</em> — the same
     * total {@link #run()} sets as the denominator at start. Used to pre-calibrate a workspace's
     * cumulative progress bar to the aggregate tick total before any module builds, and as each
     * module's slice of it. A step whose estimate throws contributes 0, mirroring {@code run()}.
     */
    public int estimatedTotalWeight() {
        List<CompletableFuture<Integer>> futures = new ArrayList<>(steps.size());
        for (Step p : steps) {
            futures.add(CompletableFuture.supplyAsync(p::estimateWeight, JkThreads.io()));
        }
        int total = 0;
        for (CompletableFuture<Integer> f : futures) {
            try {
                total += f.get();
            } catch (Exception ignored) {
                // best-effort estimate; a failing step just contributes 0
            }
        }
        return total;
    }

    /**
     * Run the pipeline. Blocks until every step reaches a terminal state. Throws no checked exceptions —
     * step failures are folded into {@link PipelineResult#success()}.
     */
    public PipelineResult run() {
        Instant pipelineStart = Instant.now();

        // Step 1: ticks estimation (parallel on IO). `initialTicks` is each step's
        // internal unit count (how granularly it ticks); `weights` is its share of
        // the bar (time-proportional). The denominator sums weights, not units, so a
        // file-count-scoped compile can't dwarf a quick step. A step without an
        // explicit weight reuses its ticks, so the denominator is unchanged for it.
        List<CompletableFuture<Integer>> tickFutures = new ArrayList<>(steps.size());
        for (Step p : steps) {
            tickFutures.add(CompletableFuture.supplyAsync(p::estimateTicks, JkThreads.io()));
        }
        Map<String, Integer> initialTicks = new HashMap<>();
        Map<String, Integer> weights = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Step p = steps.get(i);
            int s = 0;
            try {
                s = tickFutures.get(i).get();
            } catch (Exception ignored) {
                // best-effort; a failing estimate contributes 0
            }
            initialTicks.put(p.name(), s);
            // Reuse the computed ticks when no weight was set — avoids re-walking
            // sources just to learn the weight equals the ticks.
            int w = p.hasExplicitWeight() ? p.estimateWeight() : s;
            weights.put(p.name(), w);
            denominator.add(w);
        }

        for (Step p : steps) {
            statuses.put(p.name(), StepStatus.PENDING);
        }
        emit(l -> l.pipelineStart(snapshot()));

        // Interpolation interpTimer: while an opaque step runs, ease its bar slice
        // forward over elapsed time so the bar doesn't sit flat until the step's
        // single body call returns. Only started when some step opts in.
        ScheduledExecutorService interpTimer = startInterpolationTimer();

        // Step 2: run steps by readiness levels.
        Set<String> completedOk = new HashSet<>();
        List<Step> remaining = new ArrayList<>(topoSort(steps));
        try {
            while (!remaining.isEmpty() && !cancelled.get()) {
                List<Step> ready = remaining.stream()
                        .filter(p -> completedOk.containsAll(p.requires()))
                        .toList();
                if (ready.isEmpty()) {
                    // Either remaining steps all depend on a failed predecessor
                    // (mark them CANCELLED) or there's a programming bug. Bail.
                    break;
                }
                remaining.removeAll(ready);
                boolean levelOk = runLevel(ready, initialTicks, weights);
                for (Step p : ready) {
                    if (isOk(statuses.get(p.name()))) {
                        completedOk.add(p.name());
                    }
                }
                if (!levelOk) {
                    cancelled.set(true);
                    break;
                }
            }
        } finally {
            if (interpTimer != null) interpTimer.shutdownNow();
        }

        // Any remaining (un-run) steps are CANCELLED because a dep failed.
        for (Step p : remaining) {
            statuses.put(p.name(), StepStatus.CANCELLED);
            reports.add(new PipelineResult.StepReport(p.name(), StepStatus.CANCELLED, Duration.ZERO, p.requires()));
        }

        boolean success = !cancelled.get()
                && steps.stream().map(p -> statuses.get(p.name())).allMatch(Pipeline::isOk);

        // Sort reports back into declaration order so the printed summary
        // matches the user's mental model of the build pipeline.
        Map<String, Integer> declOrder = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) declOrder.put(steps.get(i).name(), i);
        List<PipelineResult.StepReport> orderedReports = new ArrayList<>(reports);
        orderedReports.sort(Comparator.comparingInt(r -> declOrder.getOrDefault(r.name(), Integer.MAX_VALUE)));

        PipelineResult result = new PipelineResult(
                name,
                success,
                Duration.between(pipelineStart, Instant.now()),
                orderedReports,
                warnings,
                errors,
                cancelled.get(),
                userRequestedCancel.get());
        emit(l -> l.pipelineFinish(result));
        return result;
    }

    // --- Scheduling ----------------------------------------------------

    /**
     * Start the daemon interpTimer that eases interpolated steps forward over time, or return {@code
     * null} when no step opts into interpolation (so the common case spins up no thread). The caller
     * shuts it down when the run finishes.
     */
    private ScheduledExecutorService startInterpolationTimer() {
        if (steps.stream().noneMatch(Step::interpolated)) return null;
        ScheduledExecutorService interpTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-progress-interp");
            t.setDaemon(true);
            return t;
        });
        interpTimer.scheduleAtFixedRate(
                () -> {
                    long now = System.nanoTime();
                    for (DefaultStepContext c : easing) {
                        try {
                            c.tick(now);
                        } catch (RuntimeException ignored) {
                            /* never break the interpTimer */
                        }
                    }
                },
                INTERP_TICK_MS,
                INTERP_TICK_MS,
                TimeUnit.MILLISECONDS);
        return interpTimer;
    }

    /**
     * Dispatch one readiness level. SYNC steps run inline (sequentially); IO/CPU steps run in
     * parallel on their respective pools. Returns true when every step in the level succeeded; false
     * on the first fail (triggers cooperative cancellation).
     */
    private boolean runLevel(List<Step> ready, Map<String, Integer> initialTicks, Map<String, Integer> weights) {
        List<CompletableFuture<StepStatus>> futures = new ArrayList<>();
        for (Step p : ready) {
            int ticks = initialTicks.getOrDefault(p.name(), 0);
            int weight = weights.getOrDefault(p.name(), ticks);
            statuses.put(p.name(), StepStatus.RUNNING);
            String stepName = p.name();
            Phase stepPhase = p.phase().orElse(null);
            emit(l -> l.stepStart(stepName, stepPhase, ticks));
            Executor exec = executorFor(p.kind());
            if (p.kind() == StepKind.SYNC) {
                futures.add(CompletableFuture.completedFuture(runOneStep(p, ticks, weight)));
            } else {
                futures.add(CompletableFuture.supplyAsync(() -> runOneStep(p, ticks, weight), exec));
            }
        }

        boolean ok = true;
        for (CompletableFuture<StepStatus> f : futures) {
            try {
                StepStatus s = f.get();
                if (!isOk(s)) ok = false;
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
            for (CompletableFuture<StepStatus> f : futures) {
                if (!f.isDone()) f.cancel(true);
            }
        }
        return ok;
    }

    /**
     * A step status that counts as "the step is done and the build may proceed": a real success or
     * a cache-hit/up-to-date {@link StepStatus#SKIPPED}. Used for dependency gating and overall
     * success so a fully-cached build (every step SKIPPED) is still a success.
     */
    private static boolean isOk(StepStatus s) {
        return s == StepStatus.SUCCESS || s == StepStatus.SKIPPED;
    }

    private StepStatus runOneStep(Step step, int initialTicks, int weight) {
        Instant start = Instant.now();
        long startNum = numerator.sum();
        long startNanos = System.nanoTime();
        long expectedNanos = step.interpolated() ? (long) weight * INTERP_NANOS_PER_WEIGHT : 0;
        DefaultStepContext ctx = new DefaultStepContext(
                step.name(), this, initialTicks, weight, step.hasExplicitWeight(), expectedNanos, startNanos);
        boolean ticked = ctx.interpolating();
        if (ticked) easing.add(ctx);
        try {
            step.execute(ctx);
            // Stop interpolating before auto-fill so no late tick races the top-up.
            if (ticked) easing.remove(ctx);
            // Auto-fill: if the step reported less progress than its bar budget
            // promised, top up the difference on a clean success so the bar
            // visually settles. Failures don't auto-fill — the bar stays
            // where the work actually stopped.
            long stepProgress = numerator.sum() - startNum;
            long stepBudget = ctx.stepBudget();
            if (stepProgress < stepBudget) {
                int gap = (int) Math.min(Integer.MAX_VALUE, stepBudget - stepProgress);
                numerator.add(gap);
                ctx.notifyProgress(gap);
            }
            // A step that reported no real work (outputs up-to-date / served from cache via
            // ctx.cached()) terminates SKIPPED, not SUCCESS. SKIPPED counts as "ok" everywhere the
            // pipeline decides success (see isOk), so it never fails a build — it only feeds the
            // dashboard's per-project cache-hit ("steps skipped") ratio.
            StepStatus terminal = ctx.wasCached() ? StepStatus.SKIPPED : StepStatus.SUCCESS;
            statuses.put(step.name(), terminal);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new PipelineResult.StepReport(step.name(), terminal, dur, step.requires()));
            stepsComplete.incrementAndGet();
            emit(l -> l.stepFinish(step.name(), step.phase().orElse(null), terminal, dur));
            return terminal;
        } catch (Throwable t) {
            if (ticked) easing.remove(ctx);
            // If the step body already recorded a specific error via
            // ctx.error(...) and then threw to signal failure, don't
            // pile on a duplicate "exception" diagnostic — the step
            // told us exactly what went wrong. We only synthesise a
            // generic diagnostic when nothing else was reported.
            boolean cancel = cancelled.get();
            boolean stepAlreadyReported =
                    !cancel && errors.stream().anyMatch(d -> step.name().equals(d.step()));
            if (!stepAlreadyReported) {
                errors.add(new PipelineResult.Diagnostic(
                        step.name(),
                        cancel ? "cancelled" : "exception",
                        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            }
            StepStatus terminal = cancel ? StepStatus.CANCELLED : StepStatus.FAIL;
            statuses.put(step.name(), terminal);
            Duration dur = Duration.between(start, Instant.now());
            reports.add(new PipelineResult.StepReport(step.name(), terminal, dur, step.requires()));
            stepsComplete.incrementAndGet();
            emit(l -> l.stepFinish(step.name(), step.phase().orElse(null), terminal, dur));
            return terminal;
        }
    }

    private static Executor executorFor(StepKind kind) {
        return switch (kind) {
            case IO -> JkThreads.io();
            case CPU -> JkThreads.cpu();
            case SYNC -> Runnable::run; // not used; SYNC runs inline
        };
    }

    // --- Fanout helpers ------------------------------------------------

    void emit(java.util.function.Consumer<PipelineListener> action) {
        for (PipelineListener l : listeners) {
            try {
                action.accept(l);
            } catch (RuntimeException ignored) {
                // Listeners must not impact the pipeline's success/fail decision.
            }
        }
    }

    LongAdder numeratorRef() {
        return numerator;
    }

    LongAdder denominatorRef() {
        return denominator;
    }

    AtomicInteger stepsCompleteRef() {
        return stepsComplete;
    }

    AtomicBoolean cancelledRef() {
        return cancelled;
    }

    List<PipelineResult.Diagnostic> warningsRef() {
        return warnings;
    }

    List<PipelineResult.Diagnostic> errorsRef() {
        return errors;
    }

    ConcurrentHashMap<String, Object> stateRef() {
        return state;
    }

    /**
     * Read a step-stashed value after {@link #run} has returned. Command bodies use this to surface
     * state steps produced — resolved lockfile, JDK outcome, etc. — into their summary output
     * without needing a separate holder object.
     */
    public <T> java.util.Optional<T> get(PipelineKey<T> key) {
        Object raw = state.get(key.name());
        if (raw == null) return java.util.Optional.empty();
        if (!key.type().isInstance(raw)) {
            throw new ClassCastException("pipeline state '"
                    + key.name()
                    + "' is "
                    + raw.getClass().getName()
                    + " not "
                    + key.type().getName());
        }
        return java.util.Optional.of(key.type().cast(raw));
    }

    // --- DAG validation + topo sort -----------------------------------

    private static void validate(List<Step> steps) {
        Set<String> known = new HashSet<>();
        for (Step p : steps) {
            if (!known.add(p.name())) {
                throw new IllegalArgumentException("duplicate step name: " + p.name());
            }
        }
        for (Step p : steps) {
            for (String req : p.requires()) {
                if (!known.contains(req)) {
                    throw new IllegalArgumentException("step '" + p.name() + "' requires unknown '" + req + "'");
                }
            }
        }
        // Cheap cycle detection via topo sort attempt.
        topoSort(steps);
    }

    private static List<Step> topoSort(List<Step> steps) {
        Map<String, Step> byName = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> reverse = new HashMap<>();
        for (Step p : steps) {
            byName.put(p.name(), p);
            inDegree.put(p.name(), 0);
        }
        for (Step p : steps) {
            for (String r : p.requires()) {
                inDegree.merge(p.name(), 1, Integer::sum);
                reverse.computeIfAbsent(r, k -> new ArrayList<>()).add(p.name());
            }
        }
        List<Step> out = new ArrayList<>();
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
        if (out.size() != steps.size()) {
            throw new IllegalArgumentException("step DAG has a cycle");
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
        private final List<Step> steps = new ArrayList<>();
        private final List<PipelineListener> listeners = new ArrayList<>();

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /**
         * Interactive pipelines (wizards, prompts) suppress automatic progress visualization — the
         * foreground UI owns the terminal. Listeners still get every event; only the default
         * progress-bar consumer respects this flag.
         */
        public Builder interactive(boolean v) {
            this.interactive = v;
            return this;
        }

        public Builder addStep(Step step) {
            steps.add(step);
            return this;
        }

        /**
         * Append every step from {@code more} whose name is not already present — ordered-set
         * semantics. Use this to compose a pipeline from multiple step sequences without duplicate steps.
         */
        public Builder addAllSteps(java.util.Collection<Step> more) {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Step p : steps) existing.add(p.name());
            for (Step p : more) {
                if (existing.add(p.name())) steps.add(p);
            }
            return this;
        }

        public Builder addListener(PipelineListener listener) {
            listeners.add(listener);
            return this;
        }

        public Pipeline build() {
            return new Pipeline(name, interactive, steps, listeners);
        }
    }
}
