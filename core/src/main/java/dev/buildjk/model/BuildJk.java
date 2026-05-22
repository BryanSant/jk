// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * The parsed contents of a project's {@code build.jk} (v0.1 minimal shape:
 * just the {@code project} block). Dependencies, repositories, profiles,
 * workspaces, and features will be added as the corresponding subsystems
 * come online.
 */
public record BuildJk(Project project) {

    public BuildJk {
        Objects.requireNonNull(project, "project");
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
}
