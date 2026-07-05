// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.run.GoalListener;
import java.util.List;

/**
 * Events emitted by {@link BuildService#buildWorkspace} as it drives a workspace build — the seam a
 * front-end (CLI live view, IDE, web app, GitHub Action) renders from without reproducing the build
 * loop. All methods have no-op defaults, so a headless caller overrides only what it needs.
 *
 * <p>The engine runs each module's {@link dev.jkbuild.run.Goal}; {@link #onModuleStart} lets the
 * caller attach that goal's per-module {@link GoalListener} (the CLI wires its existing renderers
 * here — no new rendering code). {@link #onPlan} carries per-module weights up front so a caller can
 * calibrate an aggregate progress bar before any module runs.
 */
public interface WorkspaceBuildListener {

    /** A no-op listener (a headless build that only wants the returned result). */
    WorkspaceBuildListener NOOP = new WorkspaceBuildListener() {};

    /** The resolved modules in dependency order, each with its assembled goal + estimated weight. */
    default void onPlan(List<BuildService.ModulePlan> plan) {}

    /**
     * A module is about to build. Return the {@link GoalListener} to attach to its goal (its
     * phase/progress/output events), or {@code null} / a no-op listener to ignore them.
     */
    default GoalListener onModuleStart(BuildService.ModulePlan module) {
        return new GoalListener() {};
    }

    /** A module finished (success or failure). */
    default void onModuleFinish(BuildService.ModuleOutcome outcome) {}

    /**
     * A wall-clock estimate (ms) for the whole build, computed by the engine's schedule-aware model —
     * emitted once up front (from learned/calibrated rates) and re-projected as modules finish and
     * real throughput is measured. A front-end renders it as a countdown; {@code 0} means "no
     * trustworthy estimate — count up instead".
     */
    default void onEtaEstimate(long millis) {}

    /** The whole workspace build finished. */
    default void onWorkspaceFinish(BuildService.WorkspaceResult result) {}
}
