// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A POM with its parent chain merged, BOM imports flattened, and {@code <dependencyManagement>}
 * applied to fill versions of bare deps. The thing the resolver actually wants to look at.
 *
 * <p>The {@link Pom.Dep} entries here may still carry classifier/type/scope variations; the dedup
 * key is {@code groupId:artifactId} (Maven's rule — a module appears at most once per scope).
 */
public record EffectivePom(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Map<String, String> properties,
        List<Pom.Dep> dependencies,
        List<Pom.Dep> managedDependencies) {

    public EffectivePom {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(packaging, "packaging");
        properties = Map.copyOf(properties);
        dependencies = List.copyOf(dependencies);
        managedDependencies = List.copyOf(managedDependencies);
    }
}
