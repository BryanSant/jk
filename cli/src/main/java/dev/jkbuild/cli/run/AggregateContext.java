// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalResult;

import java.util.List;

/**
 * Shared sink for a workspace build: one {@link CommandManager} (goal mode) that
 * every member's goal feeds through an {@link AggregateMemberListener}, so all
 * members render into a single bar + phase list.
 *
 * <p>Members run sequentially. {@code completedBase} is the summed scope of the
 * members that have already finished; while member <i>k</i> runs, the aggregate
 * numerator is {@code completedBase + memberNumerator}.
 *
 * <p>When {@link #calibrate} has been called (the workspace pre-scan summed every
 * member's estimated ticks up front), the bar's denominator is pinned to that
 * fixed {@code total} and advances 0→100% across the whole workspace without
 * resetting per member. Each member owns a <i>slice</i> of {@code total} equal to
 * its pre-scan estimate; its own 0→100% is scaled into that slice and {@code base}
 * advances by exactly the slice on completion, so the bar never backtracks at a
 * member boundary and the denominator never grows past the up-front estimate.
 * Without calibration (e.g. {@code --use-host}, where goals run in a forked JVM
 * and can't be pre-scanned) it falls back to the growing
 * {@code completedBase + memberDenominator}.
 */
public final class AggregateContext {

    private final CommandManager cm;
    private long completedBase;
    private long total;            // fixed aggregate denominator, 0 until calibrated
    private volatile List<GoalResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /**
     * Pin the bar's denominator to the workspace's aggregate estimated ticks and
     * paint an empty bar at {@code 0 / total}. Called once before any member runs.
     */
    public synchronized void calibrate(long total) {
        this.total = total;
        cm.progress(0, total);
    }

    /** The fixed aggregate denominator, or 0 when {@link #calibrate} wasn't called. */
    public synchronized long total() {
        return total;
    }

    /** Scope of all members finished so far. */
    public synchronized long completedBase() {
        return completedBase;
    }

    /**
     * Advance the base past a finished member. Calibrated callers pass the
     * member's reserved slice (its pre-scan estimate) so {@code Σ slices == total};
     * uncalibrated callers pass the member's live final denominator.
     */
    public synchronized void completeMember(long memberScope) {
        completedBase += memberScope;
    }

    /** Errors from the most recently failed member goal. */
    public List<GoalResult.Diagnostic> lastErrors() { return lastErrors; }

    public void notifyErrors(List<GoalResult.Diagnostic> errors) { this.lastErrors = errors; }
}
