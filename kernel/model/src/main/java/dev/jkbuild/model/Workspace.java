// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code [workspace]} block of a root {@code jk.toml}: the literal
 * module paths plus the optional {@code [workspace.dependencies]} table
 * of shared external dep declarations inherited by modules via
 * {@code <name>.workspace = true}.
 */
public record Workspace(List<String> modules, Map<String, WorkspaceDependency> dependencies) {

    public Workspace {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(dependencies, "dependencies");
        modules = List.copyOf(modules);
        dependencies = Map.copyOf(new LinkedHashMap<>(dependencies));
    }

    /** Modules-only constructor; no shared workspace dependencies. */
    public Workspace(List<String> modules) {
        this(modules, Map.of());
    }

    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /**
     * A single entry in {@code [workspace.dependencies]}. Holds the same
     * shape as an inline dep table — group/artifact/version, or a path
     * or git source override. Resolution happens when a child writes
     * {@code <name>.workspace = true}.
     */
    public record WorkspaceDependency(
            String group, String artifact, VersionSelector version, GitSource gitSource, String pathSource) {

        public WorkspaceDependency {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(artifact, "artifact");
            if (group.isBlank()) {
                throw new IllegalArgumentException("workspace dependency group must not be blank");
            }
            if (artifact.isBlank()) {
                throw new IllegalArgumentException("workspace dependency artifact must not be blank");
            }
            int sourceCount = (version != null ? 1 : 0) + (gitSource != null ? 1 : 0) + (pathSource != null ? 1 : 0);
            if (sourceCount != 1) {
                throw new IllegalArgumentException(
                        "workspace dependency must set exactly one of `version`, `git`, or `path`");
            }
        }

        public String module() {
            return group + ":" + artifact;
        }
    }
}
