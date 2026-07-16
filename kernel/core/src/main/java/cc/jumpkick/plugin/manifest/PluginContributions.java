// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.PluginConfig;
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
 * pluginConfigs} carries a config for it); each entry's optional {@link PluginDescriptor.Condition}
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
        return platformDependencies(project, nativeDeclared, pluginConfigs, PluginTableRegistry.manifests());
    }

    /** As above against an explicit manifest set (the parser passes built-ins + resolved third-party). */
    public static List<PlatformDep> platformDependencies(
            JkBuild.Project project,
            boolean nativeDeclared,
            java.util.Map<String, PluginConfig> pluginConfigs,
            List<PluginDescriptor> manifests) {
        List<PlatformDep> out = new ArrayList<>();
        for (PluginDescriptor manifest : manifests) {
            PluginConfig config = pluginConfigs.get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.PlatformDependency dep :
                    manifest.contributions().platformDependencies()) {
                if (!holds(dep.when(), config, project, nativeDeclared, null, manifest.id())) continue;
                String coordinate = Interpolation.resolve(dep.coordinate(), config, project, null);
                out.add(splitCoordinate(coordinate, manifest.id()));
            }
        }
        return out;
    }

    /** The javac args every present plugin contributes, conditions evaluated against {@code classpathModules}. */
    public static List<String> javacArgs(JkBuild build, java.nio.file.Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::javac);
    }

    /** The kotlinc args every present plugin contributes. */
    public static List<String> kotlinArgs(JkBuild build, java.nio.file.Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::kotlin);
    }

    /**
     * The KSP processor options ({@code key=value}) every present plugin contributes — handed to
     * the KSP round as {@code -processor-options} (Hilt's superclass-validation toggle et al.).
     */
    public static List<String> kspOptions(JkBuild build, java.nio.file.Path moduleDir, Set<String> classpathModules) {
        return compilerArgs(build, moduleDir, classpathModules, PluginDescriptor.CompilerArgs::ksp);
    }

    /**
     * The Kotlin compiler plugins every present plugin contributes. {@code kotlinVersion} feeds
     * {@code ${kotlin.version}} so plugin jars stay lockstep with the compiler actually used.
     */
    public static List<KotlinPluginUse> kotlinPlugins(
            JkBuild build, java.nio.file.Path moduleDir, String kotlinVersion, Set<String> classpathModules) {
        List<KotlinPluginUse> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.KotlinPlugin plugin : manifest.contributions().kotlinPlugins()) {
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
                        plugin.id(),
                        c.module().substring(0, colon),
                        c.module().substring(colon + 1),
                        c.version(),
                        options));
            }
        }
        return out;
    }

    private static List<String> compilerArgs(
            JkBuild build,
            java.nio.file.Path moduleDir,
            Set<String> classpathModules,
            java.util.function.Function<PluginDescriptor.CompilerArgs, List<String>> lane) {
        List<String> out = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfigs().get(manifest.id());
            if (config == null) continue;
            for (PluginDescriptor.CompilerArgs args : manifest.contributions().compilerArgs()) {
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
     * One resolved step-dependency, handed to the step as {@code artifact}: a Maven coordinate
     * spec ({@code group:artifact:version[:classifier]}, {@code transitive} = the runtime closure)
     * or a provisioned SDK component ({@code sdkComponent}/{@code sdkPath}).
     */
    public record StepDep(
            String artifact, String coordinateSpec, boolean transitive, String sdkComponent, String sdkPath) {

        public StepDep(String artifact, String coordinateSpec) {
            this(artifact, coordinateSpec, false, null, null);
        }
    }

    /**
     * The active plugins' {@code [[contribute.step-dependency]]} entries with conditions evaluated
     * and coordinates interpolated — the engine fetches these into the cache (never into the
     * project's dependency graph) and hands them to the step worker by name.
     */
    public static java.util.List<StepDep> stepDependencies(JkBuild build, java.nio.file.Path moduleDir) {
        java.util.List<StepDep> out = new java.util.ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.StepDependency sd : manifest.contributions().stepDependencies()) {
                if (!holds(
                        sd.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
                    continue;
                }
                if (sd.sdkComponent() != null) {
                    String component = Interpolation.resolve(sd.sdkComponent(), config, build.project(), null);
                    out.add(new StepDep(sd.artifact(), null, false, component, sd.sdkPath()));
                    continue;
                }
                String coordinate = Interpolation.resolve(sd.coordinate(), config, build.project(), null);
                String[] parts = coordinate.split(":");
                if (parts.length < 3 || parts.length > 4) {
                    throw new JkBuildParseException("[" + manifest.id() + "] step-dependency coordinate must be"
                            + " \"group:artifact:version[:classifier]\" — got: " + coordinate);
                }
                out.add(new StepDep(sd.artifact(), coordinate, sd.transitive(), null, null));
            }
        }
        return out;
    }

    /**
     * The active plugins' {@code [[contribute.provided-classpath]]} entries with conditions
     * evaluated: names of declared step-dependency artifacts whose resolved paths join the
     * module's COMPILE classpath (PROVIDED posture — compile-only, never runtime/packaging).
     */
    public static java.util.List<String> providedClasspath(JkBuild build, java.nio.file.Path moduleDir) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.ProvidedClasspath pc :
                    manifest.contributions().providedClasspath()) {
                if (!holds(
                        pc.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
                    continue;
                }
                out.add(pc.dependency());
            }
        }
        return out;
    }

    /**
     * The {@code [contribute.resolution] jvm-environment} of the active plugin that declares one
     * ({@code "android"}), or {@code "standard-jvm"} — the GMM environment KMP runtime variants
     * resolve for. Two active plugins declaring conflicting environments is a config error.
     */
    public static String jvmEnvironment(JkBuild build, java.nio.file.Path moduleDir) {
        String selected = null;
        String selectedBy = null;
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            if (build.pluginConfig(manifest.id()).isEmpty()) continue;
            String env = manifest.contributions().jvmEnvironment();
            if (env == null) continue;
            if (selected != null && !selected.equals(env)) {
                throw new IllegalStateException("[" + selectedBy + "] and [" + manifest.id()
                        + "] declare conflicting resolution jvm-environments (" + selected + " vs " + env + ")");
            }
            selected = env;
            selectedBy = manifest.id();
        }
        return selected == null ? "standard-jvm" : selected;
    }

    /**
     * The active plugins' {@code [[contribute.packager-dependency]]} entries with conditions
     * evaluated and coordinates interpolated — the engine fetches these (never into the project's
     * dependency graph) and hands them to the packager worker by name.
     */
    public static java.util.List<PackagerDep> packagerDependencies(JkBuild build, java.nio.file.Path moduleDir) {
        java.util.List<PackagerDep> out = new java.util.ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifestsFor(moduleDir, build.plugins())) {
            PluginConfig config = build.pluginConfig(manifest.id()).orElse(null);
            if (config == null) continue;
            for (PluginDescriptor.PackagerDependency pd :
                    manifest.contributions().packagerDependencies()) {
                if (!holds(
                        pd.when(), config, build.project(), build.nativeConfig().isPresent(), null, manifest.id())) {
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
            PluginDescriptor.Condition when,
            PluginConfig config,
            JkBuild.Project project,
            boolean nativeDeclared,
            Set<String> classpathModules,
            String pluginId) {
        if (when == null) return true;
        return switch (when) {
            case PluginDescriptor.Condition.ClasspathHas c -> {
                if (classpathModules == null) {
                    throw new JkBuildParseException("[" + pluginId + "] contribution uses classpath-has on a"
                            + " path evaluated before resolution");
                }
                yield classpathModules.contains(c.module());
            }
            case PluginDescriptor.Condition.ConfigEquals c ->
                c.equals().equals(String.valueOf(config.values().get(c.key())));
            case PluginDescriptor.Condition.NativeDeclared ignored -> nativeDeclared;
            case PluginDescriptor.Condition.KotlinProject ignored -> project.isKotlin();
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
