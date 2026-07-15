// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.config.WorkspaceLoader;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Scope;
import java.io.IOException;
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
 * Resolves the build graph for an entry project: the workspace root + its modules, as one
 * topologically-sorted list of build units. The single source of truth that both the build driver
 * and {@code jk explain} consume, so they agree on exactly what builds and in what order.
 *
 * <p>Every unit is a real {@code jk.toml} project built by the normal pipeline ({@code
 * BuildPipelines.coreBuilder}). There is no separate "dependency unit" concept: a local sibling is
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

    /**
     * One project to build. {@code dir} is canonical (real path) — the identity key.
     *
     * <p>Package-private on purpose: front-ends must not see (let alone build) engine units. Client
     * code reconstructing plan objects from wire data goes through {@link
     * ModulePlan#fromWire} / {@link BuildPlan.Module#fromWire} instead, per
     * {@code docs/architecture/re-foundation.md} M6.
     */
    record BuildUnit(Path dir, JkBuild manifest, String coord, Origin origin) {}

    /**
     * @param topoOrder dependency-first build order ({@code errors} empty ⇒ valid)
     * @param edges unit dir → dirs that must build before it
     * @param errors cycle / depth-cap / missing-jk.toml
     */
    public record Result(List<BuildUnit> topoOrder, Map<Path, Set<Path>> edges, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /** {@link BuildGraph#maxReadyWidth(List, Map)} over this graph's own order and edges. */
        public int maxReadyWidth() {
            return BuildGraph.maxReadyWidth(topoOrder, edges);
        }
    }

    private BuildGraph() {}

    /**
     * The widest wave of independent modules — the most that could build concurrently once
     * dependencies are honored. Simulates the same ready-set draining the build scheduler does (a
     * module is ready when all its in-graph prereqs are done), returning the largest wave. Drives
     * the concurrency the build and its ETA calibrate to, so {@code jk build} and {@code jk
     * explain} agree.
     */
    public static int maxReadyWidth(List<BuildUnit> units, Map<Path, Set<Path>> edges) {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildUnit u : units) unitDirs.add(u.dir());
        Set<Path> done = new java.util.HashSet<>();
        List<BuildUnit> remaining = new ArrayList<>(units);
        int max = 1;
        while (!remaining.isEmpty()) {
            List<BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) break; // defensive: a cycle would otherwise spin
            max = Math.max(max, ready.size());
            for (BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        return max;
    }

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

    /**
     * Order workspace modules dependency-first: each module lands after its sibling deps and its
     * {@code [build].order-after} prerequisites. The single source of truth for workspace module
     * ordering — the parallel build scheduler resolves the same edges via {@link #resolve}, while
     * the serial ({@code --no-parallel}) build and {@code jk native} route through here.
     *
     * <p>Edges are the same ones {@link Builder#addModuleEdges} wires: sibling deps across every
     * {@link Scope} (resolved by {@code group:artifact} coord, or by bare sibling name for {@code
     * workspace:} placeholders) plus {@code [build].order-after} refs (by coord or name). Keys are
     * the caller's own directory paths, returned verbatim — no canonicalization — so callers get
     * back exactly the paths they passed.
     *
     * <p>On a cycle (which {@link build.jumpkick.config.WorkspaceLoader} doesn't currently detect) the
     * unsorted stragglers are appended in declaration order so the build still attempts to make
     * progress — a cycle never silently drops a module.
     */
    public static List<Path> orderModules(Map<Path, JkBuild> modulesByDir) {
        return build.jumpkick.config.ModuleOrder.orderModules(modulesByDir);
    }

    /** See {@link build.jumpkick.config.ModuleOrder#modulePrereqs} — the one shared edge computation. */
    private static Set<Path> modulePrereqs(
            Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
        return build.jumpkick.config.ModuleOrder.modulePrereqs(moduleDir, m, dirByCoord, dirByName);
    }

    /** See {@link build.jumpkick.config.ModuleOrder#kahnSort} — the one shared topo-sort. */
    private static List<Path> kahnSort(Collection<Path> nodes, Map<Path, Set<Path>> edges) {
        return build.jumpkick.config.ModuleOrder.kahnSort(nodes, edges);
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
            // The root is a first-class unit whose inter-unit deps are explicit
            // (Cargo/uv style), so its edges come from its declared workspace deps
            // like any member — the root may depend on members, members on the root,
            // or neither. dirByName/dirByCoord already include the root (added above),
            // so member->root deps resolve to an edge here too.
            if (rootBuildable) {
                addModuleEdges(rootDir, root, dirByCoord, dirByName);
            }
        }

        /**
         * Sibling-dep + {@code [build].order-after} prereq edges. Delegates edge resolution to the
         * shared {@link BuildGraph#modulePrereqs} so the graph and {@link BuildGraph#orderModules}
         * stay in lock-step.
         */
        private void addModuleEdges(
                Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
            for (Path prereq : modulePrereqs(moduleDir, m, dirByCoord, dirByName)) {
                addEdge(moduleDir, prereq);
            }
        }

        /** Kahn topo-sort (prereqs first); a leftover set means a cycle → error. */
        List<BuildUnit> topoSort() {
            List<Path> sorted = kahnSort(units.keySet(), edges);
            if (sorted.size() != units.size()) {
                errors.add("workspace build graph has a cycle among " + (units.size() - sorted.size()) + " unit(s)");
                return List.of();
            }
            return sorted.stream().map(units::get).toList();
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
