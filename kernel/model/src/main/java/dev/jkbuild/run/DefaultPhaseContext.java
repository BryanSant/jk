// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal {@link PhaseContext} the scheduler hands to each phase. Wires {@code progress /
 * updateScope / warn / error} into the goal's counters and listener fanout; tracks per-phase scope
 * growth so the scheduler can do the "auto-fill the bar to 100% on success" trick without
 * conflating it with the phase's own reports.
 *
 * <p>Package-private — phases only see this through the {@link PhaseContext} interface.
 */
final class DefaultPhaseContext implements PhaseContext {

    private final String phase;
    private final Goal goal;

    /**
     * Weighted phases occupy a fixed {@code weight} of bar ticks regardless of how many internal
     * units ({@code internalScope}) they count; their 0→internalScope progress is scaled into that
     * weight. Legacy phases (no explicit weight) keep the old 1:1 model: {@code progress} deltas hit
     * the numerator directly and {@code updateScope} grows the goal denominator.
     */
    /**
     * Interpolation ceiling: a time-driven phase eases up to this fraction of its weight and then
     * waits for real completion, so a too-short time estimate can't sprint the bar to 100% and stall
     * — the auto-fill on success closes the remaining sliver.
     */
    private static final double INTERP_CAP = 0.9;

    private final boolean weighted;
    private volatile long weight; // mutable: reweight() resizes the slice mid-run
    private final AtomicLong internalScope; // fraction denominator (weighted)
    private final AtomicLong internalDone = new AtomicLong(0);
    private final AtomicLong emitted = new AtomicLong(0); // weight-ticks added so far (weighted)
    private final AtomicInteger scopeGrowth = new AtomicInteger(0); // legacy denominator growth

    /** Wall-clock interpolation: 0 = off; else the phase's expected duration. */
    private volatile long expectedNanos; // scaled with the weight on reweight()

    private final long startNanos;

    DefaultPhaseContext(
            String phase,
            Goal goal,
            int internalScope,
            int weight,
            boolean weighted,
            long expectedNanos,
            long startNanos) {
        this.phase = phase;
        this.goal = goal;
        this.weighted = weighted;
        this.weight = weight;
        this.internalScope = new AtomicLong(internalScope);
        this.expectedNanos = weighted ? expectedNanos : 0;
        this.startNanos = startNanos;
    }

    @Override
    public void progress(int delta) {
        if (delta <= 0) return;
        if (!weighted) {
            goal.numeratorRef().add(delta);
            notifyProgress(delta);
            return;
        }
        internalDone.addAndGet(delta);
        advance();
    }

    /**
     * Weighted mode: move the numerator so the phase's share of the bar matches its internal progress
     * fraction ({@code internalDone / internalScope}) × {@code weight}. Real progress can fill the
     * whole weight.
     */
    private void advance() {
        long scope = internalScope.get();
        long done = internalDone.get();
        long target = scope > 0 ? Math.round(weight * Math.min(1.0, (double) done / scope)) : (done > 0 ? weight : 0);
        advanceTo(target);
    }

    /**
     * Wall-clock interpolation tick (driven by the goal's scheduler for opaque phases). Eases the
     * slice toward {@code weight × elapsed/expected}, capped at {@link #INTERP_CAP}. Never moves the
     * bar past where real progress already put it — {@link #advanceTo} is monotonic — so it only
     * fills the gap an opaque phase leaves while its single body call runs.
     */
    void tick(long nowNanos) {
        if (expectedNanos <= 0) return;
        double frac = Math.min(INTERP_CAP, (double) (nowNanos - startNanos) / expectedNanos);
        advanceTo(Math.round(weight * frac));
    }

    /** True when this phase eases its slice forward over time. */
    boolean interpolating() {
        return expectedNanos > 0;
    }

    /** Monotonic advance of the weighted numerator to {@code target} ticks. */
    private synchronized void advanceTo(long target) {
        long diff = target - emitted.get();
        if (diff > 0) {
            emitted.addAndGet(diff);
            goal.numeratorRef().add(diff);
            notifyProgress((int) Math.min(Integer.MAX_VALUE, diff));
        }
    }

    /** The phase's full bar budget — what auto-fill tops the numerator up to. */
    long phaseBudget() {
        return weighted ? weight : internalScope.get() + scopeGrowth.get();
    }

