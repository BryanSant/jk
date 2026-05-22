// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a synthetic {@link BuildJk} that combines a workspace root with
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

    public static BuildJk merge(BuildJk root, Collection<BuildJk> members) {
        if (members.isEmpty()) return root;
        Map<Scope, List<Dependency>> mergedByScope = new EnumMap<>(Scope.class);
        for (Scope scope : Scope.values()) {
            Map<String, Dependency> dedup = new LinkedHashMap<>();
            for (Dependency d : root.dependencies().of(scope)) {
                dedup.putIfAbsent(d.module(), d);
            }
            for (BuildJk member : members) {
                for (Dependency d : member.dependencies().of(scope)) {
                    dedup.putIfAbsent(d.module(), d);
                }
            }
            if (!dedup.isEmpty()) {
                mergedByScope.put(scope, new ArrayList<>(dedup.values()));
            }
        }
        return new BuildJk(
                root.project(),
                new BuildJk.Dependencies(mergedByScope),
                root.repositories(),
                root.profiles(),
                root.features(),
                root.workspace());
    }
}
