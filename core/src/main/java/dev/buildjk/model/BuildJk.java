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
     * The {@code project { ... }} block of a {@code build.jk}.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code main} — fully qualified main class of a runnable project,
     *       or {@code null} for a library.</li>
     *   <li>{@code shadow} — bundle an all-in-one (shadow / fat) jar.</li>
     *   <li>{@code nativeImage} — wire a GraalVM native-image build. Stored
     *       under HOCON key {@code native}.</li>
     *   <li>{@code language} — primary source language; one of {@code "java"}
     *       or {@code "kotlin"}. Defaults to {@code "java"} when absent.</li>
     *   <li>{@code bin} — <strong>deprecated</strong> alias of {@code main}
     *       retained only so the parser can accept legacy {@code build.jk}
     *       files during the migration window. New code constructs a
     *       {@code Project} via the 4-arg, 5-arg, or 8-arg constructors
     *       (all leave {@code bin == null}); only the HOCON parser populates
     *       it when an old-style {@code bin = ...} key is parsed. To be
     *       removed in a future release.</li>
     * </ul>
     */
    public record Project(String group, String artifact, String version, String jdk,
                          String main, boolean shadow, boolean nativeImage, String language,
                          @Deprecated(forRemoval = true) String bin) {

        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("project.artifact must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
            if (language == null || language.isBlank()) language = "java";
        }

        /** Back-compat constructor — no main, no shadow, no native, default language. */
        public Project(String group, String artifact, String version, String jdk) {
            this(group, artifact, version, jdk, null, false, false, "java", null);
        }

        /**
         * Back-compat constructor that lets the legacy {@code bin} field through.
         * Callers should migrate to the canonical constructor; this exists so the
         * HOCON parser can preserve the legacy semantic during the migration window.
         *
         * @deprecated Use the canonical constructor and pass {@code main} instead.
         */
        @Deprecated(forRemoval = true)
        public Project(String group, String artifact, String version, String jdk, String bin) {
            this(group, artifact, version, jdk, null, false, false, "java", bin);
        }

        /**
         * Whether this project produces an executable application. True when an
         * explicit {@code main} class is set, or when the deprecated {@code bin}
         * field was present in the source {@code build.jk} (legacy runnable).
         */
        public boolean isRunnable() {
            return main != null || bin != null;
        }

        /**
         * @deprecated Replaced by {@link #main()}. Returns {@code null} for any
         *     {@code Project} not constructed from a legacy {@code build.jk} that
         *     used the {@code bin} key. To be removed in a future release.
         */
        @Override
        @Deprecated(forRemoval = true)
        public String bin() {
            return bin;
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
