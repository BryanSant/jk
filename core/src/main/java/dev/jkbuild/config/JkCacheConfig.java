// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Per-project + user-global preferences for cache maintenance, parsed
 * from the {@code [cache]} table in {@code jk.toml} (project) and
 * {@code ~/.config/jk/jk.toml} (user-global). Project values override
 * user-global; both fall back to the defaults below.
 *
 * <p>Schema (default values shown):
 * <pre>{@code
 *   [cache]
 *   auto-prune          = true    # opportunistic prune in build/sync tail
 *   max-size-gb         = 20      # LRU budget; unset means no ceiling
 *   prune-interval-days = 7       # minimum gap between auto-prunes
 *   record-ttl-days     = 30      # action-record + sync-manifest TTL
 * }</pre>
 *
 * <p>Auto-prune is <strong>on by default</strong> — the prune is opaque
 * to the user (detached subprocess, output to a sidecar log), and
 * letting the cache grow unbounded is a worse default than a background
 * prune every 7 days. Opt out per project with
 * {@code [cache]\nauto-prune = false}.
 *
 * <p>Reading is intentionally lenient: malformed values fall back to the
 * default rather than failing the build. The cache layer is an
 * optimisation, not a correctness gate.
 */
public record JkCacheConfig(
        boolean autoPrune,
        Optional<Integer> maxSizeGb,
        int pruneIntervalDays,
        int recordTtlDays) {

    public static final JkCacheConfig DEFAULTS =
            new JkCacheConfig(true, Optional.empty(), 7, 30);

    /**
     * Layer this config <em>over</em> {@code base}: every present field
     * in {@code this} wins; absent fields fall through to {@code base}.
     * Boolean / int fields are always "present" — base only wins when
     * this config came from defaults. Distinguishing those would require
     * Optional everywhere; the simpler rule is "project's value is
     * always definitive when the project supplied a [cache] table."
     */
    public JkCacheConfig overlay(JkCacheConfig under) {
        return new JkCacheConfig(
                autoPrune,
                maxSizeGb.or(() -> under.maxSizeGb),
                pruneIntervalDays,
                recordTtlDays);
    }

    /**
     * Load from a TOML file's {@code [cache]} table. Missing file or
     * missing table → {@link #DEFAULTS}.
     */
    public static JkCacheConfig fromToml(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return DEFAULTS;
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) return DEFAULTS;
        TomlTable cache = toml.getTable("cache");
        if (cache == null) return DEFAULTS;

        boolean autoPrune = optionalBoolean(cache, "auto-prune").orElse(DEFAULTS.autoPrune);
        Optional<Integer> maxSize = optionalInt(cache, "max-size-gb");
        int interval = optionalInt(cache, "prune-interval-days").orElse(DEFAULTS.pruneIntervalDays);
        int ttl = optionalInt(cache, "record-ttl-days").orElse(DEFAULTS.recordTtlDays);

        return new JkCacheConfig(autoPrune, maxSize, interval, ttl);
    }

    private static Optional<Boolean> optionalBoolean(TomlTable table, String key) {
        Object v = table.get(key);
        return (v instanceof Boolean b) ? Optional.of(b) : Optional.empty();
    }

    private static Optional<Integer> optionalInt(TomlTable table, String key) {
        Object v = table.get(key);
        if (v instanceof Long l) {
            if (l < 0 || l > Integer.MAX_VALUE) return Optional.empty();
            return Optional.of(l.intValue());
        }
        return Optional.empty();
    }
}
