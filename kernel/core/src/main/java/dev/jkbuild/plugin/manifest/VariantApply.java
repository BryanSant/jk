// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.Variants;
import dev.jkbuild.model.Variants.Selection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Variant resolution (variants-plan §1): variants are <em>computed goal parameterizations</em>,
 * not configured objects. A build selects one value per declared {@code [variants]} dimension
 * ({@code jk build --release} → build-type {@code release}, {@code --variant contentType=demo} →
 * a custom dimension); {@link #apply} folds the selected overlays into the parsed manifest so
 * every downstream consumer — describe keys, step action keys, contribution predicates,
 * {@code [[packaging.variant]]} resolution, worker specs — sees one flat effective build and
 * needs no variant awareness at all.
 *
 * <p>Folded per selected value: {@code extra-src} appends to {@code [build] extra-src},
 * dependency overlays append to their scopes (the lock already resolved their union — see
 * {@link Variants#unionDependencies}), and plugin sub-tables overlay that plugin's config.
 * Precedence: custom dimensions in declaration order, then {@code build-type} last. The selected
 * names are injected as {@code build-type} and {@code variant.<dim>} config keys on every plugin
 * config, so manifests condition on them
 * ({@code [[packaging.variant]] when = { config = "build-type", equals = "release" }}).
 *
 * <p>A declared dimension without a default <b>must</b> be selected — building without a choice
 * fails loudly, never silently builds "no variant". A selection naming an undeclared dimension is
 * ignored (selections are workspace-wide; modules apply only the dimensions they declare).
 *
 * <p><b>Signing groups + secrets:</b> a plugin sub-table group referenced by the effective
 * {@code signing} key flattens to {@code signing.<key>} entries. Values use {@code env:NAME}
 * indirection — resolved here against the client-supplied environment first (the thin-client
 * posture publish credentials established: the client resolves what its shell sees and ships it
 * on the request), then the engine's own environment. Keys marked {@code secret = true} in the
 * sub-schema never enter the config (so no spec, describe payload, or token renders them):
 * they ride the {@link Applied#secrets} side channel into the package spec only, and action keys
 * carry only their digest.
 */
public final class VariantApply {

    private VariantApply() {}

    /** The variant-applied build plus the resolved secret values (package-spec side channel). */
    public record Applied(JkBuild build, Map<String, String> secrets) {}

    /** As {@link #apply(JkBuild, Path, Selection, Map)} with defaults — non-build consumers. */
    public static JkBuild applyDefaults(JkBuild build, Path moduleDir) {
        return apply(build, moduleDir, Selection.DEFAULTS, Map.of()).build();
    }

    public static Applied apply(JkBuild build, Path moduleDir, Selection selection, Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, false);
    }

    /**
     * As {@link #apply} but an unselected mandatory dimension is SKIPPED, not an error — for
     * non-build consumers (exec plans, plugin verbs) that must answer before/without a full
     * selection ({@code jk android licenses} runs pre-selection by design). Builds never use
     * this: silently building "no variant" is exactly what the mandatory check prevents.
     */
    public static Applied applyLenient(JkBuild build, Path moduleDir, Selection selection, Map<String, String> clientEnv) {
        return apply(build, moduleDir, selection, clientEnv, true);
    }

    private static Applied apply(
            JkBuild build, Path moduleDir, Selection selection, Map<String, String> clientEnv, boolean lenient) {
        Variants decl = build.variants();

        // Resolve the selection: (dimension, value name, overlay) per declared dimension — custom
        // dimensions in declaration order, build-type last (AGP's precedence, now core's).
        List<Chosen> chosen = new ArrayList<>();
        for (Variants.Dimension dimension : decl.custom()) {
            Chosen c = chooseCustom(dimension, selection, lenient);
            if (c != null) chosen.add(c);
        }
        chosen.add(chooseBuildType(decl, selection));

        // Core folds: extra-src and per-scope dependency overlays.
        JkBuild out = build;
        List<String> extraSrc = new ArrayList<>();
        EnumMap<Scope, List<Dependency>> extraDeps = new EnumMap<>(Scope.class);
        for (Chosen c : chosen) {
            extraSrc.addAll(c.overlay().extraSrc());
            c.overlay().dependencies().forEach((scope, deps) ->
                    extraDeps.computeIfAbsent(scope, s -> new ArrayList<>()).addAll(deps));
        }
        if (!extraSrc.isEmpty()) out = out.withBuild(out.build().withExtraSrc(extraSrc));
        if (!extraDeps.isEmpty()) {
            Map<Scope, List<Dependency>> merged = new EnumMap<>(Scope.class);
            out.dependencies().byScope().forEach((scope, deps) -> merged.put(scope, new ArrayList<>(deps)));
            extraDeps.forEach((scope, deps) ->
                    merged.computeIfAbsent(scope, s -> new ArrayList<>()).addAll(deps));
            out = out.withDependencies(new JkBuild.Dependencies(merged));
        }

        // Plugin folds: overlay keys onto each plugin config, inject the selected names, then
        // flatten named group references (signing) with env: resolution / secret diversion.
        Map<String, String> secrets = new LinkedHashMap<>();
        for (PluginManifest manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = out.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            out = out.withPluginConfig(effective(manifest, config, chosen, clientEnv, secrets));
        }
        return new Applied(out, secrets);
    }

    /** One resolved dimension: its name, the selected value name, and that value's overlay. */
    private record Chosen(String dimension, String value, Variants.Value overlay) {}

    private static Chosen chooseCustom(Variants.Dimension dimension, Selection selection, boolean lenient) {
        String name = selection.values().get(dimension.name());
        if (name == null) name = selection.values().get("*"); // bare --variant <value>
        if (name == null) name = dimension.defaultValue();
        if (name == null) {
            if (lenient) return null;
            throw new JkBuildParseException("[variants." + dimension.name() + "] declares values "
                    + dimension.values().keySet() + " — select one with --variant "
                    + dimension.name() + "=<value>");
        }
        Variants.Value overlay = dimension.values().get(name);
        if (overlay == null) {
            throw new JkBuildParseException("no value `" + name + "` in [variants." + dimension.name()
                    + "] (declared: " + dimension.values().keySet() + ")");
        }
        return new Chosen(dimension.name(), name, overlay);
    }

    private static Chosen chooseBuildType(Variants decl, Selection selection) {
        Variants.Dimension dimension = decl.dimension(Variants.BUILD_TYPE)
                .orElseGet(() -> new Variants.Dimension(Variants.BUILD_TYPE, null, Map.of()));
        String name = selection.buildType() != null ? selection.buildType() : dimension.defaultValue();
        if (name == null) name = "debug";
        Variants.Value overlay = dimension.values().get(name);
        if (overlay == null) {
            if (!Variants.BUILT_IN_BUILD_TYPES.contains(name)) {
                throw new JkBuildParseException("no build type `" + name + "` in [variants.build-type]"
                        + " (declared: " + dimension.values().keySet()
                        + ", built-in: " + Variants.BUILT_IN_BUILD_TYPES + ")");
            }
            overlay = Variants.Value.EMPTY;
        }
        return new Chosen(Variants.BUILD_TYPE, name, overlay);
    }

    private static PluginConfig effective(
            PluginManifest manifest,
            PluginConfig config,
            List<Chosen> chosen,
            Map<String, String> clientEnv,
            Map<String, String> secrets) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : config.values().entrySet()) {
            // Group DEFINITIONS never ride flat; a same-named string REFERENCE does.
            if (manifest.subTables().containsKey(e.getKey()) && e.getValue() instanceof Map) continue;
            values.put(e.getKey(), e.getValue());
        }

        for (Chosen c : chosen) {
            Map<String, Object> overlay = c.overlay().pluginOverlays().get(manifest.table());
            if (overlay != null) values.putAll(overlay);
            values.put(
                    Variants.BUILD_TYPE.equals(c.dimension()) ? Variants.BUILD_TYPE : "variant." + c.dimension(),
                    c.value());
        }

        // Named-group references (signing = "release" → [android.signing.release] flattens to
        // signing.<key>, secrets diverted to the side channel).
        for (PluginManifest.SubTable group : manifest.subTables().values()) {
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
