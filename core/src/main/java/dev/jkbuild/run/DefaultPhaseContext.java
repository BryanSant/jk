// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal {@link PhaseContext} the scheduler hands to each phase.
 * Wires {@code progress / updateScope / warn / error} into the goal's
 * counters and listener fanout; tracks per-phase scope growth so the
 * scheduler can do the "auto-fill the bar to 100% on success" trick
 * without conflating it with the phase's own reports.
 *
 * <p>Package-private — phases only see this through the
 * {@link PhaseContext} interface.
 */
final class DefaultPhaseContext implements PhaseContext {

    private final String phase;
    private final Goal goal;
    private final AtomicInteger scopeGrowth = new AtomicInteger(0);

    DefaultPhaseContext(String phase, Goal goal) {
        this.phase = phase;
        this.goal = goal;
    }

    @Override
    public void progress(int delta) {
        if (delta <= 0) return;
        goal.numeratorRef().add(delta);
        notifyProgress(delta);
    }

    @Override
    public void updateScope(int additional) {
        if (additional <= 0) return;
        scopeGrowth.addAndGet(additional);
        // Advance the numerator proportionally so the goal-level fraction
        // never decreases. Without this, a phase that discovers more work
        // mid-flight (e.g. dependency resolution finding 93 deps after scope
        // was estimated at 0) would make the bar appear to go backwards.
        long currentNum = goal.numeratorRef().sum();
        long currentDen = goal.denominatorRef().sum();
        long numAdvance = currentDen > 0
                ? (long) Math.floor((double) additional * currentNum / currentDen)
                : 0;
        goal.denominatorRef().add(additional);
        if (numAdvance > 0) goal.numeratorRef().add(numAdvance);
        GoalView snap = goal.snapshot();
        goal.emit(l -> l.scopeUpdate(phase, additional, snap));
    }

    @Override
    public void label(String description) {
        String d = description == null ? "" : description;
        goal.emit(l -> l.label(phase, d));
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
        return goal.get(key).orElseThrow(() ->
                new IllegalStateException("phase '" + phase + "' required key '"
                        + key.name() + "' but it wasn't set by any upstream phase"));
    }

    /** How much {@link #updateScope} grew this phase's denominator. */
    int scopeGrowth() {
        return scopeGrowth.get();
    }

    /**
     * Fanout for synthetic auto-fill at phase end. Same shape as a
     * regular {@link #progress} but invoked by the scheduler, not by
     * the phase body.
     */
    void notifyProgress(int delta) {
        if (delta <= 0) return;
        GoalView snap = goal.snapshot();
        goal.emit(l -> l.progress(phase, delta, snap));
    }
}
