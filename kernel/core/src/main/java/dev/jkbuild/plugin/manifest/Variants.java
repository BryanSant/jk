// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Variant resolution (build-plugins §3.1 / android-plan §3.1): variants are <em>computed goal
 * parameterizations</em>, not configured objects. A build selects one entry per declared variant
 * axis ({@code jk build --release} → build-type {@code release}); {@link #apply} folds the
 * selected overlays into each plugin's config so every downstream consumer — describe keys, step
 * action keys, contribution predicates, {@code [[packaging.variant]]} resolution, worker specs —
 * sees one flat effective config and needs no variant awareness at all.
 *
 * <p>Precedence (AGP's): base scalars, then flavor overlays (declaration order of dimensions),
 * then the build-type overlay. The selected names are injected as {@code build-type} and
 * {@code flavor} / {@code flavor.<dim>} config keys, so manifests condition on them
 * ({@code [[packaging.variant]] when = { config = "build-type", equals = "release" }}).
 *
 * <p><b>Signing groups + secrets:</b> a non-axis sub-table group referenced by the effective
 * {@code signing} key flattens to {@code signing.<key>} entries. Values use {@code env:NAME}
 * indirection — resolved here against the client-supplied environment first (the thin-client
 * posture publish credentials established: the client resolves what its shell sees and ships it
 * on the request), then the engine's own environment. Keys marked {@code secret = true} in the
 * sub-schema never enter the config (so no spec, describe payload, or token renders them):
 * they ride the {@link Applied#secrets} side channel into the package spec only, and action keys
 * carry only their digest.
 */
public final class Variants {

    private Variants() {}

    /** The wire spelling: {@code ""} (defaults) / {@code "release"} / {@code "release|tier=free"}. */
    public record Selection(String buildType, Map<String, String> flavors) {

        public static final Selection DEFAULTS = new Selection(null, Map.of());

        public static Selection parse(String raw) {
            if (raw == null || raw.isBlank()) return DEFAULTS;
            String buildType = null;
            Map<String, String> flavors = new LinkedHashMap<>();
            for (String part : raw.split("\\|")) {
                if (part.isBlank()) continue;
                int eq = part.indexOf('=');
                if (eq > 0) flavors.put(part.substring(0, eq), part.substring(eq + 1));
                else buildType = part;
            }
            return new Selection(buildType, flavors);
        }

        public String encode() {
            StringBuilder b = new StringBuilder(buildType == null ? "" : buildType);
            for (Map.Entry<String, String> e : flavors.entrySet()) {
                if (b.length() > 0) b.append('|');
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            return b.toString();
        }
    }

    /** The variant-applied build plus the resolved secret values (package-spec side channel). */
    public record Applied(JkBuild build, Map<String, String> secrets) {}

    /** As {@link #apply(JkBuild, Path, Selection, Map)} with defaults — non-build consumers. */
    public static JkBuild applyDefaults(JkBuild build, Path moduleDir) {
        return apply(build, moduleDir, Selection.DEFAULTS, Map.of()).build();
    }

    public static Applied apply(JkBuild build, Path moduleDir, Selection selection, Map<String, String> clientEnv) {
        JkBuild out = build;
        Map<String, String> secrets = new LinkedHashMap<>();
        for (PluginManifest manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            if (manifest.subTables().isEmpty()) continue;
            out = out.withPluginConfig(effective(manifest, config, selection, clientEnv, secrets));
        }
        return new Applied(out, secrets);
    }

    private static PluginConfig effective(
            PluginManifest manifest,
            PluginConfig config,
            Selection selection,
            Map<String, String> clientEnv,
            Map<String, String> secrets) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : config.values().entrySet()) {
            if (manifest.subTables().containsKey(e.getKey())) continue; // groups never ride flat
            values.put(e.getKey(), e.getValue());
        }

        // Flavor overlays first (dimension declaration order), then the build type — AGP precedence.
        for (PluginManifest.SubTable axis : manifest.variantAxes()) {
            if (!axis.dimensioned()) continue;
            Map<String, Map<String, Map<String, Object>>> dims = config.dimensionedGroup(axis.table());
            for (Map.Entry<String, Map<String, Map<String, Object>>> dim : dims.entrySet()) {
                String chosen = selection.flavors().get(dim.getKey());
                if (chosen == null) {
                    throw new JkBuildParseException("[" + manifest.table() + "." + axis.table() + "."
                            + dim.getKey() + "] declares flavors " + dim.getValue().keySet()
                            + " — select one with --flavor " + dim.getKey() + "=<name>");
                }
                Map<String, Object> overlay = dim.getValue().get(chosen);
                if (overlay == null) {
                    throw new JkBuildParseException("no flavor `" + chosen + "` in [" + manifest.table() + "."
                            + axis.table() + "." + dim.getKey() + "] (declared: " + dim.getValue().keySet() + ")");
                }
                values.putAll(overlay);
                values.put("flavor." + dim.getKey(), chosen);
                values.put("flavor", chosen); // single-dimension convenience
            }
        }
        for (PluginManifest.SubTable axis : manifest.variantAxes()) {
            if (axis.dimensioned()) continue;
            String chosen = selection.buildType() != null ? selection.buildType() : axis.defaultName();
            if (chosen == null) continue;
            Map<String, Map<String, Object>> entries = config.group(axis.table());
            Map<String, Object> overlay = entries.get(chosen);
            if (overlay == null && !axis.builtIn().contains(chosen)) {
                throw new JkBuildParseException("no " + axis.variantAxis() + " `" + chosen + "` in ["
                        + manifest.table() + "." + axis.table() + "] (declared: " + entries.keySet()
                        + ", built-in: " + axis.builtIn() + ")");
            }
            if (overlay != null) values.putAll(overlay);
            values.put(axis.variantAxis(), chosen);
        }

        // Named-group references (signing = "release" → [android.signing.release] flattens to
        // signing.<key>, secrets diverted to the side channel).
        for (PluginManifest.SubTable group : manifest.subTables().values()) {
            if (group.variantAxis() != null) continue;
            Object ref = values.get(group.table());
            if (!(ref instanceof String name) || name.isBlank()) continue;
            Map<String, Object> entry = config.group(group.table()).get(name);
            if (entry == null) {
                throw new JkBuildParseException("[" + manifest.table() + "] references " + group.table() + " = \""
                        + name + "\" but declares no [" + manifest.table() + "." + group.table() + "." + name + "]");
            }
            Map<String, PluginManifest.SchemaKey> subSchema = manifest.subSchemas().get(group.schema());
            for (Map.Entry<String, Object> e : entry.entrySet()) {
                Object value = e.getValue();
                if (value instanceof String s) {
                    value = resolveEnv(
                            s, clientEnv, manifest.table() + "." + group.table() + "." + name + "." + e.getKey());
                }
                PluginManifest.SchemaKey schemaKey = subSchema.get(e.getKey());
                String flatKey = group.table() + "." + e.getKey();
                if (schemaKey != null && schemaKey.secret()) {
                    secrets.put(flatKey, String.valueOf(value));
                } else {
                    values.put(flatKey, value);
                }
            }
        }
        return new PluginConfig(config.id(), values);
    }

    /**
     * {@code env:NAME} indirection: the client-shipped environment wins (it is the user's shell —
     * the engine's own env belongs to whichever invocation first spawned it), the engine env is
     * the fallback (in-process and test paths). An unresolvable reference fails loudly — a signing
     * config must never silently sign with an empty credential.
     */
    private static String resolveEnv(String raw, Map<String, String> clientEnv, String where) {
        if (!raw.startsWith("env:")) return raw;
        String name = raw.substring("env:".length()).trim();
        String v = clientEnv.get(name);
        if (v == null) v = System.getenv(name);
        if (v == null) {
            throw new JkBuildParseException(
                    "[" + where + "] references env:" + name + " but " + name + " is not set");
        }
        return v;
    }

    /** Every {@code env:NAME} name referenced anywhere in {@code build}'s plugin configs. */
    public static List<String> envRefs(JkBuild build) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (PluginConfig config : build.pluginConfigs().values()) {
            collectEnvRefs(config.values(), names);
        }
        return List.copyOf(names);
    }

    @SuppressWarnings("unchecked")
    private static void collectEnvRefs(Map<String, Object> values, java.util.Set<String> names) {
        for (Object v : values.values()) {
            if (v instanceof String s && s.startsWith("env:")) {
                names.add(s.substring("env:".length()).trim());
            } else if (v instanceof Map<?, ?> m) {
                collectEnvRefs((Map<String, Object>) m, names);
            }
        }
    }
}
