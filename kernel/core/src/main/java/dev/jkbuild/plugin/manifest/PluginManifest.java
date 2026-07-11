// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A build plugin's declarative manifest ({@code jk-plugin.toml} — build-plugins plan §3.1): the
 * table it owns, its schema, and (from P2 on) its declarative contributions. Parsed by
 * {@link PluginManifests}; the engine never loads plugin classes to evaluate this layer.
 *
 * @param id the plugin id ({@code [plugin] id})
 * @param table the jk.toml table this plugin owns ({@code [plugin] table})
 * @param version the plugin's own version
 * @param jkCompat the jk version range this plugin supports (informational in P1)
 * @param schema typed keys of the owned table, in declaration order
 */
public record PluginManifest(
        String id, String table, String version, String jkCompat, Map<String, SchemaKey> schema) {

    public PluginManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(table, "table");
        schema = schema == null || schema.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
    }

    /**
     * One schema key: its type ({@code string | bool | int | string-list}), whether the table
     * must declare it, and the value applied when absent ({@code null} = stay absent — the
     * tri-state pattern). {@code example} and {@code hint} feed the required-key error message
     * so schema-driven validation keeps the hand-written diagnostics' quality.
     */
    public record SchemaKey(String name, Type type, boolean required, Object defaultValue, String example, String hint) {

        public enum Type {
            STRING,
            BOOL,
            INT,
            STRING_LIST;

            public static Type parse(String raw, String where) {
                return switch (raw) {
                    case "string" -> STRING;
                    case "bool" -> BOOL;
                    case "int" -> INT;
                    case "string-list" -> STRING_LIST;
                    default -> throw new IllegalArgumentException(
                            where + ": unknown schema type `" + raw + "` (string|bool|int|string-list)");
                };
            }
        }

        /** The default's runtime shape, normalized to the {@code PluginConfig} value vocabulary. */
        public Object normalizedDefault() {
            if (defaultValue instanceof List<?> l) return List.copyOf(l.stream().map(String::valueOf).toList());
            return defaultValue;
        }
    }
}
