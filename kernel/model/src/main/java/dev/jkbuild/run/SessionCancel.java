// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.util.function.BooleanSupplier;

/**
 * Dependency-inversion seam that lets the dependency-free jk-api ({@code :model}) observe a
 * front-end's session-level cancellation without an upward compile dependency on {@code :core},
 * where {@code Session}/{@code SessionContext} and the per-session {@code CancelToken} live.
 * ({@code :core} already declares {@code api(project(":model"))}, so a {@code :model → :core} edge
 * would be a cycle — this seam is how the intent "OR the session cancel into the per-goal poll" is
 * expressed while keeping the module DAG legal.)
 *
 * <p>A core-aware layer (the engine) {@link #bind binds} a probe that reads the current session's
 * cancel token; {@link DefaultPhaseContext#cancelled()} ORs {@link #cancelled()} into its per-goal
 * flag, so every phase that already polls {@link PhaseContext#cancelled()} also honors a
 * session/Ctrl-C cancel — no new polling sites.
 *
 * <p>Until a probe is bound, {@link #cancelled()} returns {@code false} and behavior is unchanged.
 * The probe is read on whatever thread polls {@code cancelled()}, so its own thread-affinity
 * caveats (e.g. {@code ScopedValue} bindings not propagating to shared worker pools) surface here.
 */
public final class SessionCancel {

    private static volatile BooleanSupplier probe = () -> false;

    private SessionCancel() {}

    /** Install the session-cancel probe. Idempotent; the last binding wins. A {@code null} disables it. */
    public static void bind(BooleanSupplier p) {
        probe = (p == null) ? () -> false : p;
    }

    /** Whether the bound session (if any) has requested cancellation. */
    public static boolean cancelled() {
        return probe.getAsBoolean();
    }
}
