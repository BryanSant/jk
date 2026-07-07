// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global policy for the resident build daemon, parsed from the {@code [daemon]} table of
 * {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}). See {@code docs/daemon.md}.
 *
 * <p>Deliberately <strong>not project-overridable</strong>, for the same reason as {@link
 * JkCacheConfig}: the daemon's lifetime is a per-machine/per-user policy, not something a checked-out
 * project should be able to force on another project sharing the same {@code ~/.jk}.
 *
 * <pre>{@code
 * [daemon]
 * idle-minutes = 120   # JK_DAEMON_IDLE_MINUTES   see idleMinutes() javadoc for 0 / -1
 * }</pre>
 *
 * <p>Read once when a daemon starts (or is spawned) — a running daemon does not hot-reload this
 * file; {@code jk daemon stop} followed by a fresh lazy-start picks up a changed value.
 */
public record JkDaemonConfig(int idleMinutes) {

    public static final int DEFAULT_IDLE_MINUTES = 120;

    public static final JkDaemonConfig DEFAULTS = new JkDaemonConfig(DEFAULT_IDLE_MINUTES);

    /**
     * The effective daemon configuration for this machine: the user-global {@code
     * ~/.jk/config.toml} (missing/malformed → {@link #DEFAULTS}), overridden by {@code
     * JK_DAEMON_IDLE_MINUTES} when set (env &gt; user-config &gt; default).
     */
    public static JkDaemonConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkDaemonConfig resolve(Path userConfig, Function<String, String> env) {
        JkDaemonConfig base = fromToml(userConfig);
        return new JkDaemonConfig(EnvValues.intValue(env, "JK_DAEMON_IDLE_MINUTES")
                .filter(JkDaemonConfig::valid)
                .orElse(base.idleMinutes));
    }

    /**
     * Load from a TOML file's {@code [daemon]} table. Missing file, missing table, or malformed
     * file → {@link #DEFAULTS}. An out-of-range {@code idle-minutes} (anything below {@code -1})
     * also falls back to the default — the daemon layer is advisory, never a build-breaking gate.
     */
    public static JkDaemonConfig fromToml(Path file) {
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return DEFAULTS;
        TomlTable daemon = parsed.get().getTable("daemon");
        if (daemon == null) return DEFAULTS;
        int idleMinutes = TomlValues.optInt(daemon, "idle-minutes")
                .filter(JkDaemonConfig::valid)
                .orElse(DEFAULTS.idleMinutes);
        return new JkDaemonConfig(idleMinutes);
    }

    private static boolean valid(int idleMinutes) {
        return idleMinutes >= -1;
    }

    /** {@code true}: shut down the instant the in-flight workload drains, don't linger at all. */
    public boolean exitAsSoonAsIdle() {
        return idleMinutes == 0;
    }

    /** {@code true}: never self-terminate — only an explicit stop or process death ends the daemon. */
    public boolean neverExpires() {
        return idleMinutes == -1;
    }
}
