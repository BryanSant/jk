// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.List;
import java.util.Objects;

/**
 * A named, additive set of dependencies (PRD §7.8). A feature can pull
 * in raw dep coords and/or activate other features transitively.
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
