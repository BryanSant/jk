// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the build graph for an entry project: the workspace root + its modules, as one
 * topologically-sorted list of build units. The single source of truth that both the build driver
 * and {@code jk explain} consume, so they agree on exactly what builds and in what order.
 *
 * <p>Every unit is a real {@code jk.toml} project built by the normal pipeline ({@code
 * BuildPipeline.coreBuilder}). There is no separate "dependency unit" concept: a local sibling is
 * always a workspace module, and a git dependency (any ref type) is always a lock-pinned
 * coordinate resolved by {@link GitSourceResolution} — neither needs its own build unit here.
 * Edges point from a unit to the units that must build before it (prereqs): sibling deps plus
 * {@code [build].order-after}.
 *
 * <p>Cycles and chains deeper than {@link #MAX_DEPTH} are reported as errors before any build
 * runs.
 */
public final class BuildGraph {

    /** Guards symlink loops {@code toRealPath} can't collapse and pathological graphs. */
    public static final int MAX_DEPTH = 512;

    public enum Origin {
        ROOT,
        MODULE
    }

    /** One project to build. {@code dir} is canonical (real path) — the identity key. */
    public record BuildUnit(Path dir, JkBuild manifest, String coord, Origin origin) {}

    /**
     * @param topoOrder dependency-first build order ({@code errors} empty ⇒ valid)
     * @param edges unit dir → dirs that must build before it
     * @param errors cycle / depth-cap / missing-jk.toml
     */
    public record Result(List<BuildUnit> topoOrder, Map<Path, Set<Path>> edges, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    private BuildGraph() {}

    /** Resolve the graph rooted at {@code entryDir}/{@code entry}. */
    public static Result resolve(Path entryDir, JkBuild entry) throws IOException {
        Builder b = new Builder();
        if (entry.isWorkspaceRoot()) {
            b.addWorkspace(entryDir, entry, Origin.MODULE);
        } else {
            b.addUnit(b.canonical(entryDir), entry, Origin.ROOT);
        }
        List<BuildUnit> order = b.errors.isEmpty() ? b.topoSort() : List.of();
        return new Result(order, b.edges, b.errors);
    }

    // ---------------------------------------------------------------------

    private static final class Builder {
        final Map<Path, BuildUnit> units = new LinkedHashMap<>(); // canonical dir → unit
        final Map<Path, Set<Path>> edges = new LinkedHashMap<>(); // dir → prereq dirs
        final List<String> errors = new ArrayList<>();

        void addUnit(Path canonicalDir, JkBuild m, Origin origin) {
            String coord = m.project().group() + ":" + m.project().name();
            units.putIfAbsent(canonicalDir, new BuildUnit(canonicalDir, m, coord, origin));
            edges.putIfAbsent(canonicalDir, new LinkedHashSet<>());
        }

        void addEdge(Path from, Path prereq) {
            if (!from.equals(prereq))
                edges.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(prereq);
        }

        /** Add every module of a workspace as a unit, then wire module-order edges. */
        void addWorkspace(Path wsRoot, JkBuild root, Origin moduleOrigin) throws IOException {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(wsRoot, root);
            // Index by coord + bare name for sibling/order-after edge resolution.
            Map<String, Path> dirByCoord = new LinkedHashMap<>();
            Map<String, Path> dirByName = new LinkedHashMap<>();
            // A workspace root that carries its own sources is itself a build unit — it
            // compiles and locks like any module (its coordinate is already in the
            // collision set). A pure coordinator root (no src/) is not built; its merged
            // deps are still validated up front by the whole-workspace lock check.
            Path rootDir = canonical(wsRoot);
            boolean rootBuildable = CompileSupport.hasSources(rootDir);
            if (rootBuildable) {
                addUnit(rootDir, root, Origin.ROOT);
                dirByCoord.put(root.project().group() + ":" + root.project().name(), rootDir);
                dirByName.put(root.project().name(), rootDir);
            }
            for (var e : modules.entrySet()) {
                Path c = canonical(e.getKey());
                addUnit(c, e.getValue(), moduleOrigin);
                dirByCoord.put(
                        e.getValue().project().group() + ":"
                                + e.getValue().project().name(),
                        c);
                dirByName.put(e.getValue().project().name(), c);
            }
            for (var e : modules.entrySet()) {
                Path moduleDir = canonical(e.getKey());
                addModuleEdges(moduleDir, e.getValue(), dirByCoord, dirByName);
            }
            // The root may depend on its own siblings — wire those edges too.
            if (rootBuildable) {
                addModuleEdges(rootDir, root, dirByCoord, dirByName);
            }
        }

        /**
         * Sibling-dep + {@code [build].order-after} prereq edges (lifted from
         * BuildCommand.topoSortModules).
         */
        private void addModuleEdges(
                Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
            for (Scope scope : Scope.values()) {
                for (Dependency d : m.dependencies().of(scope)) {
                    String module = d.module();
                    Path depDir = dirByCoord.get(module);
                    if (depDir == null && module.startsWith("workspace:")) {
                        depDir = dirByName.get(module.substring("workspace:".length()));
                    }
                    if (depDir != null) addEdge(moduleDir, depDir);
                }
            }
            for (String ref : m.build().allOrderAfter()) {
                Path depDir = dirByCoord.get(ref);
                if (depDir == null) depDir = dirByName.get(ref);
                if (depDir != null) addEdge(moduleDir, depDir);
            }
        }

        /** Kahn topo-sort (prereqs first); a leftover set means a cycle → error. */
        List<BuildUnit> topoSort() {
            Map<Path, Integer> remaining = new LinkedHashMap<>();
            for (Path u : units.keySet()) {
                remaining.put(u, edges.getOrDefault(u, Set.of()).size());
            }
            Deque<Path> queue = new ArrayDeque<>();
            for (var e : remaining.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
            List<BuildUnit> sorted = new ArrayList<>();
            while (!queue.isEmpty()) {
                Path next = queue.removeFirst();
                sorted.add(units.get(next));
                for (var e : edges.entrySet()) {
                    if (e.getValue().contains(next)) {
                        if (remaining.merge(e.getKey(), -1, Integer::sum) == 0) queue.add(e.getKey());
                    }
                }
            }
            if (sorted.size() != units.size()) {
                errors.add("workspace build graph has a cycle among " + (units.size() - sorted.size()) + " unit(s)");
                return List.of();
            }
            return sorted;
        }

        Path canonical(Path p) {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                return p.toAbsolutePath().normalize();
            }
        }
    }
}
