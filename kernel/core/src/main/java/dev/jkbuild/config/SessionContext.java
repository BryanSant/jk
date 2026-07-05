// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

/**
 * Ambient holder for the current {@link Session}, installed once at a front-end's entry point.
 *
 * <p><b>Transitional.</b> The re-foundation's end state threads {@link Session} explicitly through
 * the engine ({@code BuildPipeline.Inputs}/{@code PhaseContext}) so the kernel has no ambient state.
 * This holder exists to migrate the ~30 legacy {@code dev.jkbuild.config.SessionContext.current().config()} call sites incrementally
 * without a flag-day change; as each kernel consumer moves to an explicit {@code Session} parameter,
 * its dependence on this holder drops. Once the server path is fully threaded, this holder is
 * retained only for CLI-local convenience (the client may keep per-process ambient state) and is
 * never read by the engine.
 */
public final class SessionContext {

    private static volatile Session current = Session.defaults();

    private SessionContext() {}

    /** Install the resolved session for this invocation. */
    public static void install(Session session) {
        current = (session == null) ? Session.defaults() : session;
    }

    /** Convenience: install the config slice onto the current session (keeps the other fields). */
    public static void installConfig(JkConfig config) {
        install(current.withConfig(config == null ? JkConfig.empty() : config));
    }

    /** The current session (never null; {@link Session#defaults()} before install). */
    public static Session current() {
        return current;
    }

    /** Reset to defaults. Primarily for tests that share a JVM. */
    public static void reset() {
        current = Session.defaults();
    }
}
