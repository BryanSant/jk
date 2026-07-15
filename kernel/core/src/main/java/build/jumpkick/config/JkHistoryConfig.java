// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import build.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global policy for the persisted build-history journal, parsed from the {@code [history]}
 * table of {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}).
 *
 * <p>Unlike {@link JkHttpConfig} (where the table's presence is the enable switch), history is
 * <strong>on by default</strong> — every engine build is journaled under {@code
 * ~/.jk/state/builds/journal/} so a restart never loses the activity feed. This view therefore has
 * always-present defaults like {@link JkCacheConfig}. Set {@code enabled = false} to turn capture
 * and serving off entirely.
 *
 * <p>Retention is bounded on two independent axes, whichever bites first; a background prune at the
 * engine's idle boundary enforces them (deleting whole per-build snapshot dirs, oldest first):
 *
 * <pre>{@code
 * [history]
 * enabled      = true    # JK_HISTORY_ENABLED       false = no capture, no serving
 * max-age-days = 30      # JK_HISTORY_MAX_AGE_DAYS  drop entries older than this; 0 = no age limit
 * max-disk-mb  = 512     # JK_HISTORY_MAX_DISK_MB   total budget; oldest pruned past it; 0 = no cap
 * }</pre>
 *
 * <p>Deliberately <strong>not project-overridable</strong> (same reasoning as {@link JkHttpConfig}
 * / {@link JkCacheConfig}): the journal is machine-scoped. Read once when an engine starts.
 * Reading is lenient — a malformed value falls back to its default rather than failing anything.
 */
public record JkHistoryConfig(boolean enabled, int maxAgeDays, int maxDiskMb) {

    public static final boolean DEFAULT_ENABLED = true;

    public static final int DEFAULT_MAX_AGE_DAYS = 30;

    public static final int DEFAULT_MAX_DISK_MB = 512;

    public static final JkHistoryConfig DEFAULTS =
            new JkHistoryConfig(DEFAULT_ENABLED, DEFAULT_MAX_AGE_DAYS, DEFAULT_MAX_DISK_MB);

    /**
     * The effective history configuration for this machine: the user-global {@code
     * ~/.jk/config.toml} (missing/malformed → {@link #DEFAULTS}), with each key overridden by its
     * {@code JK_HISTORY_*} environment variable when set (env &gt; user-config &gt; default).
     */
    public static JkHistoryConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkHistoryConfig resolve(Path userConfig, Function<String, String> env) {
        JkHistoryConfig base = fromToml(userConfig);
        return new JkHistoryConfig(
                EnvValues.bool(env, "JK_HISTORY_ENABLED").orElse(base.enabled),
                envNonNegativeInt(env, "JK_HISTORY_MAX_AGE_DAYS").orElse(base.maxAgeDays),
                envNonNegativeInt(env, "JK_HISTORY_MAX_DISK_MB").orElse(base.maxDiskMb));
    }

    private static java.util.Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /**
     * Load from a TOML file's {@code [history]} table — the explicit-path seam used by {@link
     * #resolve()} and by tests. Missing file, missing table, or malformed file → {@link #DEFAULTS}.
     * Negative integers are rejected (a negative age/budget is meaningless) and fall back to the
     * default.
     */
    public static JkHistoryConfig fromToml(Path file) {
        java.util.Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return DEFAULTS;
        TomlTable history = parsed.get().getTable("history");
        if (history == null) return DEFAULTS;

        boolean enabled = TomlValues.optBoolean(history, "enabled").orElse(DEFAULTS.enabled);
        int maxAge = nonNegative(TomlValues.optInt(history, "max-age-days")).orElse(DEFAULTS.maxAgeDays);
        int maxDisk = nonNegative(TomlValues.optInt(history, "max-disk-mb")).orElse(DEFAULTS.maxDiskMb);
        return new JkHistoryConfig(enabled, maxAge, maxDisk);
    }

    private static java.util.Optional<Integer> nonNegative(java.util.Optional<Integer> value) {
        return value.filter(i -> i >= 0);
    }

    /** Age budget in milliseconds, or {@code 0} for "no age limit" (keep regardless of age). */
    public long maxAgeMillis() {
        return maxAgeDays <= 0 ? 0L : (long) maxAgeDays * 86_400_000L;
    }

    /** Disk budget in bytes, or {@code 0} for "no cap" (keep regardless of total size). */
    public long maxDiskBytes() {
        return maxDiskMb <= 0 ? 0L : (long) maxDiskMb * 1024L * 1024L;
    }
}
