// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parsed contents of a project's {@code jk.toml}. Each new top-level
 * block (project, dependencies, repositories, profiles, features,
 * workspace, ...) shows up as a field as its subsystem comes online.
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
        NativeConfig nativeConfig,
        Build build) {

    public JkBuild {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(features, "features");
        repositories = List.copyOf(repositories);
        // Custom jar-manifest attributes (the [manifest] table). Insertion
        // order preserved for faithful round-tripping. Main-Class is NOT here —
        // it derives from project.main.
        manifest = manifest == null || manifest.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
        plugins = plugins == null ? List.of() : List.copyOf(plugins);
        nativeConfig = nativeConfig == null ? NativeConfig.EMPTY : nativeConfig;
        build = build == null ? Build.EMPTY : build;
    }

    /** Back-compat constructor for callers that don't set the {@code [build]} block. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories,
                   Profiles profiles, Features features, Workspace workspace,
                   Map<String, String> manifest, List<PluginDeclaration> plugins,
                   NativeConfig nativeConfig) {
        this(project, dependencies, repositories, profiles, features, workspace,
                manifest, plugins, nativeConfig, null);
    }

    /** Back-compat constructor for callers that don't set nativeConfig. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories,
                   Profiles profiles, Features features, Workspace workspace,
                   Map<String, String> manifest, List<PluginDeclaration> plugins) {
        this(project, dependencies, repositories, profiles, features, workspace,
                manifest, plugins, null, null);
    }

    /** Back-compat constructor for callers that don't set plugin declarations. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories,
                   Profiles profiles, Features features, Workspace workspace,
                   Map<String, String> manifest) {
        this(project, dependencies, repositories, profiles, features, workspace, manifest, List.of());
    }

    /** Back-compat constructor for callers that don't set manifest attributes. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories,
                   Profiles profiles, Features features, Workspace workspace) {
        this(project, dependencies, repositories, profiles, features, workspace, Map.of(), List.of());
    }

    /** Project + deps only — no repos, no profiles, no features, no workspace. */
    public JkBuild(Project project, Dependencies dependencies) {
        this(project, dependencies, List.of(), Profiles.empty(), Features.empty(), null);
    }

    /** Project + deps + repos, default profiles / features / no workspace. */
    public JkBuild(Project project, Dependencies dependencies, List<RepositorySpec> repositories) {
        this(project, dependencies, repositories, Profiles.empty(), Features.empty(), null);
    }

    /** With profiles. */
    public JkBuild(Project project, Dependencies dependencies,
                   List<RepositorySpec> repositories, Profiles profiles) {
        this(project, dependencies, repositories, profiles, Features.empty(), null);
    }

    /** With profiles + features. */
    public JkBuild(Project project, Dependencies dependencies,
                   List<RepositorySpec> repositories, Profiles profiles, Features features) {
        this(project, dependencies, repositories, profiles, features, null);
    }

    public static JkBuild of(Project project) {
        return new JkBuild(project, Dependencies.empty(), List.of(),
                Profiles.empty(), Features.empty(), null);
    }

    /** Return a copy with the given custom jar-manifest attributes. */
    public JkBuild withManifest(Map<String, String> manifest) {
        return new JkBuild(project, dependencies, repositories, profiles, features, workspace,
                manifest, plugins, nativeConfig, build);
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
     * <ul>
     *   <li>{@code jdk} — JDK feature release (major) the project builds against.
     *       Resolved to a concrete install identifier (e.g. {@code temurin-25.0.3})
     *       in {@code jk.lock}.</li>
     *   <li>{@code java} — Java compiler language/bytecode level (major). Non-zero
     *       only for Java projects; mutually exclusive with {@code kotlin}.</li>
     *   <li>{@code kotlin} — Kotlin compiler version selector (floating by
     *       default, {@code =}-pinnable, range-capable — like a dependency
     *       version). Resolved to a concrete version in {@code jk.lock}.
     *       Non-{@code null} only for Kotlin projects; mutually exclusive with
     *       {@code java}.</li>
     *   <li>{@code main} — fully qualified main class of a runnable project,
     *       or {@code null} for a library.</li>
     *   <li>{@code shadow} — bundle an all-in-one (shadow / fat) jar.</li>
     *   <li>{@code nativeMode} — controls GraalVM native-image participation.
     *       TOML key {@code native}: {@code false} → DISABLED (never),
     *       absent → SUPPORTED (only when {@code jk native} is run explicitly),
     *       {@code true}/{@code "always"} → ALWAYS ({@code jk build} and
     *       {@code jk native} both produce the binary).</li>
     *   <li>{@code description} — free-form human-readable description.
     *       Surfaces as {@code <description>} in {@code jk publish} POMs and
     *       {@code jk export pom.xml}; {@code null} when omitted.</li>
     *   <li>{@code application} — when true, {@code jk install} also performs a
     *       {@code make install} (launcher / native binary under {@code ~/.jk}).
     *       Defaults to {@code main != null}; the {@code application} key
     *       overrides (e.g. {@code application = false} with a {@code main}).</li>
     *   <li>{@code m2install} — when true, {@code jk install} additionally
     *       hard-links/copies the jar+pom into {@code ~/.m2/repository}. Default
     *       false.</li>
     * </ul>
     *
     * <p>Java and Kotlin are independent opt-ins — a project may compile both
     * (sources under {@code src/main/java} and {@code src/main/kotlin}). Given a
     * required {@code jdk}, {@code java = <int>} opts into Java and
     * {@code kotlin = "<ver>"} opts into Kotlin (either or both). When neither
     * is declared, the languages are inferred from the source tree, defaulting
     * to Java; {@link #isKotlin()} is the cheap "kotlin declared" predicate.
     * The build-time resolution lives in {@code CompileSupport.resolveLanguages}.
     */

    /** Controls how native-image compilation participates in the build. */
    /**
     * Source layout strategy for a project.
     *
     * <ul>
     *   <li>{@code simple} — sources in {@code ./src}, tests in {@code ./test},
     *       no package declaration. Written as {@code project.layout = "simple"}.</li>
     *   <li>{@code traditional} — Maven layout: {@code src/main/java},
     *       {@code src/test/java}. Written as {@code project.layout = "traditional"}.</li>
     *   <li>{@code auto} — inferred from the directory tree. Default when
     *       {@code project.layout} is absent from {@code jk.toml}.</li>
     * </ul>
     */
    public enum Layout {
        SIMPLE, TRADITIONAL, AUTO;

        /** Parse from a jk.toml string value; null or blank → AUTO. */
        public static Layout parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            return switch (raw.trim().toLowerCase()) {
                case "simple"      -> SIMPLE;
                case "traditional" -> TRADITIONAL;
                case "auto"        -> AUTO;
                default -> throw new IllegalArgumentException(
                        "project.layout must be \"simple\", \"traditional\", or \"auto\" (got: " + raw + ")");
            };
        }

        /** The string written to jk.toml, or null for AUTO (omitted). */
        public String tomlValue() {
            return switch (this) {
                case SIMPLE      -> "simple";
                case TRADITIONAL -> "traditional";
                case AUTO        -> null;
            };
        }
    }

    public enum NativeMode {
        /** {@code native = false} — never run native-image (explicit opt-out). */
        DISABLED,
        /**
         * {@code native} key absent — eligible for {@code jk native} workspace cascade
         * but NOT auto-built by {@code jk build} or other build commands.
         */
        SUPPORTED,
        /**
         * {@code native = true} or {@code native = "always"} — native-image runs on
         * {@code jk build}, {@code jk install}, and {@code jk native}.
         */
        ALWAYS;

        public boolean isEnabled() { return this != DISABLED; }
    }

    public record Project(String group, String name, String version,
                          int jdk, int java, VersionSelector kotlin,
                          String main, boolean shadow, NativeMode nativeMode,
                          String description, boolean application, boolean m2install,
                          Layout layout) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (name.isBlank()) throw new IllegalArgumentException("project.name must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (java < 0 || jdk < 0) {
                throw new IllegalArgumentException("project.jdk/java must be non-negative");
            }
            if (nativeMode == null) nativeMode = NativeMode.DISABLED;
            if (layout == null) layout = Layout.AUTO;
            if (description != null && description.isBlank()) description = null;
        }

        /** Back-compat constructor (pre-{@code layout}). */
        public Project(String group, String name, String version,
                       int jdk, int java, VersionSelector kotlin,
                       String main, boolean shadow, NativeMode nativeMode,
                       String description, boolean application, boolean m2install) {
            this(group, name, version, jdk, java, kotlin, main, shadow, nativeMode,
                    description, application, m2install, Layout.AUTO);
        }

        /** Back-compat constructor (pre-{@code application}/{@code m2install}). */
        public Project(String group, String name, String version,
                       int jdk, int java, VersionSelector kotlin,
                       String main, boolean shadow, NativeMode nativeMode, String description) {
            this(group, name, version, jdk, java, kotlin, main, shadow, nativeMode,
                    description, main != null, false, Layout.AUTO);
        }

        /** Library project — no main, no shadow, no native; defaults to a Java project. */
        public Project(String group, String name, String version, int jdk) {
            this(group, name, version, jdk, jdk, null, null, false, NativeMode.DISABLED,
                    null, false, false, Layout.AUTO);
        }

        /** Backward-compat: true when native mode is not DISABLED. */
        public boolean nativeImage() { return nativeMode != NativeMode.DISABLED; }

        /** True when an explicit {@code main} class is set. */
        public boolean isRunnable() {
            return main != null;
        }

        /**
         * True when this project is an <em>application</em> — {@code jk install}
         * additionally performs a {@code make install} (launcher / native binary
         * under {@code ~/.jk}). Defaults to whether a {@code main} is set; the
         * {@code application} key overrides either way.
         */
        public boolean isApplication() {
            return application;
        }

        /** True when this is a Kotlin project (i.e. a {@code kotlin} version is set). */
        public boolean isKotlin() {
            return kotlin != null;
        }

        /** The {@code java} compiler release to target. Falls back to {@code jdk} (implicit java=jdk). */
        public int javaRelease() {
            return java > 0 ? java : jdk;
        }

        /** {@code "java"} / {@code "kotlin"} — derived from which compiler field is non-zero. */
        public String languageName() {
            return isKotlin() ? "kotlin" : "java";
        }
    }

    /**
     * Configuration for {@code jk native} / GraalVM native-image.
     * Declared in the {@code [native]} table of {@code jk.toml}.
     *
     * @param mainClass  overrides {@code [project].main} for the native binary entry point
     * @param name       output binary filename; defaults to {@code [project].name}
     * @param args       extra arguments prepended to every native-image invocation
     *                   (CLI {@code --} args are appended after these)
     */
    public record NativeConfig(String mainClass, String name, List<String> args) {

        public static final NativeConfig EMPTY = new NativeConfig(null, null, List.of());

        public NativeConfig {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /**
     * The optional {@code [build]} block — build-time directives that are not
     * dependencies and never reach a classpath or {@code jk.lock}.
     *
     * <p>{@code orderAfter} lists workspace members (by project name or
     * {@code group:artifact}) that must build before this one, <em>without</em>
     * adding a compile/test/runtime edge — the "build-order-only dependency" jk
     * otherwise can't express.
     *
     * <p>{@code embedSha} ({@code [build.embed-sha]}) maps a resource basename to
     * a workspace member: the build hashes that member's output jar and writes
     * the digest to {@code META-INF/<basename>-sha256.txt} in this module's
     * classes. Each value is implicitly an {@code orderAfter} entry — you must
     * build a sibling before you can hash its jar (see {@link #allOrderAfter()}).
     * This is how the engine pins the first-party worker jars it locates at runtime.
     *
     * <p>{@code testWorkerJars} ({@code [build.test-worker-jars]}) lists workspace
     * members whose built worker jar must be handed to this module's test JVM (as
     * {@code -Djk.<worker>.worker.jar}) so tests that fork that worker locate it by
     * path. Also implicitly {@code orderAfter} (the worker must be built first).
     */
    public record Build(List<String> orderAfter, Map<String, String> embedSha,
                        List<String> testWorkerJars) {

        public static final Build EMPTY = new Build(List.of(), Map.of(), List.of());

        public Build {
            orderAfter = orderAfter == null ? List.of() : List.copyOf(orderAfter);
            embedSha = embedSha == null ? Map.of() : Map.copyOf(embedSha);
            testWorkerJars = testWorkerJars == null ? List.of() : List.copyOf(testWorkerJars);
        }

        /**
         * All build-order prerequisites: explicit {@code orderAfter} plus every
         * {@code embedSha} source and {@code testWorkerJars} member (a sibling
         * must be built before you can hash its jar or hand it to a test).
         * De-duplicated, order preserved.
         */
        public List<String> allOrderAfter() {
            if (embedSha.isEmpty() && testWorkerJars.isEmpty()) return orderAfter;
            var all = new java.util.LinkedHashSet<>(orderAfter);
            all.addAll(embedSha.values());
            all.addAll(testWorkerJars);
            return List.copyOf(all);
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
