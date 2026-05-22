// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed contents of a project's {@code build.jk}. Each new top-level
 * block (project, dependencies, repositories, profiles, ...) shows up as
 * a field as its subsystem comes online.
 */
public record BuildJk(
        Project project,
        Dependencies dependencies,
        List<RepositorySpec> repositories,
        Profiles profiles) {

    public BuildJk {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(profiles, "profiles");
        repositories = List.copyOf(repositories);
    }

    /** Convenience constructor with empty profiles. */
    public BuildJk(Project project, Dependencies dependencies, List<RepositorySpec> repositories) {
        this(project, dependencies, repositories, Profiles.empty());
    }

    /** Convenience constructor: empty repositories and profiles. */
    public BuildJk(Project project, Dependencies dependencies) {
        this(project, dependencies, List.of(), Profiles.empty());
    }

    public static BuildJk of(Project project) {
        return new BuildJk(project, Dependencies.empty(), List.of(), Profiles.empty());
    }

    public record Project(String group, String artifact, String version, String jdk) {
        public Project {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(version, "version");
            if (group.isBlank()) throw new IllegalArgumentException("project.group must not be blank");
            if (artifact.isBlank()) throw new IllegalArgumentException("project.artifact must not be blank");
            if (version.isBlank()) throw new IllegalArgumentException("project.version must not be blank");
        }
    }

    public record Dependencies(Map<Scope, List<Dependency>> byScope) {

        public Dependencies {
            Objects.requireNonNull(byScope, "byScope");
            // Defensive deep copy with stable ordering.
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
