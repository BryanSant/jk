// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-first ordering of a workspace's parsed modules — the one topo-sort the whole tool
 * shares (see re-foundation: the CLI/engine duplicate was unified here). Rehomed from the engine's
 * {@code BuildGraph} in the slim-client Stage 5 split: the client needs the ordering engine-less
 * (e.g. {@code jk native}'s pre-flight GraalVM resolution loop), and the engine's graph builder
 * keeps delegating to these same edge/sort primitives so the two can never disagree.
 */
public final class ModuleOrder {

    private ModuleOrder() {}

    /**
     * Order {@code modulesByDir} dependency-first (Kahn). On a cycle, the strays are appended in
     * declaration order so the build still tries to make progress.
     */
    public static List<Path> orderModules(Map<Path, JkBuild> modulesByDir) {
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>(); // for workspace: references
        for (var e : modulesByDir.entrySet()) {
            dirByCoord.put(coord(e.getValue()), e.getKey());
            dirByName.put(e.getValue().project().name(), e.getKey());
        }
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            edges.put(e.getKey(), modulePrereqs(e.getKey(), e.getValue(), dirByCoord, dirByName));
        }
        List<Path> sorted = new ArrayList<>(kahnSort(modulesByDir.keySet(), edges));
        if (sorted.size() != modulesByDir.size()) {
            // Cycle. Fall back to declaration order for the stragglers
            // so the build still tries to make progress.
            for (Path p : modulesByDir.keySet()) {
                if (!sorted.contains(p)) sorted.add(p);
            }
        }
        return sorted;
    }

    private static String coord(JkBuild m) {
        return m.project().group() + ":" + m.project().name();
    }

    /**
     * Sibling-dep + {@code [build].order-after} prereqs for one module, resolved against the
     * workspace's coord/name indexes. Self-references are dropped. Shared by the engine's graph
     * builder and the map-based {@link #orderModules} entry so both compute identical edges.
     */
    public static Set<Path> modulePrereqs(
            Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
        Set<Path> prereqs = new LinkedHashSet<>();
        for (Scope scope : Scope.values()) {
            for (Dependency d : m.dependencies().of(scope)) {
                String module = d.module();
                Path depDir = dirByCoord.get(module);
                // workspace placeholders resolve by their bare sibling name
                if (depDir == null && d.isWorkspace()) {
                    depDir = dirByName.get(d.workspaceName());
                }
                if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
            }
        }
        // [build].order-after: build-order-only edges (no classpath/lock). Each entry names a
        // sibling by project name or group:artifact coord.
        for (String ref : m.build().allOrderAfter()) {
            Path depDir = dirByCoord.get(ref);
            if (depDir == null) depDir = dirByName.get(ref);
            if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
        }
        return prereqs;
    }

    /**
     * Kahn topo-sort (prereqs first) over an explicit prereq-edge map. Returns the nodes in
     * dependency-first order; a returned list SHORTER than {@code nodes} means a cycle left some
     * nodes unplaced — callers apply their own leftover policy.
     */
    public static List<Path> kahnSort(Collection<Path> nodes, Map<Path, Set<Path>> edges) {
        Map<Path, Integer> remaining = new LinkedHashMap<>();
        for (Path n : nodes) remaining.put(n, edges.getOrDefault(n, Set.of()).size());
        Deque<Path> queue = new ArrayDeque<>();
        for (var e : remaining.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        List<Path> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Path next = queue.removeFirst();
            sorted.add(next);
            for (var e : edges.entrySet()) {
                if (e.getValue().contains(next)) {
                    if (remaining.merge(e.getKey(), -1, Integer::sum) == 0) queue.add(e.getKey());
                }
            }
        }
        return sorted;
    }
}
