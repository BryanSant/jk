// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.run.PipelineListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Events emitted by {@link BuildService#buildWorkspace} as it drives a workspace build — the seam a
 * front-end (CLI live view, IDE, web app, GitHub Action) renders from without reproducing the build
 * loop. All methods have no-op defaults, so a headless caller overrides only what it needs.
 *
 * <p>The engine runs each module's {@link build.jumpkick.run.Pipeline}; {@link #onModuleStart} lets the
 * caller attach that pipeline's per-module {@link PipelineListener} (the CLI wires its existing renderers
 * here — no new rendering code). {@link #onPlan} carries per-module weights up front so a caller can
 * calibrate an aggregate progress bar before any module runs.
 */
public interface WorkspaceBuildListener {

    /** A no-op listener (a headless build that only wants the returned result). */
    WorkspaceBuildListener NOOP = new WorkspaceBuildListener() {};

    /** The resolved modules in dependency order, each with its assembled pipeline + estimated weight. */
    default void onPlan(List<ModulePlan> plan) {}

    /**
     * The module dependency graph: each module dir → the dirs that must build before it. Emitted once
     * up front (alongside {@link #onPlan}) so a caller can reconstruct the module DAG — used by the
     * engine's critical-path cache-benefit metric to compose per-module cold-cost estimates.
     */
    default void onModuleGraph(Map<Path, Set<Path>> prereqs) {}

    /**
     * A module is about to build. Return the {@link PipelineListener} to attach to its pipeline (its
     * step/progress/output events), or {@code null} / a no-op listener to ignore them.
     */
    default PipelineListener onModuleStart(ModulePlan module) {
        return new PipelineListener() {};
    }

    /** A module finished (success or failure). */
    default void onModuleFinish(ModuleOutcome outcome) {}

    /**
     * A wall-clock estimate (ms) for the whole build, computed by the engine's schedule-aware model —
     * emitted once up front (from learned/calibrated rates) and re-projected as modules finish and
     * real throughput is measured. A front-end renders it as a countdown; {@code 0} means "no
     * trustworthy estimate — count up instead".
     */
    default void onEtaEstimate(long millis) {}

    /** The whole workspace build finished. */
    default void onWorkspaceFinish(WorkspaceResult result) {}
}
