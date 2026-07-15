// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

/**
 * Ambient holder for the current {@link Session}, installed once at a front-end's entry point.
 *
 * <p><b>Transitional.</b> The re-foundation's end state threads {@link Session} explicitly through
 * the engine ({@code BuildPipelines.Inputs}/{@code StepContext}) so the kernel has no ambient state.
 * This holder exists to migrate the ~30 legacy {@code build.jumpkick.config.SessionContext.current().config()} call sites incrementally
 * without a flag-day change; as each kernel consumer moves to an explicit {@code Session} parameter,
 * its dependence on this holder drops. Once the server path is fully threaded, this holder is
 * retained only for CLI-local convenience (the client may keep per-process ambient state) and is
 * never read by the engine.
 *
 * <h2>Two lookup layers</h2>
 *
 * <p>{@link #current()} prefers a per-thread {@link ScopedValue} binding when one is in effect, and
 * otherwise falls back to a single process-wide static:
 *
 * <ul>
 *   <li><b>Scoped binding</b> — set via {@link #where(Session, java.util.concurrent.Callable)} or
 *       {@link #runWhere(Session, Runnable)}. Each dynamic scope carries its own {@link Session}, so
 *       two (or more) builds can run concurrently in one JVM — each thread observes only the session
 *       bound for its own scope, with no clobbering. This is the concurrency unlock.
 *   <li><b>Process static</b> — the {@link #install(Session) installed} fallback used by the CLI,
 *       which runs a single build per process and never opens a {@code where} scope. Before any
 *       install it is {@link Session#defaults()}.
 * </ul>
 *
 * <h2>Mutators touch only the static fallback</h2>
 *
 * <p>{@link #install}, {@link #installConfig}, and {@link #reset} mutate <em>only</em> the process
 * static; a {@code ScopedValue} binding is immutable for the life of its scope. Consequently, inside
 * a {@code where}/{@code runWhere} scope the session is fixed — to change it, open a nested scope
 * with a new {@code Session}. Note that {@link #installConfig} reads {@link #current()} (which may be
 * the scoped value) but writes the static; that asymmetry is harmless for the CLI, which never uses
 * {@code where}, and callers inside a scope should thread config via a nested {@code where} rather
 * than {@code installConfig}.
 */
public final class SessionContext {

    /** Per-thread binding set by {@link #where}/{@link #runWhere}; enables concurrent in-JVM builds. */
    private static final ScopedValue<Session> SCOPED = ScopedValue.newInstance();

    /** Process-wide fallback for the single-build CLI path. */
    private static volatile Session current = Session.defaults();

    static {
        // Install the Session-capturing propagator into :model's ContextPropagator seam so a
        // where()-bound session propagates to tasks run on the shared JkThreads.io()/cpu() pools
        // (a ScopedValue binding otherwise does NOT reach a pre-existing shared executor). wrap* is
        // called on the SUBMITTING thread — capturing current() there — and rebinds it on the worker.
        // Because the rebound session carries its per-request cancel token, SessionCancel.cancelled()
        // (which reads current().cancelled()) now also reaches async worker-pool tasks. This class
        // loads early (everything reads current()), so the binding is in place before any pool
        // submission. Under a single static session (the CLI) this just rebinds the same session —
        // behavior unchanged.
        build.jumpkick.run.ContextPropagator.bind(
                new build.jumpkick.run.ContextPropagator.Propagator() {
                    @Override
                    public Runnable wrapRunnable(Runnable r) {
                        Session s = current();
                        return () -> runWhere(s, r);
                    }

                    @Override
                    public <T> java.util.concurrent.Callable<T> wrapCallable(
                            java.util.concurrent.Callable<T> c) {
                        Session s = current();
                        return () -> where(s, c);
                    }
                });
    }

    private SessionContext() {}

    /** Install the resolved session onto the process-static fallback for this invocation. */
    public static void install(Session session) {
        current = (session == null) ? Session.defaults() : session;
    }

    /** Convenience: install the config slice onto the current session (keeps the other fields). */
    public static void installConfig(JkConfig config) {
        install(current().withConfig(config == null ? JkConfig.empty() : config));
    }

    /**
     * The current session (never null; {@link Session#defaults()} before install). Prefers the
     * {@link ScopedValue} binding of the calling thread when one is bound, else the process static.
     */
    public static Session current() {
        return SCOPED.isBound() ? SCOPED.get() : current;
    }

    /**
     * Run {@code body} with {@code s} bound as the current session for the dynamic extent of the call
     * (and any threads it structurally forks). {@link #current()} returns {@code s} within that scope.
     */
    public static <T> T where(Session s, java.util.concurrent.Callable<T> body) throws Exception {
        // Java 25's finalized Carrier.call takes a ScopedValue.CallableOp; adapt the Callable via a
        // method reference (Callable.call and CallableOp.call share the R call() throws X shape).
        return ScopedValue.where(SCOPED, s).<T, Exception>call(body::call);
    }

    /** Void-returning variant of {@link #where(Session, java.util.concurrent.Callable)}. */
    public static void runWhere(Session s, Runnable body) {
        ScopedValue.where(SCOPED, s).run(body);
    }

    /** Reset the process-static fallback to defaults. Primarily for tests that share a JVM. */
    public static void reset() {
        current = Session.defaults();
    }
}
