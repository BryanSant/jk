// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

/**
 * Process-wide accessor for the resolved {@link JkConfig}. Populated once at CLI entry by {@code
 * Jk.execute} before any subcommand runs, then read freely by anything that needs to know the
 * user's color / offline / progress / etc. preference.
 *
 * <p>Anything calling {@link #get} before {@link #install} returns {@link JkConfig#empty()}, which
 * means "every consumer falls back to its own default" — safe for tests that bypass the CLI entry
 * point.
 */
public final class ActiveConfig {

    private static volatile JkConfig active = JkConfig.empty();

    private ActiveConfig() {}

    /** Install the resolved config. Subsequent {@link #get} calls return this. */
    public static void install(JkConfig config) {
        active = (config == null) ? JkConfig.empty() : config;
    }

    /** Read the currently-installed config (never null). */
    public static JkConfig get() {
        return active;
    }

    /** Reset to the empty default. Primarily for tests that share a JVM. */
    public static void reset() {
        active = JkConfig.empty();
    }
}
