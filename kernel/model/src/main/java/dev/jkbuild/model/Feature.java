// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.List;
import java.util.Objects;

/**
 * A named, additive set of dependencies (PRD §7.8). {@code deps} are the <em>names</em> of {@code
 * [dependencies.*]} entries declared with {@code optional = true}; activating the feature pulls
 * those deps into the resolution (so a feature dep inherits the full dependency grammar — git /
 * path / workspace / catalog short-name / version selectors). {@code features} activates other
 * features transitively.
 */
public record Feature(String name, List<String> deps, List<String> features) {

    public Feature {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(deps, "deps");
        Objects.requireNonNull(features, "features");
        deps = List.copyOf(deps);
        features = List.copyOf(features);
    }

    public static Feature of(String name) {
        return new Feature(name, List.of(), List.of());
    }
}
