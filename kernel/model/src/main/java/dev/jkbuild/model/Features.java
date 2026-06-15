// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code features} block of a {@code jk.toml}: named feature
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
     * The dependency <em>names</em> (the {@code [dependencies.*]} short-name
     * keys) requested by an activated feature set — the union of each feature's
     * {@code deps}, in activation order. The resolver maps these to the declared
     * {@code optional = true} dependencies and pulls them into the graph (see
     * {@code LockOrchestrator}); a name that isn't a declared optional dep is an
     * error there. Features carry names, not coords, so they inherit the full
     * dependency grammar (git / path / workspace / catalog / selectors) for free.
     */
    public List<String> requestedDepNames(Set<String> activated) {
        List<String> names = new ArrayList<>();
        for (String name : activated) {
            Feature feature = byName.get(name);
            if (feature == null) {
                throw new IllegalArgumentException("unknown feature: " + name);
            }
            names.addAll(feature.deps());
        }
        return names;
    }
}
