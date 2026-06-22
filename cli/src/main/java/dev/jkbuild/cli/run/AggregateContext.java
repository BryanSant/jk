// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalResult;

import java.util.List;

/**
 * Shared sink for a workspace build: one {@link CommandManager} (goal mode) that
 * every module's goal feeds through an {@link AggregateModuleListener}, so all
 * modules render into a single bar + phase list.
 *
 * <p>Modules run sequentially. {@code completedBase} is the summed scope of the
 * modules that have already finished; while module <i>k</i> runs, the aggregate
 * numerator is {@code completedBase + moduleNumerator}.
 *
 * <p>When {@link #calibrate} has been called (the workspace pre-scan summed every
 * module's estimated ticks up front), the bar's denominator is pinned to that
 * fixed {@code total} and advances 0→100% across the whole workspace without
 * resetting per module. Each module owns a <i>slice</i> of {@code total} equal to
 * its pre-scan estimate; its own 0→100% is scaled into that slice and {@code base}
 * advances by exactly the slice on completion, so the bar never backtracks at a
 * module boundary and the denominator never grows past the up-front estimate.
 * Both {@code jk build} and {@code jk native} pre-scan and calibrate. Without
 * calibration (an uncalibrated caller, slice 0) it falls back to the growing
 * {@code completedBase + moduleDenominator}.
 */
public final class AggregateContext {

    private final CommandManager cm;
    private long completedBase;
    private long total;            // fixed aggregate denominator, 0 until calibrated
    // Calibrated path: each still-running module's current contribution to its
    // slice. The aggregate numerator is completedBase + Σ(these), so modules
    // building concurrently sum into the bar instead of clobbering one another
    // (last-writer-wins). A module is removed here when it completes — its slice
    // moves into completedBase, so it's never double-counted.
    private final java.util.Map<String, Long> moduleAdvanced = new java.util.HashMap<>();
    private volatile List<GoalResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /**
     * Pin the bar's denominator to the workspace's aggregate estimated ticks and
     * paint an empty bar at {@code 0 / total}. Called once before any module runs.
     */
    public synchronized void calibrate(long total) {
        this.total = total;
        cm.progress(0, total);
    }

    /** The aggregate denominator, or 0 when {@link #calibrate} wasn't called. */
    public synchronized long total() {
        return total;
    }

    /**
     * Adjust the aggregate denominator by {@code delta} when a running module
     * reweights a phase mid-run (e.g. a full compile turns out to be a cheap
     * restore). The module grows/shrinks its own slice by the same delta, so
     * {@code Σ slices} stays equal to {@code total}. The repaint happens on the
     * module's next {@link #moduleProgress}, so this only moves the denominator.
     */
    public synchronized void growTotal(long delta) {
        total += delta;
    }

    /** Scope of all modules finished so far. */
    public synchronized long completedBase() {
        return completedBase;
    }

    /**
     * Advance the base past a finished module. Calibrated callers pass the
     * module's reserved slice (its pre-scan estimate) so {@code Σ slices == total};
     * uncalibrated callers pass the module's live final denominator.
     */
    public synchronized void completeModule(long moduleScope) {
        completedBase += moduleScope;
    }

    /**
     * Calibrated, concurrency-safe progress for one module: record this module's
     * current contribution to its slice and repaint the bar at
     * {@code completedBase + Σ(all running modules)}. Use this (not the raw
     * setter) when modules build in parallel so their progress sums.
     */
    public synchronized void moduleProgress(String module, long advanced) {
        moduleAdvanced.put(module, advanced);
        long sum = completedBase;
        for (long v : moduleAdvanced.values()) sum += v;
        cm.progress(Math.min(sum, total), total);
    }

    /**
     * Calibrated completion: fold the module's reserved {@code slice} into the
     * base and drop its running contribution, so the bar neither backtracks nor
     * double-counts. {@code Σ slices == total}.
     */
    public synchronized void completeModule(String module, long slice) {
        moduleAdvanced.remove(module);
        completedBase += slice;
        cm.progress(Math.min(completedBase, total), total);
    }

    /** Errors from the most recently failed module goal. */
    public List<GoalResult.Diagnostic> lastErrors() { return lastErrors; }

    public void notifyErrors(List<GoalResult.Diagnostic> errors) { this.lastErrors = errors; }
}
