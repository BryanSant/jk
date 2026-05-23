// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The parsed contents of a project's {@code build.jk}. Each new top-level
 * block (project, dependencies, repositories, profiles, features,
 * workspace, ...) shows up as a field as its subsystem comes online.
 */
public record BuildJk(
        Project project,
        Dependencies dependencies,
        List<RepositorySpec> repositories,
        Profiles profiles,
        Features features,
        Workspace workspace) {

    public BuildJk {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(features, "features");
        repositories = List.copyOf(repositories);
    }

    /** Project + deps only — no repos, no profiles, no features, no workspace. */
    public BuildJk(Project project, Dependencies dependencies) {
        this(project, dependencies, List.of(), Profiles.empty(), Features.empty(), null);
    }

    /** Project + deps + repos, default profiles / features / no workspace. */
    public BuildJk(Project project, Dependencies dependencies, List<RepositorySpec> repositories) {
        this(project, dependencies, repositories, Profiles.empty(), Features.empty(), null);
    }

    /** With profiles. */
    public BuildJk(Project project, Dependencies dependencies,
                   List<RepositorySpec> repositories, Profiles profiles) {
        this(project, dependencies, repositories, profiles, Features.empty(), null);
    }

    /** With profiles + features. */
    public BuildJk(Project project, Dependencies dependencies,
                   List<RepositorySpec> repositories, Profiles profiles, Features features) {
        this(project, dependencies, repositories, profiles, features, null);
    }

    public static BuildJk of(Project project) {
        return new BuildJk(project, Dependencies.empty(), List.of(),
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
     *   <li>{@code main} — fully qualified main class of a runnable project,
     *       or {@code null} for a library.</li>
     *   <li>{@code shadow} — bundle an all-in-one (shadow / fat) jar.</li>
     *   <li>{@code nativeImage} — wire a GraalVM native-image build. Stored
     *       under TOML key {@code native}.</li>
     *   <li>{@code language} — primary source language; one of {@code "java"}
     *       or {@code "kotlin"}. Defaults to {@code "java"} when absent.</li>
     * </ul>
     */
    public record Project(String group, String artifact, String version, String jdk,
                          String main, boolean shadow, boolean nativeImage, String language) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("project.artifact must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (language == null || language.isBlank()) language = "java";
        }

        /** Library project — no main, no shadow, no native, default language. */
        public Project(String group, String artifact, String version, String jdk) {
            this(group, artifact, version, jdk, null, false, false, "java");
        }

        /** True when an explicit {@code main} class is set. */
        public boolean isRunnable() {
            return main != null;
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
