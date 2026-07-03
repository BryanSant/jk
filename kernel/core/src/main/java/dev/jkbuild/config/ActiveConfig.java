// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

/**
 * Process-wide accessor for the resolved {@link JkConfig}.
 *
 * <p><b>Transitional shim.</b> The config now lives on the request-scoped {@link Session} held by
 * {@link SessionContext}; this class is a thin façade over {@code SessionContext.current().config()}
 * so the ~30 legacy {@code ActiveConfig.get()} call sites keep working while they migrate to reading
 * {@link Session} directly. New code should take a {@link Session} (threaded through the engine)
 * rather than call this. Removed once every consumer is migrated.
 */
public final class ActiveConfig {

    private ActiveConfig() {}

    /** Install the resolved config onto the current {@link Session}. */
    public static void install(JkConfig config) {
        JkConfig cfg = (config == null) ? JkConfig.empty() : config;
        SessionContext.install(SessionContext.current().withConfig(cfg));
    }

    /** Read the currently-installed config (never null). */
    public static JkConfig get() {
        return SessionContext.current().config();
    }

    /** Reset to the empty default. Primarily for tests that share a JVM. */
    public static void reset() {
        SessionContext.reset();
    }
}
