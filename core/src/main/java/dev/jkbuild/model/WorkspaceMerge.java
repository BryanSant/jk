// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a synthetic {@link JkBuild} that combines a workspace root with
 * its members so the existing {@code LockOrchestrator} can resolve the
 * whole workspace in a single pass. Dedupes by {@code group:artifact}
 * per scope — root declarations win, then declaration order across
 * members.
 *
 * <p>v0.3 first iteration: scoped dep aggregation only. The
 * {@code dependencies.workspace} version-pinning table and the
 * {@code $workspace} placeholder substitution come in a follow-up.
 */
public final class WorkspaceMerge {

    private WorkspaceMerge() {}

    public static JkBuild merge(JkBuild root, Collection<JkBuild> members) {
        if (members.isEmpty()) return root;
        // Workspace-internal coords (each member's `<group>:<artifact>`) are
        // satisfied locally — not from any registered repository. The resolver
        // would 404 on them, so we drop them from the merged dep list.
        Set<String> internal = new HashSet<>();
        for (JkBuild member : members) {
            internal.add(member.project().group() + ":" + member.project().artifact());
        }
        Map<Scope, List<Dependency>> mergedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            Map<String, Dependency> dedup = new LinkedHashMap<>();
            for (Dependency d : root.dependencies().of(scope)) {
                if (internal.contains(d.module())) continue;
                dedup.putIfAbsent(d.module(), d);
            }
            for (JkBuild member : members) {
                for (Dependency d : member.dependencies().of(scope)) {
                    if (internal.contains(d.module())) continue;
                    dedup.putIfAbsent(d.module(), d);
                }
            }
            if (!dedup.isEmpty()) {
                mergedByScope.put(scope, new ArrayList<>(dedup.values()));
            }
        }
        return new JkBuild(
                root.project(),
                new JkBuild.Dependencies(mergedByScope),
                root.repositories(),
                root.profiles(),
                root.features(),
                root.workspace());
    }
}
