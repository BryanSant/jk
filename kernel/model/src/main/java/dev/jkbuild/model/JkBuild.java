// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parsed contents of a project's {@code jk.toml}. Each new top-level block (project,
 * dependencies, repositories, profiles, features, workspace, ...) shows up as a field as its
 * subsystem comes online.
 */
public record JkBuild(
        Project project,
        Dependencies dependencies,
        List<RepositorySpec> repositories,
        Profiles profiles,
        Features features,
        Workspace workspace,
        Map<String, String> manifest,
        List<PluginDeclaration> plugins,
        Optional<Application> application,
        Optional<NativeConfig> nativeConfig,
        Map<String, PluginConfig> pluginConfigs,
        Build build,
        FormatConfig format) {

    public JkBuild {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(features, "features");
        repositories = List.copyOf(repositories);
        // Custom jar-manifest attributes (the [manifest] table). Insertion
        // order preserved for faithful round-tripping. Main-Class is NOT here —
        // it derives from [application].main.
        manifest = manifest == null || manifest.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        application = application == null ? Optional.empty() : application;
        nativeConfig = nativeConfig == null ? Optional.empty() : nativeConfig;
        // Plugin-owned tables ([spring-boot], …), keyed by plugin id — schema-validated by the
        // parser against each installed plugin's jk-plugin.toml (build-plugins plan P1).
        pluginConfigs = pluginConfigs == null || pluginConfigs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(pluginConfigs));
        build = build == null ? Build.EMPTY : build;
        format = format == null ? FormatConfig.EMPTY : format;
    }

    /**
     * Project + deps only — no repos, profiles, features, or workspace. A clear, unambiguous
     * shortcut; anything richer uses {@link #builder(Project)}.
     */
    public JkBuild(Project project, Dependencies dependencies) {
        this(
                project, dependencies, List.of(), Profiles.empty(), Features.empty(), null, null, List.of(), null,
                null, null, null, null);
    }

    /**
     * Project + deps + repos, default profiles / features / no workspace. A clear, unambiguous
     * shortcut; anything richer uses {@link #builder(Project)}.
     */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories) {
        this(
                project, dependencies, repositories, Profiles.empty(), Features.empty(), null, null, List.of(), null,
                null, null, null, null);
    }

    /** {@code [application].main}, or {@code null} when {@code [application]} is absent or unset. */
    public String mainClass() {
        return application.map(Application::main).orElse(null);
    }

    /** True when a {@code main} class is set — the {@code [application]} table declares one. */
    public boolean isRunnable() {
        return mainClass() != null;
    }

    /**
     * True when this project is an <em>application</em> — {@code jk install} additionally performs
     * a {@code make install} (launcher / native binary under {@code ~/.jk}). Driven purely by
     * whether {@code [application]} is declared at all.
     */
    public boolean isApplication() {
        return application.isPresent();
    }

    /** The schema-validated config for the plugin-owned table {@code id}, when declared. */
    public Optional<PluginConfig> pluginConfig(String id) {
        return Optional.ofNullable(pluginConfigs.get(id));
    }

    /** This build with one plugin's config replaced — the variant-overlay substitution point. */
    public JkBuild withPluginConfig(PluginConfig config) {
        Map<String, PluginConfig> next = new LinkedHashMap<>(pluginConfigs);
        next.put(config.id(), config);
        return new JkBuild(
                project, dependencies, repositories, profiles, features, workspace, manifest, plugins,
                application, nativeConfig, next, build, format);
    }

    /**
     * True when {@code [spring-boot]} is declared — Boot packaging, BOM import, and
     * {@code -parameters} compilation apply. Driven purely by the table's presence. A convenience
     * over {@link #pluginConfig}; P3 replaces call sites with capability checks fed by plugin
     * contributions.
     */
    public boolean isSpringBoot() {
        return pluginConfigs.containsKey(SPRING_BOOT_ID);
    }

    /** The built-in spring-boot plugin's id / table name. */
    public static final String SPRING_BOOT_ID = "spring-boot";

    /** {@code [application].shadow-jar} — bundle an all-in-one (shadow / fat) jar. */
    public boolean shadowJar() {
        return application.map(Application::shadowJar).orElse(false);
    }

    /** {@code [native].graal} — the GraalVM spec {@code jk native} uses, or {@code null} if unset. */
    public String graal() {
        return nativeConfig.map(NativeConfig::graal).orElse(null);
    }

    /**
     * Native-image participation, derived from whether {@code [native]} is declared and its
     * {@code always} flag: absent → {@link NativeMode#DISABLED}, present with {@code always =
     * false} (default) → {@link NativeMode#SUPPORTED}, present with {@code always = true} →
     * {@link NativeMode#ALWAYS}.
     */
    public NativeMode nativeMode() {
        return nativeConfig
                .map(nc -> nc.always() ? NativeMode.ALWAYS : NativeMode.SUPPORTED)
                .orElse(NativeMode.DISABLED);
    }

    /** Backward-compat: true when native mode is not DISABLED. */
    public boolean nativeImage() {
        return nativeMode() != NativeMode.DISABLED;
    }

    public static JkBuild of(Project project) {
        return builder(project).build();
    }

    /**
     * A fluent builder for {@code JkBuild} — the readable replacement for the telescoping
     * constructors. Only {@code project} is required; every other field defaults to the same value
     * the canonical constructor normalizes to (empty collections / {@code EMPTY} blocks / no
     * workspace). Chain the setters you need and call {@link Builder#build()}.
     */
    public static Builder builder(Project project) {
        return new Builder(project);
    }

    /** Mutable accumulator for {@link JkBuild}; see {@link #builder}. */
    public static final class Builder {
        private final Project project;
        private Dependencies dependencies = Dependencies.empty();
        private List<RepositorySpec> repositories = List.of();
        private Profiles profiles = Profiles.empty();
        private Features features = Features.empty();
        private Workspace workspace;
        private Map<String, String> manifest = Map.of();
        private List<PluginDeclaration> plugins = List.of();
        private Optional<Application> application = Optional.empty();
        private Optional<NativeConfig> nativeConfig = Optional.empty();
        private final Map<String, PluginConfig> pluginConfigs = new LinkedHashMap<>();
        private Build build;
        private FormatConfig format;

        private Builder(Project project) {
            this.project = project;
        }

        public Builder dependencies(Dependencies dependencies) { this.dependencies = dependencies; return this; }

        public Builder repositories(List<RepositorySpec> repositories) { this.repositories = repositories; return this; }

        public Builder profiles(Profiles profiles) { this.profiles = profiles; return this; }

        public Builder features(Features features) { this.features = features; return this; }

        public Builder workspace(Workspace workspace) { this.workspace = workspace; return this; }

        public Builder manifest(Map<String, String> manifest) { this.manifest = manifest; return this; }

        public Builder plugins(List<PluginDeclaration> plugins) { this.plugins = plugins; return this; }

        public Builder application(Application application) { this.application = Optional.ofNullable(application); return this; }

        public Builder nativeConfig(NativeConfig nativeConfig) { this.nativeConfig = Optional.ofNullable(nativeConfig); return this; }

        public Builder pluginConfig(PluginConfig config) {
            if (config != null) this.pluginConfigs.put(config.id(), config);
            return this;
        }

        public Builder build(Build build) { this.build = build; return this; }

        public Builder format(FormatConfig format) { this.format = format; return this; }

        public JkBuild build() {
            return new JkBuild(
                    project, dependencies, repositories, profiles, features, workspace,
                    manifest, plugins, application, nativeConfig, pluginConfigs, build, format);
        }
    }

    /** Return a copy with the given custom jar-manifest attributes. */
    public JkBuild withManifest(Map<String, String> manifest) {
        return new JkBuild(
                project,
                dependencies,
                repositories,
                profiles,
                features,
                workspace,
                manifest,
                plugins,
                application,
                nativeConfig,
                pluginConfigs,
                build,
                format);
    }

    /** True iff this is a workspace root (has a non-empty {@code workspace} block). */
    public boolean isWorkspaceRoot() {
        return workspace != null && !workspace.isEmpty();
    }

    public Optional<Workspace> workspaceOpt() {
        return Optional.ofNullable(workspace);
    }

    /**
     * The {@code [project]} block of a {@code jk.toml}.
     *
     * <p>Fields:
     *
     * <ul>
     *   <li>{@code jdk} — JDK feature release (major) the project builds against. Resolved to a
     *       concrete install identifier (e.g. {@code temurin-25.0.3}) in {@code jk.lock}.
     *   <li>{@code java} — Java compiler language/bytecode level (major). Non-zero only for Java
     *       projects; mutually exclusive with {@code kotlin}.
     *   <li>{@code kotlin} — Kotlin compiler version selector (floating by default, {@code
     *       =}-pinnable, range-capable — like a dependency version). Resolved to a concrete version
     *       in {@code jk.lock}. Non-{@code null} only for Kotlin projects; mutually exclusive with
     *       {@code java}.
     *   <li>{@code description} — free-form human-readable description. Surfaces as {@code
     *       <description>} in {@code jk publish} POMs and {@code jk export pom.xml}; {@code null}
     *       when omitted.
     *   <li>{@code m2install} — when true, {@code jk install} additionally copies the jar+pom into
     *       {@code ~/.m2/repository} for Maven/Gradle interop; also enables mirroring this
     *       project's resolved dependencies into {@code ~/.m2} as they're fetched. Default false —
     *       {@code ~/.jk/cache} is the primary artifact store either way.
     * </ul>
     *
     * <p>{@code main}, {@code shadow-jar} (application packaging) and {@code graal}, {@code always}
     * (native-image participation) live in the separate {@link Application} / {@link NativeConfig}
     * top-level blocks — see {@link JkBuild#application} / {@link JkBuild#nativeConfig}. Whether a
     * project is an application, or builds a native image at all, is driven purely by whether
     * {@code [application]} / {@code [native]} is declared, not by a boolean flag on {@code
     * [project]}.
     *
     * <p>Java and Kotlin are independent opt-ins — a project may compile both (sources under {@code
     * src/main/java} and {@code src/main/kotlin}). Given a required {@code jdk}, {@code java = <int>}
     * opts into Java and {@code kotlin = "<ver>"} opts into Kotlin (either or both). When neither is
     * declared, the languages are inferred from the source tree, defaulting to Java; {@link
     * #isKotlin()} is the cheap "kotlin declared" predicate. The build-time resolution lives in
     * {@code CompileSupport.resolveLanguages}.
     */

    /**
     * Source layout strategy for a project.
     *
     * <ul>
     *   <li>{@code simple} — sources in {@code ./src}, tests in {@code ./test}, no package
     *       declaration. Written as {@code project.layout = "simple"}.
     *   <li>{@code traditional} — Maven layout: {@code src/main/java}, {@code src/test/java}. Written
     *       as {@code project.layout = "traditional"}.
     *   <li>{@code auto} — inferred from the directory tree. Default when {@code project.layout} is
     *       absent from {@code jk.toml}.
     * </ul>
     */
    public enum Layout {
        SIMPLE,
        TRADITIONAL,
        AUTO;

        /** Parse from a jk.toml string value; null or blank → AUTO. */
        public static Layout parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            return switch (raw.trim().toLowerCase()) {
                case "simple" -> SIMPLE;
                case "traditional" -> TRADITIONAL;
                case "auto" -> AUTO;
                default ->
                    throw new IllegalArgumentException(
                            "project.layout must be \"simple\", \"traditional\", or \"auto\" (got: " + raw + ")");
            };
        }

        /** The string written to jk.toml, or null for AUTO (omitted). */
        public String tomlValue() {
            return switch (this) {
                case SIMPLE -> "simple";
                case TRADITIONAL -> "traditional";
                case AUTO -> null;
            };
        }
    }

    /** Derived from whether {@code [native]} is declared and its {@code always} flag — see {@link JkBuild#nativeMode}. */
    public enum NativeMode {
        /** {@code [native]} absent — no native-image participation at all. */
        DISABLED,
        /**
         * {@code [native]} declared, {@code always} absent/false — eligible for {@code jk native}
         * workspace cascade but NOT auto-built by {@code jk build} or other build commands.
         */
        SUPPORTED,
        /**
         * {@code [native] always = true} — native-image runs on {@code jk build}, {@code jk
         * install}, and {@code jk native}.
         */
        ALWAYS;

        public boolean isEnabled() {
            return this != DISABLED;
        }
    }

    /**
     * Controls when a sources JAR is produced. TOML key {@code project.sources}.
     *
     * <ul>
     *   <li>absent / {@code false} → {@code DISABLED}: no sources jar ever.
     *   <li>{@code true} → {@code PUBLISH}: sources jar is assembled and uploaded during {@code jk
     *       publish} but NOT written to {@code target/} by {@code jk build}.
     *   <li>{@code "always"} → {@code ALWAYS}: sources jar is built as a {@code package-sources}
     *       tail phase during {@code jk build} (written to {@code target/}) AND uploaded during
     *       {@code jk publish}.
     * </ul>
     */
    public enum SourcesMode {
        /** Sources jar never produced. */
        DISABLED,
        /** Sources jar assembled during {@code jk publish} only. */
        PUBLISH,
        /** Sources jar built by {@code jk build} and sent by {@code jk publish}. */
        ALWAYS;

        public boolean publishSources() {
            return this != DISABLED;
        }
    }

    public record Project(
            String group,
            String name,
            String version,
            String jdk,
            int java,
            VersionSelector kotlin,
            SourcesMode sourcesMode,
            String description,
            boolean m2install,
            Layout layout) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (name.isBlank()) throw new IllegalArgumentException("project.name must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (java < 0) {
                throw new IllegalArgumentException("project.java must be non-negative");
            }
            if (jdk != null && jdk.isBlank()) jdk = null;
            if (sourcesMode == null) sourcesMode = SourcesMode.DISABLED;
            if (layout == null) layout = Layout.AUTO;
            if (description != null && description.isBlank()) description = null;
        }

        /** Library project — bare-major {@code jdk} (0 → unset). */
        public Project(String group, String name, String version, int jdk) {
            this(group, name, version, majorSpec(jdk), jdk, null, null, null, false, Layout.AUTO);
        }

        /** A bare-major int as a jdk spec string ({@code 25} → {@code "25"}); 0/negative → unset. */
        private static String majorSpec(int major) {
            return major > 0 ? Integer.toString(major) : null;
        }

        /**
         * A fluent builder for {@code Project} — the readable replacement for the telescoping
         * constructors. Only {@code group}/{@code name}/{@code version} are required; every other
         * field defaults to the same value the canonical constructor normalizes to (unset/DISABLED/
         * AUTO). Chain the setters you need and call {@link Builder#build()}.
         */
        public static Builder builder(String group, String name, String version) {
            return new Builder(group, name, version);
        }

        /** Mutable accumulator for {@link Project}; see {@link #builder}. */
        public static final class Builder {
            private final String group;
            private final String name;
            private final String version;
            private String jdk;
            private int java;
            private VersionSelector kotlin;
            private SourcesMode sourcesMode = SourcesMode.DISABLED;
            private String description;
            private boolean m2install;
            private Layout layout = Layout.AUTO;

            private Builder(String group, String name, String version) {
                this.group = group;
                this.name = name;
                this.version = version;
            }

            /** Toolchain JDK spec, e.g. {@code "temurin-25"} or {@code "25"}. */
            public Builder jdk(String jdk) { this.jdk = jdk; return this; }

            /** Toolchain JDK from a bare major ({@code 25} → {@code "25"}; 0/negative → unset). */
            public Builder jdkMajor(int major) { this.jdk = majorSpec(major); return this; }

            /** {@code --release} target for javac (0 → falls back to the jdk major). */
            public Builder java(int java) { this.java = java; return this; }

            public Builder kotlin(VersionSelector kotlin) { this.kotlin = kotlin; return this; }

            public Builder sourcesMode(SourcesMode sourcesMode) { this.sourcesMode = sourcesMode; return this; }

            public Builder description(String description) { this.description = description; return this; }

            public Builder m2install(boolean m2install) { this.m2install = m2install; return this; }

            public Builder layout(Layout layout) { this.layout = layout; return this; }

            public Project build() {
                return new Project(group, name, version, jdk, java, kotlin, sourcesMode, description, m2install, layout);
            }
        }

        /** True when this is a Kotlin project (i.e. a {@code kotlin} version is set). */
        public boolean isKotlin() {
            return kotlin != null;
        }

        /**
         * The {@code java} compiler release to target. Falls back to the {@code jdk} major (implicit
         * java=jdk).
         */
        public int javaRelease() {
            return java > 0 ? java : jdkMajor();
        }

        /**
         * Major version implied by the {@code jdk} spec — {@code "temurin-25"}, {@code "25"}, and
         * {@code "21.0.3"} all yield {@code 25}/{@code 21}; {@code 0} when unset or no numeric token is
         * present.
         */
        public int jdkMajor() {
            return majorOf(jdk);
        }

        /**
         * Extract the feature/major version from a JDK spec. Mirrors the resolver-side parser ({@code
         * JdkSelector.parseFlexible}) for the major token: splits on {@code -}/{@code _}, takes the
         * first numeric-leading token (the prefix before any {@code .}). Returns {@code 0} when there
         * is no such token. Kept here, JDK-only, because the model and the TOML parser must derive the
         * major without depending on {@code :toolchain}.
         */
        public static int majorOf(String spec) {
            if (spec == null) return 0;
            for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
                if (tok.isEmpty() || !Character.isDigit(tok.charAt(0))) continue;
                int dot = tok.indexOf('.');
                try {
                    return Integer.parseInt(dot < 0 ? tok : tok.substring(0, dot));
                } catch (NumberFormatException ignored) {
                    // not a clean integer — keep scanning later tokens
                }
            }
            return 0;
        }

        /**
         * True when the spec pins a point/patch release (a numeric token with a {@code .}, e.g. {@code
         * "25.0.3"} or {@code "temurin-25.0.3"}). jk rejects these — it keeps the patch current behind
         * the major pointer.
         */
        public static boolean hasPointRelease(String spec) {
            if (spec == null) return false;
            for (String tok : spec.toLowerCase(Locale.ROOT).split("[-_]")) {
                if (!tok.isEmpty() && Character.isDigit(tok.charAt(0)) && tok.indexOf('.') >= 0) {
                    return true;
                }
            }
            return false;
        }

        /** {@code "java"} / {@code "kotlin"} — derived from which compiler field is non-zero. */
        public String languageName() {
            return isKotlin() ? "kotlin" : "java";
        }
    }

    /**
     * The {@code [application]} block of {@code jk.toml}. Its mere presence marks the project as
     * an application — {@code jk install} additionally performs a {@code make install} (launcher /
     * native binary under {@code ~/.jk}). Absent entirely (not this record with defaulted fields —
     * see {@link JkBuild#application}) means "library, not an application."
     *
     * @param main fully qualified main class of a runnable project, or {@code null}
     * @param shadowJar bundle an all-in-one (shadow / fat) jar
     */
    public record Application(String main, boolean shadowJar) {

        public Application {
            if (main != null && main.isBlank()) main = null;
        }
    }

    /**
     * Configuration for {@code jk native} / GraalVM native-image. Declared in the {@code [native]}
     * table of {@code jk.toml}. Its mere presence marks the project as native-image-eligible — see
     * {@link JkBuild#nativeMode}. Absent entirely (not this record with defaulted fields — see
     * {@link JkBuild#nativeConfig}) means native-image participation is {@link NativeMode#DISABLED}.
     *
     * @param mainClass overrides {@code [application].main} for the native binary entry point
     * @param name output binary filename; defaults to {@code [project].name}
     * @param args extra arguments prepended to every native-image invocation (CLI {@code --} args are
     *     appended after these)
     * @param graal GraalVM spec {@code jk native} uses (same grammar as {@code [project].jdk});
     *     defaults to the {@code "native"} keyword (latest Oracle GraalVM) when {@code [native]} is
     *     declared and this key is omitted
     * @param always when true, native-image runs on {@code jk build}/{@code jk install} in addition
     *     to {@code jk native}; when false (default), native-image only runs when {@code jk native}
     *     is invoked explicitly
     */
    public record NativeConfig(String mainClass, String name, List<String> args, String graal, boolean always) {

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
            if (mainClass != null && mainClass.isBlank()) mainClass = null;
            if (name != null && name.isBlank()) name = null;
            if (graal != null && graal.isBlank()) graal = null;
        }
    }

    /**
     * The optional {@code [build]} block — build-time directives that are not dependencies and never
     * reach a classpath or {@code jk.lock}.
     *
     * <p>{@code orderAfter} lists workspace modules (by project name or {@code group:artifact}) that
     * must build before this one, <em>without</em> adding a compile/test/runtime edge — the
     * "build-order-only dependency" jk otherwise can't express.
     *
     * <p>{@code testWorkerJars} ({@code [build.test-worker-jars]}) lists workspace modules whose
     * built worker jar must be handed to this module's test JVM (as {@code -Djk.<worker>.worker.jar})
     * so tests that fork that worker locate it by path. Also implicitly {@code orderAfter} (the
     * worker must be built first).
     *
     * <p>{@code kotlinPlugins} ({@code [[kotlin-plugins]]}) are project-declared Kotlin compiler
     * plugins; see {@link KotlinPluginDecl}.
     */
    public record Build(
            List<String> orderAfter, List<String> testWorkerJars, boolean lint,
            List<KotlinPluginDecl> kotlinPlugins) {

        public static final Build EMPTY = new Build(List.of(), List.of(), true, List.of());

        public Build {
            orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
            testWorkerJars = testWorkerJars == null ? List.of() : List.copyOf(testWorkerJars);
            kotlinPlugins = kotlinPlugins == null ? List.of() : List.copyOf(kotlinPlugins);
        }

        /** Back-compat (pre-{@code [[kotlin-plugins]]}). */
        public Build(List<String> orderAfter, List<String> testWorkerJars, boolean lint) {
            this(orderAfter, testWorkerJars, lint, List.of());
        }

        /**
         * Back-compat constructor (pre-{@code lint}); defaults lint <b>on</b> so {@code jk build}
         * surfaces javac deprecation/unchecked warnings. Set {@code [build] lint = false} in jk.toml to
         * suppress that default.
         */
        public Build(List<String> orderAfter, List<String> testWorkerJars) {
            this(orderAfter, testWorkerJars, true);
        }

        /**
         * All build-order prerequisites: explicit {@code orderAfter} plus every {@code testWorkerJars}
         * module (a sibling must be built before its jar can be handed to a test). De-duplicated,
         * order preserved.
         */
        public List<String> allOrderAfter() {
            if (testWorkerJars.isEmpty()) return orderAfter;
            var all = new java.util.LinkedHashSet<>(orderAfter);
            all.addAll(testWorkerJars);
            return List.copyOf(all);
        }
    }

    /**
     * A project-declared Kotlin compiler plugin ({@code [[kotlin-plugins]]}): {@code coordinate}
     * is {@code group:artifact[:version]} — version omitted means "match the project's Kotlin
     * version" (the serialization/allopen convention); {@code id} defaults to the artifact name.
     */
    public record KotlinPluginDecl(String id, String coordinate, List<String> options) {
        public KotlinPluginDecl {
            Objects.requireNonNull(coordinate, "coordinate");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * The {@code [format]} block — how {@code jk format} formats sources.
     *
     * <p>All three are raw, optional spec strings (never validated here — the model is
     * dependency-free and knows nothing about formatter tools):
     *
     * <ul>
     *   <li>{@code style} — a cross-language preset (e.g. {@code "standard"}) applied to both
     *       languages unless a per-language key overrides it.
     *   <li>{@code java} — the Java style ({@code palantir} / {@code google} / {@code aosp}); {@code
     *       null} → inherit {@code style} / the default.
     *   <li>{@code kotlin} — the Kotlin style ({@code kotlinlang} / {@code google} / {@code meta});
     *       {@code null} → inherit {@code style} / the default.
     * </ul>
     *
     * Resolution of these strings to concrete formatters lives in the CLI / {@code jk-formatter}
     * worker, not in the model.
     */
    public record FormatConfig(String style, String java, String kotlin, Boolean optimizeImports) {

        public static final FormatConfig EMPTY = new FormatConfig(null, null, null, null);

        public FormatConfig {
            if (style != null && style.isBlank()) style = null;
            if (java != null && java.isBlank()) java = null;
            if (kotlin != null && kotlin.isBlank()) kotlin = null;
        }
    }

    public record Dependencies(Map<Scope, List<Dependency>> byScope) {

        public Dependencies {
            Objects.requireNonNull(byScope, "byScope");
            EnumMap<Scope, List<Dependency>> copy = new EnumMap<>(Scope.class);
            byScope.forEach((scope, list) -> copy.put(scope, List.copyOf(list)));
            byScope = Map.copyOf(copy);
        }

        public static Dependencies empty() {
            return new Dependencies(Map.of());
        }

        public List<Dependency> of(Scope scope) {
            return byScope.getOrDefault(scope, List.of());
        }
    }
}
