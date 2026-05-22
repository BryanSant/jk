// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed contents of a project's {@code build.jk} (v0.1 shape:
 * project block + declared dependencies). Repositories, profiles,
 * workspaces, and features will be added as their subsystems come online.
 */
public record BuildJk(Project project, Dependencies dependencies) {

    public BuildJk {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(dependencies, "dependencies");
    }

    public static BuildJk of(Project project) {
        return new BuildJk(project, Dependencies.empty());
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
