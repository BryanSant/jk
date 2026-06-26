// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * User-global preferences for cache maintenance, parsed from the {@code [cache]}
 * table of {@code ~/.jk/config.toml} ({@link JkDirs#userConfigFile()}).
 *
 * <p>Cache settings are <strong>not project-overridable</strong>: a project
 * {@code jk.toml}'s {@code [cache]} table is intentionally ignored. The cache
 * lives on your machine and is shared across every project, so its size budget
 * and prune cadence are a machine/user preference — not something a checked-out
 * project may dictate. Each key <em>can</em> be overridden by its environment
 * variable (env &gt; user-config &gt; default), which keeps jk container- and
 * CI-friendly: an image can tune the cache via {@code JK_*} without editing a
 * file. Resolve the effective config with {@link #resolve()}; the
 * {@code (Path, env)} overload is the seam for tests.
 *
 * <p>Schema (default values shown), with the env var that overrides each key:
 * <pre>{@code
 *   [cache]
 *   auto-prune          = true    # JK_AUTO_PRUNE           opportunistic prune in build/sync tail
 *   max-size-gb         = 20      # JK_MAX_SIZE_GB          LRU budget; unset means no ceiling
 *   prune-interval-days = 7       # JK_PRUNE_INTERVAL_DAYS  minimum gap between auto-prunes
 *   record-ttl-days     = 30      # JK_RECORD_TTL_DAYS      action-record + sync-manifest TTL
 * }</pre>
 *
 * <p>Auto-prune is <strong>on by default</strong> — the prune is opaque
 * to the user (detached subprocess, output to a sidecar log), and
 * letting the cache grow unbounded is a worse default than a background
 * prune every 7 days. Opt out with {@code [cache]\nauto-prune = false} in
 * {@code ~/.jk/config.toml}.
 *
 * <p>Reading is intentionally lenient: malformed values fall back to the
 * default rather than failing the build. The cache layer is an
 * optimisation, not a correctness gate.
 */
public record JkCacheConfig(boolean autoPrune, Optional<Integer> maxSizeGb, int pruneIntervalDays, int recordTtlDays) {

    public static final JkCacheConfig DEFAULTS = new JkCacheConfig(true, Optional.empty(), 7, 30);

    /**
     * The effective cache configuration for this machine: the user-global
     * {@code ~/.jk/config.toml} (missing/malformed → {@link #DEFAULTS}), with each
     * key overridden by its {@code JK_*} environment variable when set
     * (env &gt; user-config &gt; default). Project files are not consulted.
     */
    public static JkCacheConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env) {
        JkCacheConfig base = fromToml(userConfig);
        return new JkCacheConfig(
                EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(base.autoPrune),
                envNonNegativeInt(env, "JK_MAX_SIZE_GB").or(() -> base.maxSizeGb),
                envNonNegativeInt(env, "JK_PRUNE_INTERVAL_DAYS").orElse(base.pruneIntervalDays),
                envNonNegativeInt(env, "JK_RECORD_TTL_DAYS").orElse(base.recordTtlDays));
    }

    private static Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /**
     * Load from a TOML file's {@code [cache]} table — the explicit-path seam used
     * by {@link #resolve()} and by tests. Missing file, missing table, or
     * malformed file → {@link #DEFAULTS}. Reads values through the shared
     * {@link TomlValues} coercion; negative integers are rejected (a negative
     * budget/interval is meaningless) and fall back to the default.
     */
    public static JkCacheConfig fromToml(Path file) {
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return DEFAULTS;
        TomlTable cache = parsed.get().getTable("cache");
        if (cache == null) return DEFAULTS;

        boolean autoPrune = TomlValues.optBoolean(cache, "auto-prune").orElse(DEFAULTS.autoPrune);
        Optional<Integer> maxSize = nonNegative(TomlValues.optInt(cache, "max-size-gb"));
        int interval =
                nonNegative(TomlValues.optInt(cache, "prune-interval-days")).orElse(DEFAULTS.pruneIntervalDays);
        int ttl = nonNegative(TomlValues.optInt(cache, "record-ttl-days")).orElse(DEFAULTS.recordTtlDays);

        return new JkCacheConfig(autoPrune, maxSize, interval, ttl);
    }

    private static Optional<Integer> nonNegative(Optional<Integer> value) {
        return value.filter(i -> i >= 0);
    }
}
