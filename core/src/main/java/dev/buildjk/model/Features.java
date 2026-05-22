// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code features} block of a {@code build.jk}: named feature
 * definitions plus the {@code default} list applied when no
 * {@code --features} flag is given.
 */
public record Features(Map<String, Feature> byName, List<String> defaults) {

    public Features {
        Objects.requireNonNull(byName, "byName");
        Objects.requireNonNull(defaults, "defaults");
        byName = Map.copyOf(byName);
        defaults = List.copyOf(defaults);
    }

    public static Features empty() {
        return new Features(Map.of(), List.of());
    }

    public boolean isEmpty() {
        return byName.isEmpty();
    }

    /**
     * Expand a requested set of feature names into the full transitive
     * activation set. {@code withDefaults} flips on the {@code default}
     * list. Unknown feature names → {@link IllegalArgumentException}.
     */
    public Set<String> activate(Set<String> requested, boolean withDefaults) {
        Objects.requireNonNull(requested, "requested");
        Set<String> seeds = new LinkedHashSet<>();
        if (withDefaults) seeds.addAll(defaults);
        seeds.addAll(requested);

        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!result.add(name)) continue;
            Feature feature = byName.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name);
            }
            queue.addAll(feature.features());
        }
        return result;
    }

    /**
     * Translate the activated feature set into {@link Dependency}s suitable
     * for adding to the main-scope resolution. Each feature dep coord is
     * parsed as {@code group:artifact:version}.
     */
    public List<Dependency> resolveDeps(Set<String> activated) {
        List<Dependency> deps = new ArrayList<>();
        for (String name : activated) {
            Feature feature = byName.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name);
            }
            for (String coord : feature.deps()) {
                Coordinate parsed = Coordinate.parse(coord);
                deps.add(new Dependency(parsed.module(),
                        VersionSelector.parse(parsed.version())));
            }
        }
        return deps;
    }
}
