// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.EnumMap;
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
        Workspace workspace) {

    public JkBuild {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(features, "features");
        repositories = List.copyOf(repositories);
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
     *   <li>{@code kotlin} — Kotlin compiler major version. Non-zero only for
     *       Kotlin projects; mutually exclusive with {@code java}.</li>
     *   <li>{@code main} — fully qualified main class of a runnable project,
     *       or {@code null} for a library.</li>
     *   <li>{@code shadow} — bundle an all-in-one (shadow / fat) jar.</li>
     *   <li>{@code nativeMode} — controls GraalVM native-image participation.
     *       TOML key {@code native}: absent/{@code false} → DISABLED,
     *       {@code true} → SUPPORTED (explicit {@code jk native} only),
     *       {@code "always"} → ALWAYS ({@code jk build} automatically
     *       produces the binary).</li>
     * </ul>
     *
     * <p>Exactly one of {@code java}/{@code kotlin} must be set; the parser
     * enforces this. {@link #isKotlin()} is the cheap predicate.
     */

    /** Controls how native-image compilation participates in the build. */
    public enum NativeMode {
        /** {@code native = false} or absent — no native support. */
        DISABLED,
        /** {@code native = true} — user must run {@code jk native} explicitly. */
        SUPPORTED,
        /** {@code native = "always"} — {@code jk build} automatically produces the binary. */
        ALWAYS;

        public boolean isEnabled() { return this != DISABLED; }
    }

    public record Project(String group, String artifact, String version,
                          int jdk, int java, int kotlin,
                          String main, boolean shadow, NativeMode nativeMode) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("project.artifact must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (java < 0 || kotlin < 0 || jdk < 0) {
                throw new IllegalArgumentException("project.jdk/java/kotlin must be non-negative");
            }
            if (java > 0 && kotlin > 0) {
                throw new IllegalArgumentException(
                        "project must set exactly one of `java` or `kotlin`, not both");
            }
            if (nativeMode == null) nativeMode = NativeMode.DISABLED;
        }

        /** Library project — no main, no shadow, no native; defaults to a Java project. */
        public Project(String group, String artifact, String version, int jdk) {
            this(group, artifact, version, jdk, jdk, 0, null, false, NativeMode.DISABLED);
        }

        /** Backward-compat: true when native mode is not DISABLED. */
        public boolean nativeImage() { return nativeMode != NativeMode.DISABLED; }

        /** True when an explicit {@code main} class is set. */
        public boolean isRunnable() {
            return main != null;
        }

        /** True when this is a Kotlin project (i.e. {@code kotlin > 0}). */
        public boolean isKotlin() {
            return kotlin > 0;
        }

        /** The {@code java} compiler release to target. For Kotlin projects, falls back to {@code jdk}. */
        public int javaRelease() {
            return java > 0 ? java : jdk;
        }

        /** {@code "java"} / {@code "kotlin"} — derived from which compiler field is non-zero. */
        public String languageName() {
            return isKotlin() ? "kotlin" : "java";
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
