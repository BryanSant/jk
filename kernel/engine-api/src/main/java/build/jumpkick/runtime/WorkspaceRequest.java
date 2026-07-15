// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.model.JkBuild;
import java.nio.file.Path;
import java.util.Set;

/** What a front-end asks the engine to build (see {@code BuildService.buildWorkspace} in :engine). */
public record WorkspaceRequest(
        Path entryDir,
        JkBuild entryBuild,
        Path cache,
        Path jdksDir,
        int workers,
        String profile,
        boolean skipTests,
        boolean verbose,
        // Max modules to build concurrently. 0 = auto (schedule every ready level's units at once,
        // memory permitting — the parallel default); 1 = strictly serial; N = a rolling window of N.
        int maxModuleConcurrency,
        // Pre-computed dirty module dirs, or null to forecast internally. Lets a caller that
        // already forecasted (the CLI's fully-cached "all up to date" shortcut) skip a redundant pass.
        Set<Path> dirtyHint,
        // True (the single-CLI-process default): this call sizes and applies its own worker-JVM
        // memory plan (see the buildWorkspace javadoc). False: the caller already owns memory
        // planning for the process — e.g. a host serving several concurrent buildWorkspace calls
        // in one JVM plans once for its own assumed concurrency, and a per-call plan here would
        // stomp the shared HeapPlan/WorkerSlots state out from under a sibling call in flight.
        boolean applyMemoryPlan,
        // True (jk build): auto-freshen the merged workspace lock before any build decision via
        // ensureWorkspaceLockFresh — the guard BuildCommand used to run client-side, now applied
        // here so an engine-hosted build request freshens the lock engine-side. False for callers
        // that must use the pinned lock verbatim (jk verify's scratch rebuild).
        boolean freshenLock,
        // The variant selection ("", "release", "release|tier=free") — folded into each module's
        // plugin configs at parse time (VariantApply.apply); pipelines are parameterized, not configured.
        String variant,
        // Client-resolved env values for env:-indirected plugin config (signing secrets): the
        // user's shell env rides the request; the engine env is only the fallback.
        java.util.Map<String, String> clientEnv) {

    /** Back-compat: the pre-variant shape (default build type, no client env). */
    public WorkspaceRequest(
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            Set<Path> dirtyHint,
            boolean applyMemoryPlan,
            boolean freshenLock) {
        this(
                entryDir, entryBuild, cache, jdksDir, workers, profile, skipTests, verbose, maxModuleConcurrency,
                dirtyHint, applyMemoryPlan, freshenLock, "", java.util.Map.of());
    }

    /** This request with a variant selection + client-resolved env attached. */
    public WorkspaceRequest withVariant(String variant, java.util.Map<String, String> clientEnv) {
        return new WorkspaceRequest(
                entryDir, entryBuild, cache, jdksDir, workers, profile, skipTests, verbose, maxModuleConcurrency,
                dirtyHint, applyMemoryPlan, freshenLock, variant == null ? "" : variant,
                clientEnv == null ? java.util.Map.of() : clientEnv);
    }
}
