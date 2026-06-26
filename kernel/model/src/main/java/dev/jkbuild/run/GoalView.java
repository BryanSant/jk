// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

/**
 * Read-only snapshot of a {@link Goal}'s state at a single moment.
 * Passed to listeners on every event so they can render without
 * re-querying the goal.
 *
 * <p>Both {@code numerator} and {@code denominator} can grow over time
 * ({@code updateScope} grows the denominator; {@code progress} grows
 * the numerator). The current rendered percentage is
 * {@code numerator / denominator} clamped to {@code [0.0, 1.0]}.
 */
public record GoalView(
        String goalName, long numerator, long denominator, int phasesTotal, int phasesComplete, boolean cancelled) {

    public double fraction() {
        if (denominator <= 0) return 0.0;
        double frac = (double) numerator / (double) denominator;
        if (frac < 0.0) return 0.0;
        if (frac > 1.0) return 1.0;
        return frac;
    }

    public int percent() {
        return (int) Math.round(fraction() * 100);
    }
}
