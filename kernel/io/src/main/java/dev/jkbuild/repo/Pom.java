// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed contents of a single Maven POM. Property substitution within
 * the POM's own scope ({@code ${project.*}} and {@code <properties>}) has
 * already been applied; cross-POM concerns (parent inheritance, BOM
 * imports, external properties) are the resolver's job.
 */
public record Pom(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Parent parent,
        Map<String, String> properties,
        List<Dep> dependencies,
        List<Dep> managedDependencies) {

    public Pom {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(managedDependencies, "managedDependencies");
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
    }

    /** True when groupId or version was inherited from {@code <parent>}. */
    public boolean inheritsCoordsFromParent() {
        return parent != null && (groupId == null || version == null);
    }

    public record Parent(String groupId, String artifactId, String version) {
        public Parent {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * A {@code <dependency>} entry. Maven's {@code system} scope is captured
     * as a raw string here so the parser stays lossless; jk's higher layers
     * reject it per PRD §7.2.
     */
    public record Dep(
            String groupId,
            String artifactId,
            String version,
            String scope,
            boolean optional,
            String classifier,
            String type,
            List<Exclusion> exclusions) {

        public Dep {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(exclusions, "exclusions");
            exclusions = List.copyOf(exclusions);
        }

        /** Maven coordinate without classifier or type. */
        public String module() {
            return groupId + ":" + artifactId;
        }

        public record Exclusion(String groupId, String artifactId) {
            public Exclusion {
                Objects.requireNonNull(groupId, "groupId");
                Objects.requireNonNull(artifactId, "artifactId");
            }
        }
    }
}
