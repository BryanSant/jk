// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.config.JkBuildParseException;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates installed plugins' declarative contributions against one project (plan §3.1, P2):
 * platform dependencies at parse time, compiler args and Kotlin compiler plugins at compile
 * time. Pure data in, pure data out — no plugin code runs, which is the point of the
 * declarative layer. Engine-side reachability only (the manifests live behind tomlj).
 *
 * <p>A plugin contributes only when its owned table is present in the project ({@code
 * pluginConfigs} carries a config for it); each entry's optional {@link PluginManifest.Condition}
 * gates it further. {@code classpath-has} evaluates against the resolved lock's
 * {@code group:artifact} names — the exact semantics the hard-coded jakarta.persistence check
 * had before this layer existed.
 */
public final class PluginContributions {

    private PluginContributions() {}

    /** A parse-time platform (BOM) injection: the module plus its exact version pin. */
    public record PlatformDep(String module, String version) {}

    /** A compile-time Kotlin compiler plugin: BTA id, jar coordinate pieces, plugin options. */
    public record KotlinPluginUse(String id, String group, String artifact, String version, List<String> options) {}

    /**
     * The platform dependencies every present plugin contributes — evaluated at parse time
     * (before resolution; the manifest loader already rejected classpath-has conditions here).
     */
    public static List<PlatformDep> platformDependencies(
            JkBuild.Project project, boolean nativeDeclared, java.util.Map<String, PluginConfig> pluginConfigs) {
        List<PlatformDep> out = new ArrayList<>();
        for (PluginManifest manifest : PluginTableRegistry.manifests()) {
            PluginConfig config = pluginConfigs.get(manifest.id());
            if (config == null) continue;
            for (PluginManifest.PlatformDependency dep :
                    manifest.contributions().platformDependencies()) {
                if (!holds(dep.when(), config, project, nativeDeclared, null, manifest.id())) continue;
                String coordinate = Interpolation.resolve(dep.coordinate(), config, project, null);
                out.add(splitCoordinate(coordinate, manifest.id()));
            }
        }
        return out;
    }

    /** The javac args every present plugin contributes, conditions evaluated against {@code classpathModules}. */
    public static List<String> javacArgs(JkBuild build, Set<String> classpathModules) {
        return compilerArgs(build, classpathModules, PluginManifest.CompilerArgs::javac);
    }

    /** The kotlinc args every present plugin contributes. */
    public static List<String> kotlinArgs(JkBuild build, Set<String> classpathModules) {
        return compilerArgs(build, classpathModules, PluginManifest.CompilerArgs::kotlin);
    }

    /**
     * The Kotlin compiler plugins every present plugin contributes. {@code kotlinVersion} feeds
     * {@code ${kotlin.version}} so plugin jars stay lockstep with the compiler actually used.
     */
    public static List<KotlinPluginUse> kotlinPlugins(JkBuild build, String kotlinVersion, Set<String> classpathModules) {
        List<KotlinPluginUse> out = new ArrayList<>();
        for (PluginManifest manifest : PluginTableRegistry.manifests()) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginManifest.KotlinPlugin plugin : manifest.contributions().kotlinPlugins()) {
                if (!holds(
                        plugin.when(),
                        config,
                        build.project(),
                        build.nativeConfig().isPresent(),
                        classpathModules,
                        manifest.id())) {
                    continue;
                }
                String coordinate = Interpolation.resolve(plugin.coordinate(), config, build.project(), kotlinVersion);
                PlatformDep c = splitCoordinate(coordinate, manifest.id());
                int colon = c.module().indexOf(':');
                List<String> options = new ArrayList<>(plugin.options().size());
                for (String opt : plugin.options()) {
                    options.add(Interpolation.resolve(opt, config, build.project(), kotlinVersion));
                }
                out.add(new KotlinPluginUse(
                        plugin.id(), c.module().substring(0, colon), c.module().substring(colon + 1), c.version(),
                        options));
            }
        }
        return out;
    }

    private static List<String> compilerArgs(
            JkBuild build,
            Set<String> classpathModules,
            java.util.function.Function<PluginManifest.CompilerArgs, List<String>> lane) {
        List<String> out = new ArrayList<>();
        for (PluginManifest manifest : PluginTableRegistry.manifests()) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginManifest.CompilerArgs args : manifest.contributions().compilerArgs()) {
                if (!holds(
                        args.when(),
                        config,
                        build.project(),
                        build.nativeConfig().isPresent(),
                        classpathModules,
                        manifest.id())) {
                    continue;
                }
                for (String arg : lane.apply(args)) {
                    out.add(Interpolation.resolve(arg, config, build.project(), null));
                }
            }
        }
        return out;
    }

    /** One resolved packager-dependency: fetch {@code module:version}, hand it over as {@code artifact}. */
    public record PackagerDep(String artifact, String module, String version) {}

    /**
     * The active plugins' {@code [[contribute.packager-dependency]]} entries with conditions
     * evaluated and coordinates interpolated — the engine fetches these (never into the project's
     * dependency graph) and hands them to the packager worker by name.
     */
    public static java.util.List<PackagerDep> packagerDependencies(JkBuild build) {
        java.util.List<PackagerDep> out = new java.util.ArrayList<>();
        for (PluginManifest manifest : PluginTableRegistry.manifests()) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginManifest.PackagerDependency pd :
                    manifest.contributions().packagerDependencies()) {
                if (!holds(pd.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
                    continue;
                }
                String coordinate = Interpolation.resolve(pd.coordinate(), config, build.project(), null);
                PlatformDep split = splitCoordinate(coordinate, manifest.id());
                out.add(new PackagerDep(pd.artifact(), split.module(), split.version()));
            }
        }
        return out;
    }

    /** One predicate, or unconditional when {@code when} is null. */
    private static boolean holds(
            PluginManifest.Condition when,
            PluginConfig config,
            JkBuild.Project project,
            boolean nativeDeclared,
            Set<String> classpathModules,
            String pluginId) {
        if (when == null) return true;
        return switch (when) {
            case PluginManifest.Condition.ClasspathHas c -> {
                if (classpathModules == null) {
                    throw new JkBuildParseException("[" + pluginId + "] contribution uses classpath-has on a"
                            + " path evaluated before resolution");
                }
                yield classpathModules.contains(c.module());
            }
            case PluginManifest.Condition.ConfigEquals c ->
                c.equals().equals(String.valueOf(config.values().get(c.key())));
            case PluginManifest.Condition.NativeDeclared ignored -> nativeDeclared;
            case PluginManifest.Condition.KotlinProject ignored -> project.isKotlin();
        };
    }

    private static PlatformDep splitCoordinate(String coordinate, String pluginId) {
        String[] parts = coordinate.split(":");
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new JkBuildParseException("[" + pluginId + "] contribution coordinate must be"
                    + " \"group:artifact:version\" — got: " + coordinate);
        }
        return new PlatformDep(parts[0] + ":" + parts[1], parts[2]);
    }
}
