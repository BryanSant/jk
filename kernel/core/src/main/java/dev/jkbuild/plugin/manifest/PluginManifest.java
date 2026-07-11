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
        String id,
        String table,
        String version,
        String jkCompat,
        Map<String, SchemaKey> schema,
        Contributions contributions) {

    public PluginManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(table, "table");
        schema = schema == null || schema.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));
        contributions = contributions == null ? Contributions.NONE : contributions;
    }

    /**
     * The declarative layer's build contributions (plan §3.1): pure data evaluated engine-side
     * with zero plugin code — BOM auto-import, default compiler args, Kotlin compiler plugins.
     * Each entry may carry one {@link Condition}; anything conditional beyond the closed
     * predicate set belongs in a code hook (P3), never here.
     */
    public record Contributions(
            List<PlatformDependency> platformDependencies,
            List<CompilerArgs> compilerArgs,
            List<KotlinPlugin> kotlinPlugins) {

        public static final Contributions NONE = new Contributions(List.of(), List.of(), List.of());

        public Contributions {
            platformDependencies = platformDependencies == null ? List.of() : List.copyOf(platformDependencies);
            compilerArgs = compilerArgs == null ? List.of() : List.copyOf(compilerArgs);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
        }

        public boolean isEmpty() {
            return platformDependencies.isEmpty() && compilerArgs.isEmpty() && kotlinPlugins.isEmpty();
        }
    }

    /**
     * {@code [[contribute.platform-dependency]]}: a BOM-style platform-scope dependency,
     * {@code group:artifact:version} with {@code ${…}} interpolation. Injected at parse time —
     * before resolution — so its condition may not use {@code classpath-has}.
     */
    public record PlatformDependency(String coordinate, Condition when) {}

    /**
     * {@code [[contribute.compiler-args]]}: default args for javac / kotlinc. Each arg is added
     * only when the user's own args don't already carry it (user wins).
     */
    public record CompilerArgs(List<String> javac, List<String> kotlin, Condition when) {
        public CompilerArgs {
            javac = javac == null ? List.of() : List.copyOf(javac);
            kotlin = kotlin == null ? List.of() : List.copyOf(kotlin);
        }
    }

    /**
     * {@code [[contribute.kotlin-plugin]]}: a Kotlin compiler plugin — BTA plugin id, jar
     * coordinate ({@code ${kotlin.version}} keeps it lockstep with the compiler), plugin options.
     */
    public record KotlinPlugin(String id, String coordinate, List<String> options, Condition when) {
        public KotlinPlugin {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * The CLOSED condition vocabulary (plan §3.1): four predicates, no expression language —
     * a manifest that needs more writes a code hook instead. Exactly one predicate per
     * {@code when} table.
     */
    public sealed interface Condition {

        /** {@code when = { classpath-has = "group:artifact" }} — the module is on the resolved classpath. */
        record ClasspathHas(String module) implements Condition {}

        /** {@code when = { config = "key", equals = "value" }} — the owned table's key equals a value. */
        record ConfigEquals(String key, String equals) implements Condition {}

        /** {@code when = { native-declared = true }} — the project declares {@code [native]}. */
        record NativeDeclared() implements Condition {}

        /** {@code when = { kotlin-project = true }} — the project declares Kotlin. */
        record KotlinProject() implements Condition {}
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
