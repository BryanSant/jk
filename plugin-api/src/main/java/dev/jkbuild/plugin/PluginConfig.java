// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One plugin-owned table of {@code jk.toml}, schema-validated into typed values
 * (build-plugins plan §3.1). The parser validates the raw table against the owning plugin's
 * {@code jk-plugin.toml} schema — required keys checked, defaults applied, types coerced — so
 * consumers read through the typed accessors without null-checking or re-validating.
 *
 * <p>Values are {@link String}, {@link Boolean}, {@link Long}, {@code List<String>}, or —
 * the fifth shape — a nested {@code Map<String, Map<String, Object>>} GROUP: a key declared
 * as a sub-table schema ({@code [android.signing.<name>]}) holds every named entry's own
 * validated map, read via {@link #group}. The SAME key may instead hold a {@link String}
 * REFERENCE naming one entry of a group declared elsewhere (the parser disambiguates by
 * shape: nested table = the group, string = a reference to one). A key that is absent here
 * was absent in the table <em>and</em> carried no default (the tri-state pattern:
 * {@code [spring-boot] aot} unset means "auto").
 *
 * <p>Insertion order is preserved for faithful round-tripping (the import renderer writes the
 * table back in declaration order).
 */
public record PluginConfig(String id, Map<String, Object> values) {

    public PluginConfig {
        Objects.requireNonNull(id, "id");
        values = values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A required-by-schema string — the schema guarantees presence, so absent is a bug. */
    public String string(String key) {
        Object v = values.get(key);
        if (v instanceof String s) return s;
        throw new IllegalStateException("[" + id + "]." + key + " missing — schema should have required it");
    }

    /** An optional string, empty when the key is absent. */
    public Optional<String> stringOpt(String key) {
        return values.get(key) instanceof String s ? Optional.of(s) : Optional.empty();
    }

    /** A tri-state bool: empty when unset (no default in the schema). */
    public Optional<Boolean> bool(String key) {
        return values.get(key) instanceof Boolean b ? Optional.of(b) : Optional.empty();
    }

    /** A bool with a call-site fallback (schema defaults are already applied at parse). */
    public boolean bool(String key, boolean fallback) {
        return bool(key).orElse(fallback);
    }

    /** A string list; empty when absent. */
    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        return values.get(key) instanceof List<?> l ? (List<String>) l : List.of();
    }

    /**
     * A nested-table group ({@code [android.signing.<name>]} entries): entry name → its
     * validated key/value map. Empty when the group is absent. Values inside follow the same
     * vocabulary as top-level config values.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> group(String key) {
        Object v = values.get(key);
        if (!(v instanceof Map<?, ?> m)) return Map.of();
        return (Map<String, Map<String, Object>>) m;
    }

    public long intValue(String key, long fallback) {
        return values.get(key) instanceof Long l ? l : fallback;
    }
}
