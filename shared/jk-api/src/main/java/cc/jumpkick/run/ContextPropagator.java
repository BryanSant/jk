// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.Callable;

/**
 * Dependency-inversion seam that lets the dependency-free jk-api ({@code :model}) carry a front-end's
 * ambient request context across the shared {@link JkThreads} pools without an upward compile edge on
 * {@code :core}, where {@code Session}/{@code SessionContext} live. ({@code :core} already declares
 * {@code api(project(":jk-api"))}, so a {@code :jk-api → :core} edge would be a cycle — mirrors the
 * {@code SessionCancel} seam.)
 *
 * <p>The problem it solves: {@code SessionContext} is {@link ScopedValue}-backed, and a
 * {@code ScopedValue} binding established by {@code where(session, …)} does <b>not</b> propagate to a
 * task handed to a pre-existing shared executor — the worker thread would read the process-static
 * session instead. {@link JkThreads#io()}/{@link JkThreads#cpu()} run all async engine work, so a
 * engine binding Session-A and dispatching a module build to a pool would leak the static session and
 * two concurrent requests could not be isolated.
 *
 * <p>A core-aware layer ({@code SessionContext}'s static initializer) {@link #bind binds} a
 * {@link Propagator} that captures the current session on the submitting thread and re-establishes it
 * (via the {@code ScopedValue}) on the worker thread. Because the re-bound session also carries the
 * per-request cancel token, {@code SessionCancel.cancelled()} — which reads {@code current().cancelled()}
 * — then reaches async worker-pool tasks too.
 *
 * <p>Until a propagator is bound, the seam is {@link #IDENTITY identity}: {@code wrap*} returns the
 * task unchanged and behavior is exactly as before (relevant to any consumer of {@code :model} that
 * uses the pools without {@code :core} loaded).
 */
public final class ContextPropagator {

    /**
     * Wraps a pool task so ambient context captured on the submitting thread is re-established on the
     * worker thread. {@code wrap*} is invoked <b>on the submitting thread</b> (capturing the ambient
     * context now) and returns a task that restores that context when later run on a worker.
     */
    public interface Propagator {

        /** Capture context now (submitting thread); return a Runnable that restores it when run. */
        Runnable wrapRunnable(Runnable r);

        /** Capture context now (submitting thread); return a Callable that restores it when called. */
        <T> Callable<T> wrapCallable(Callable<T> c);
    }

    /** Pass-through propagator: tasks are returned unchanged (unbound / default behavior). */
    public static final Propagator IDENTITY = new Propagator() {
        @Override
        public Runnable wrapRunnable(Runnable r) {
            return r;
        }

        @Override
        public <T> Callable<T> wrapCallable(Callable<T> c) {
            return c;
        }
    };

    private static volatile Propagator active = IDENTITY;

    private ContextPropagator() {}

    /** Install the context-capturing propagator. Last binding wins; a {@code null} resets to identity. */
    public static void bind(Propagator p) {
        active = (p == null) ? IDENTITY : p;
    }

    /** Wrap a Runnable via the active propagator (called on the submitting thread). */
    public static Runnable wrapRunnable(Runnable r) {
        return active.wrapRunnable(r);
    }

    /** Wrap a Callable via the active propagator (called on the submitting thread). */
    public static <T> Callable<T> wrapCallable(Callable<T> c) {
        return active.wrapCallable(c);
    }
}
