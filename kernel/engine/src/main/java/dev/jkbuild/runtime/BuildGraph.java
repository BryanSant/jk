// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;

import java.io.IOException;
import java.nio.file.Files;
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
 * Resolves the full <em>composite build graph</em> for an entry project: the
 * workspace root + its modules + every transitive {@code path =} and
 * <em>branch</em> git dependency, as one topologically-sorted list of build
 * units. The single source of truth that both the build driver and
 * {@code jk explain} consume, so they agree on exactly what builds and in what
 * order.
 *
 * <p>Every unit is a real {@code jk.toml} project built by the normal pipeline
 * ({@code BuildPipeline.coreBuilder}); dependency units (PATH / BRANCH_GIT) are
 * compile-only. Immutable (tag/rev) git deps are NOT units here — they stay on
 * the lock-pinned {@link GitSourceResolution} path. Edges point from a unit to
 * the units that must build before it (prereqs), unifying workspace module
 * order ({@code [build].order-after} + sibling deps) with composite edges.
 *
 * <p>Cycles (e.g. git1→git2→git1, path loops, or module cycles) and chains
 * deeper than {@link #MAX_DEPTH} are reported as errors before any build runs.
 */
public final class BuildGraph {

    /** Guards symlink loops {@code toRealPath} can't collapse and pathological graphs. */
    public static final int MAX_DEPTH = 512;

    public enum Origin { ROOT, MODULE, PATH, BRANCH_GIT }

    /**
     * One project to build. {@code dir} is canonical (real path) — the identity key.
     * {@code gitSource} is non-null only for {@link Origin#BRANCH_GIT}.
     */
    public record BuildUnit(Path dir, JkBuild manifest, String coord, Origin origin, GitSource gitSource) {
        /** Dependency units are compile-only; root/modules run their own tests. */
        public boolean isDependency() { return origin == Origin.PATH || origin == Origin.BRANCH_GIT; }
    }

    /**
     * @param topoOrder dependency-first build order ({@code errors} empty ⇒ valid)
     * @param edges     unit dir → dirs that must build before it
     * @param errors    cycle / depth-cap / coordinate-mismatch / missing-jk.toml
     */
    public record Result(List<BuildUnit> topoOrder, Map<Path, Set<Path>> edges, List<String> errors) {
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    private BuildGraph() {}

    /**
     * Resolve the graph rooted at {@code entryDir}/{@code entry}. {@code gitRoot}
     * is the git checkout cache ({@code $JK_CACHE/git}) used to clone branch git
     * targets during discovery (the clone reveals a target's edges).
     */
    public static Result resolve(Path entryDir, JkBuild entry, Path gitRoot)
            throws IOException, InterruptedException {
        Builder b = new Builder(gitRoot);
        try {
            if (entry.isWorkspaceRoot()) {
                b.addWorkspace(entryDir, entry, Origin.MODULE);
            } else {
                b.addUnit(b.canonical(entryDir), entry, Origin.ROOT, null);
                b.discoverComposites(entryDir, entry, 1);
            }
        } catch (GitFetcher.TagRewriteException e) {
            throw e; // never happens for branches, but keep the checked type honest
        }
        List<BuildUnit> order = b.errors.isEmpty() ? b.topoSort() : List.of();
        return new Result(order, b.edges, b.errors);
    }

    /** The composite (path / branch-git) deps of {@code project} across MAIN+RUNTIME+TEST, deduped by module. */
    static List<Dependency> compositeDeps(JkBuild project) {
        List<Dependency> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scope scope : List.of(Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.EXPORT)) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (isComposite(d) && seen.add(d.module())) out.add(d);
            }
        }
        return out;
    }

    static boolean isComposite(Dependency d) {
        return d.isPath() || (d.isGit() && !d.gitSource().ref().isImmutable());
    }

    /**
     * Resolve a composite dependency's project directory: a {@code path} dep's
     * normalized dir, or a branch git dep's checkout dir (cloned via
     * {@link GitFetcher}, cached). Shared with {@code CompositeLocator} and
     * {@code jk idea}.
     */
    public static Path targetDir(Path fromDir, Dependency dep, Path gitRoot)
            throws IOException, InterruptedException {
        if (dep.isPath()) {
            return fromDir.resolve(dep.pathSource()).normalize();
        }
        GitSource src = dep.gitSource();
        Path checkout = new GitFetcher(gitRoot).fetch(src).checkoutPath();
        return (src.path() != null && !src.path().isBlank())
                ? checkout.resolve(src.path()) : checkout;
    }

    // ---------------------------------------------------------------------

    private static final class Builder {
        final Path gitRoot;
        final Map<Path, BuildUnit> units = new LinkedHashMap<>();    // canonical dir → unit
        final Map<Path, Set<Path>> edges = new LinkedHashMap<>();    // dir → prereq dirs
        final List<String> errors = new ArrayList<>();
        final LinkedHashSet<Path> onStack = new LinkedHashSet<>();   // cycle detection

        Builder(Path gitRoot) { this.gitRoot = gitRoot; }

        void addUnit(Path canonicalDir, JkBuild m, Origin origin, GitSource git) {
            String coord = m.project().group() + ":" + m.project().name();
            units.putIfAbsent(canonicalDir, new BuildUnit(canonicalDir, m, coord, origin, git));
            edges.putIfAbsent(canonicalDir, new LinkedHashSet<>());
        }

        void addEdge(Path from, Path prereq) {
            if (!from.equals(prereq)) edges.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(prereq);
        }

        /** Add every module of a workspace as a unit, wire module-order edges, then descend composites. */
        void addWorkspace(Path wsRoot, JkBuild root, Origin moduleOrigin)
                throws IOException, InterruptedException {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(wsRoot, root);
            // Index by coord + bare name for sibling/order-after edge resolution.
            Map<String, Path> dirByCoord = new LinkedHashMap<>();
            Map<String, Path> dirByName = new LinkedHashMap<>();
            for (var e : modules.entrySet()) {
                Path c = canonical(e.getKey());
                addUnit(c, e.getValue(), moduleOrigin, null);
                dirByCoord.put(e.getValue().project().group() + ":" + e.getValue().project().name(), c);
                dirByName.put(e.getValue().project().name(), c);
            }
            for (var e : modules.entrySet()) {
                Path moduleDir = canonical(e.getKey());
                JkBuild m = e.getValue();
                addModuleEdges(moduleDir, m, dirByCoord, dirByName);
                discoverComposites(e.getKey(), m, 1);
            }
        }

        /** Sibling-dep + {@code [build].order-after} prereq edges (lifted from BuildCommand.topoSortModules). */
        private void addModuleEdges(Path moduleDir, JkBuild m,
                                    Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
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

        /** Discover the composite (path / branch-git) deps of a unit; recurse depth-first. */
        void discoverComposites(Path fromDir, JkBuild from, int depth)
                throws IOException, InterruptedException {
            if (depth > MAX_DEPTH) {
                errors.add("composite dependency chain exceeds " + MAX_DEPTH
                        + " levels (likely a cycle through symlinks)");
                return;
            }
            Path fromCanon = canonical(fromDir);
            for (Dependency dep : compositeDeps(from)) {
                Path targetDir = targetDirOf(fromDir, dep);
                if (targetDir == null) continue; // error recorded
                Path key = canonical(targetDir);
                addEdge(fromCanon, key);
                if (units.containsKey(key)) continue;              // memoized
                if (onStack.contains(key)) {
                    errors.add("composite dependency cycle through " + targetDir);
                    continue;
                }
                Path toml = targetDir.resolve("jk.toml");
                if (!Files.isRegularFile(toml)) {
                    errors.add("composite dependency `" + dep.module() + "` has no jk.toml at " + targetDir);
                    continue;
                }
                JkBuild target;
                try {
                    target = JkBuildParser.parse(Files.readString(toml));
                } catch (RuntimeException e) {
                    errors.add("composite dependency `" + dep.module() + "` failed to parse "
                            + toml + ": " + e.getMessage());
                    continue;
                }
                String coord = target.project().group() + ":" + target.project().name();
                if (!coord.equals(dep.module())) {
                    errors.add("composite dependency `" + dep.module() + "` points at a project whose "
                            + "coordinate is `" + coord + "` (" + targetDir + "); they must match");
                    continue;
                }
                Origin origin = dep.isPath() ? Origin.PATH : Origin.BRANCH_GIT;
                addUnit(key, target, origin, dep.isGit() ? dep.gitSource() : null);
                onStack.add(key);
                discoverComposites(targetDir, target, depth + 1);
                onStack.remove(key);
            }
        }

        /** Resolve a composite dep's project dir (path dir, or a branch git checkout). */
        private Path targetDirOf(Path fromDir, Dependency dep) throws IOException, InterruptedException {
            return targetDir(fromDir, dep, gitRoot);
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
                errors.add("composite/workspace build graph has a cycle among "
                        + (units.size() - sorted.size()) + " unit(s)");
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
