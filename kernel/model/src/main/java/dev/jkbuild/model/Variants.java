// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code [variants]} block of {@code jk.toml} — the project's declared product dimensions.
 *
 * <p>Vocabulary (variants-plan §0): a <em>dimension</em> is an axis ({@code contentType}, or the
 * built-in {@link #BUILD_TYPE}); a <em>value</em> is one named entry along a dimension
 * ({@code demo}); a <em>variant</em> is the resolved combination — one value per dimension. A
 * declared dimension without a {@code default} makes selection mandatory: building without
 * choosing a value is an error, never a silent "no variant" — that exclusivity is what keeps
 * action keys and caches sound.
 *
 * <p>Each value is a config <em>overlay</em>, folded over the base manifest at parse time
 * (variants are computed goal parameterizations, not configured objects):
 *
 * <ul>
 *   <li>{@code extra-src} — additional source roots, appended to {@code [build] extra-src}.
 *   <li>Dependency-scope sub-tables ({@code [variants.<dim>.<value>.dependencies]} and the other
 *       scoped spellings) — appended to the matching scope. {@code jk lock} resolves the
 *       <em>union</em> of every value's dependency overlays so one lockfile covers every variant.
 *   <li>Plugin sub-tables ({@code [variants.<dim>.<value>.android]}) — key overlays onto that
 *       plugin's config table, validated against the plugin's schema.
 * </ul>
 *
 * <p>Overlay precedence: custom dimensions in declaration order, then {@link #BUILD_TYPE} last.
 *
 * <p>{@code build-type} is a built-in dimension that always exists: values {@code debug} and
 * {@code release} are valid even when undeclared, the default is {@code debug}, and
 * {@code jk build --release} selects {@code release}. Declaring
 * {@code [variants.build-type.<name>]} attaches overlays (or adds custom build types).
 *
 * <p>Deliberately <em>not</em> overlayable: {@code [project]} identity, repositories, profiles,
 * features, and toolchain flags — a variant that changes coordinates is a different project, and
 * how-to-build knobs belong to {@code [profiles]} (see docs/variants.md).
 */
public record Variants(List<Dimension> dimensions) {

    /** The built-in dimension name selected by {@code --release} / defaulted to {@code debug}. */
    public static final String BUILD_TYPE = "build-type";

    /** Built-in {@code build-type} values, valid even when undeclared. */
    public static final List<String> BUILT_IN_BUILD_TYPES = List.of("debug", "release");

    public static final Variants EMPTY = new Variants(List.of());

    /**
     * A build's variant selection — one value per dimension. The wire spelling (CLI → engine
     * requests) is {@code ""} (all defaults) / {@code "release"} / {@code "release|contentType=demo"}:
     * the bare token is the build type, {@code <dim>=<value>} pairs select custom dimensions, and
     * {@code *=<value>} is the single-dimension convenience ({@code --variant <value>} with no
     * {@code <dim>=}). A selection naming a dimension a module doesn't declare is ignored by that
     * module — selections are workspace-wide.
     */
    public record Selection(String buildType, Map<String, String> values) {

        public static final Selection DEFAULTS = new Selection(null, Map.of());

        public Selection {
            values = values == null || values.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public static Selection parse(String raw) {
            if (raw == null || raw.isBlank()) return DEFAULTS;
            String buildType = null;
            Map<String, String> values = new LinkedHashMap<>();
            for (String part : raw.split("\\|")) {
                if (part.isBlank()) continue;
                int eq = part.indexOf('=');
                if (eq > 0) values.put(part.substring(0, eq), part.substring(eq + 1));
                else buildType = part;
            }
            return new Selection(buildType, values);
        }

        public String encode() {
            StringBuilder b = new StringBuilder(buildType == null ? "" : buildType);
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (b.length() > 0) b.append('|');
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            return b.toString();
        }
    }

    public Variants {
        Objects.requireNonNull(dimensions, "dimensions");
        dimensions = List.copyOf(dimensions);
    }

    public boolean isEmpty() {
        return dimensions.isEmpty();
    }

    public Optional<Dimension> dimension(String name) {
        return dimensions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    /** Declared dimensions minus {@code build-type}, in declaration order. */
    public List<Dimension> custom() {
        return dimensions.stream().filter(d -> !BUILD_TYPE.equals(d.name())).toList();
    }

    /**
     * {@code build} with every value's dependency overlays folded in — the <em>union</em> graph
     * {@code jk lock} resolves so one lockfile covers every variant (cargo's model; see
     * docs/variants.md for the over-constraint trade-off). Exact duplicate declarations collapse;
     * same-module different-selector declarations stay, intersecting as ordinary constraints.
     */
    public static JkBuild unionDependencies(JkBuild build) {
        if (build.variants().isEmpty()) return build;
        Map<Scope, java.util.LinkedHashSet<Dependency>> merged = new java.util.EnumMap<>(Scope.class);
        build.dependencies().byScope().forEach((scope, deps) ->
                merged.computeIfAbsent(scope, s -> new java.util.LinkedHashSet<>()).addAll(deps));
        for (Dimension dimension : build.variants().dimensions()) {
            for (Value value : dimension.values().values()) {
                value.dependencies().forEach((scope, deps) ->
                        merged.computeIfAbsent(scope, s -> new java.util.LinkedHashSet<>()).addAll(deps));
            }
        }
        Map<Scope, List<Dependency>> out = new java.util.EnumMap<>(Scope.class);
        merged.forEach((scope, deps) -> out.put(scope, List.copyOf(deps)));
        return build.withDependencies(new JkBuild.Dependencies(out));
    }

    /**
     * One axis: its values (name → overlay) and the optional default value name. The built-in
     * {@code build-type} dimension defaults to {@code debug} whether or not it is declared; a
     * custom dimension without a default makes selection mandatory.
     */
    public record Dimension(String name, String defaultValue, Map<String, Value> values) {

        public Dimension {
            Objects.requireNonNull(name, "name");
            values = values == null || values.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(values));
            if (defaultValue != null && !values.containsKey(defaultValue) && !isBuiltIn(name, defaultValue)) {
                throw new IllegalArgumentException("[variants." + name + "] default = \"" + defaultValue
                        + "\" names no declared value (declared: " + values.keySet() + ")");
            }
        }

        private static boolean isBuiltIn(String dimension, String value) {
            return BUILD_TYPE.equals(dimension) && BUILT_IN_BUILD_TYPES.contains(value);
        }
    }

    /**
     * One value's overlay: extra source roots, per-scope dependency additions, and per-plugin
     * config-key overlays (plugin table name → keys, schema-validated by the parser).
     */
    public record Value(
            List<String> extraSrc,
            Map<Scope, List<Dependency>> dependencies,
            Map<String, Map<String, Object>> pluginOverlays) {

        public static final Value EMPTY = new Value(List.of(), Map.of(), Map.of());

        public Value {
            extraSrc = extraSrc == null ? List.of() : List.copyOf(extraSrc);
            dependencies = dependencies == null || dependencies.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(dependencies));
            pluginOverlays = pluginOverlays == null || pluginOverlays.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(pluginOverlays));
        }
    }
}
