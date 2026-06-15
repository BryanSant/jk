// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.util.JkThreads;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Builds a project's transitive composite ({@code path} / branch-git) dependency
 * units from source — compile-only, via the SAME real pipeline as any project
 * ({@code BuildPipeline.coreBuilder}). jk's {@code includeBuild} analog, shared by
 * {@code jk build}, {@code jk run}, and {@code jk install} so each builds its
 * composite deps before the classpath that consumes them ({@code CompositeLocator}
 * then locates the jars).
 *
 * <p>Independent units build <em>in parallel</em> (a topo level-scheduled driver):
 * dependency units are compile-only, so parallelism never runs tests concurrently
 * (tests stay serial — that's a workspace-member concern). The CPU work inside
 * each unit shares the bounded {@link JkThreads#cpu()} pool, so node parallelism
 * doesn't oversubscribe. Each unit builds silently and reports a one-line result
 * (concurrent live progress bars can't share one terminal region).
 */
final class CompositeBuild {

    private static final Object PRINT_LOCK = new Object();

    private CompositeBuild() {}

    /**
     * Resolve the composite graph rooted at {@code entryDir}/{@code entry} and build
     * each dependency unit (compile-only, independent units in parallel). Returns 0
     * on success, else an exit code (errors already printed). A no-op when no
     * composite deps exist.
     */
    static int buildDependencies(Path entryDir, JkBuild entry, Path cache, Path jdksDir,
                                 String profileName, GlobalOptions global)
            throws IOException, InterruptedException {
        BuildGraph.Result graph = BuildGraph.resolve(entryDir, entry, cache.resolve("git"));
        if (graph.hasErrors()) {
            for (String err : graph.errors()) System.err.println("error[composite]: " + err);
            return 2;
        }
        List<BuildGraph.BuildUnit> deps = graph.topoOrder().stream()
                .filter(BuildGraph.BuildUnit::isDependency).toList();
        if (deps.isEmpty()) return 0;

        Set<Path> depDirs = deps.stream().map(BuildGraph.BuildUnit::dir).collect(Collectors.toSet());
        Map<Path, Set<Path>> edges = graph.edges();
        if (!global.outputIsJson()) {
            System.out.println("Building " + deps.size() + " composite dependenc"
                    + (deps.size() == 1 ? "y" : "ies") + " from source"
                    + (deps.size() > 1 ? " (parallel)" : "") + "…");
        }

        // Topo level-scheduled: build all units whose composite prereqs are done,
        // concurrently, then advance to the next level.
        Set<Path> done = ConcurrentHashMap.newKeySet();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(deps);
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(depDirs::contains).allMatch(done::contains))
                    .toList();
            // Acyclic (BuildGraph guarantees it), so `ready` is never empty here.
            List<CompletableFuture<UnitResult>> futures = new ArrayList<>();
            for (BuildGraph.BuildUnit u : ready) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> buildOne(u, cache, jdksDir, profileName, global), JkThreads.io()));
            }
            boolean anyFail = false;
            for (CompletableFuture<UnitResult> f : futures) {
                UnitResult ur = f.join();
                report(ur, global);
                if (!ur.success) anyFail = true;
            }
            if (anyFail) return 1;
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        return 0;
    }

    private record UnitResult(BuildGraph.BuildUnit unit, boolean success, long millis, String outcome) {}

    /** Build one dependency unit (compile-only) silently via the real pipeline. */
    private static UnitResult buildOne(BuildGraph.BuildUnit u, Path cache, Path jdksDir,
                                       String profileName, GlobalOptions global) {
        Path dir = u.dir();
        try {
            BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                    dir, cache, dir.resolve("jk.toml"), dir.resolve("jk.lock"), dir,
                    1, 0, profileName, jdksDir, true /* skipTests */, global.verbose);
            Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
            BuildPipeline.appendDeclaredTails(builder, inputs);
            Goal goal = builder.build();
            GoalResult r = GoalConsole.runGoalSilently(goal, cache);
            String outcome = goal.get(BuildPipeline.BUILD_OUTCOME).orElse("");
            return new UnitResult(u, r.success(), r.duration() == null ? 0 : r.duration().toMillis(), outcome);
        } catch (Exception e) {
            return new UnitResult(u, false, 0, e.getMessage() == null ? "build error" : e.getMessage());
        }
    }

    private static void report(UnitResult ur, GlobalOptions global) {
        if (global.outputIsJson()) return;
        Theme t = Theme.active();
        synchronized (PRINT_LOCK) {
            if (ur.success) {
                String detail = "up-to-date".equals(ur.outcome) || "no-sources".equals(ur.outcome)
                        ? "up to date" : ur.millis + "ms";
                System.out.println("  " + Theme.colorize("✓", t.success())
                        + " " + ur.unit.coord() + " (" + detail + ")");
            } else {
                System.out.println("  " + Theme.colorize("✗", t.error())
                        + " " + ur.unit.coord() + " — " + ur.outcome);
            }
        }
    }
}