    @Override
    public synchronized void reweight(int newWeight) {
        if (!weighted || newWeight < 0) return;
        long old = weight;
        if (newWeight == old) return;
        goal.denominatorRef().add(newWeight - old);
        // Keep the per-weight interpolation duration constant as the slice resizes.
        if (old > 0 && expectedNanos > 0) {
            expectedNanos = expectedNanos * newWeight / old;
        }
        weight = newWeight;
        // If we shrank below what was already emitted (reweight called late), pull
        // the numerator back so the phase never exceeds its new budget. Meant to be
        // called early (emitted == 0), so this is normally a no-op.
        long over = emitted.get() - newWeight;
        if (over > 0) {
            emitted.addAndGet(-over);
            goal.numeratorRef().add(-over);
        }
    }

    @Override
    public void updateScope(int additional) {
        if (additional <= 0) return;
        if (weighted) {
            // The phase's bar share is fixed at `weight`; discovering more units
            // just re-bases the fraction (each unit is now worth fewer ticks). Grow
            // the internal denominator only — the goal denominator stays put, so the
            // bar neither grows nor jumps; advance() picks up the new ratio.
            internalScope.addAndGet(additional);
            return;
        }
        scopeGrowth.addAndGet(additional);
        // Grow the denominator only — the numerator stays where it is.
        // An older version of this method also advanced the numerator
        // proportionally to "preserve the fraction" so the bar wouldn't
        // appear to go backwards when a phase discovered more work
        // mid-flight; that turned out to compound catastrophically for
        // phases that call updateScope and progress in lockstep (each
        // iteration the proportional advance added to num while progress
        // also added to num, so num grew ~2× faster than den, producing
        // displays like "318 of 161"). Honest mid-phase backtracking is
        // strictly better than nonsense counts; phase-end auto-fill in
        // Goal.runOnePhase still closes any residual gap on success.
        goal.denominatorRef().add(additional);
        GoalView snap = goal.snapshot();
        goal.emit(l -> l.scopeUpdate(phase, additional, snap));
    }

    @Override
    public void label(String description) {
        String d = description == null ? "" : description;
        goal.emit(l -> l.label(phase, d));
    }

    @Override
    public void output(String line) {
        String s = line == null ? "" : line;
        goal.emit(l -> l.output(phase, s));
    }

    @Override
    public void warn(String code, String message) {
        goal.warningsRef().add(new GoalResult.Diagnostic(phase, code, message));
        goal.emit(l -> l.warn(phase, code, message));
    }

    @Override
    public void error(String code, String message) {
        goal.errorsRef().add(new GoalResult.Diagnostic(phase, code, message));
        goal.emit(l -> l.error(phase, code, message));
    }

    @Override
    public void error(String code, String message, String test, String exceptionClass) {
        goal.errorsRef().add(new GoalResult.Diagnostic(phase, code, message, test, exceptionClass));
        goal.emit(l -> l.error(phase, code, message, test, exceptionClass));
    }

    @Override
    public boolean cancelled() {
        return goal.cancelledRef().get();
    }

    @Override
    public <T> void put(GoalKey<T> key, T value) {
        // Allow null to be stored as a sentinel? Decided against — phases
        // should signal "no value" by not putting at all and downstream
        // reading via .get() returning empty.
        if (value == null) {
            goal.stateRef().remove(key.name());
        } else {
            goal.stateRef().put(key.name(), value);
        }
    }

    @Override
    public <T> java.util.Optional<T> get(GoalKey<T> key) {
        return goal.get(key);
    }

    @Override
    public <T> T require(GoalKey<T> key) {
        return goal.get(key)
                .orElseThrow(() -> new IllegalStateException("phase '"
                        + phase
                        + "' required key '"
                        + key.name()
                        + "' but it wasn't set by any upstream phase"));
    }

    /**
     * Fanout for synthetic auto-fill at phase end. Same shape as a regular {@link #progress} but
     * invoked by the scheduler, not by the phase body.
     */
    void notifyProgress(int delta) {
        if (delta <= 0) return;
        GoalView snap = goal.snapshot();
        goal.emit(l -> l.progress(phase, delta, snap));
    }
}
