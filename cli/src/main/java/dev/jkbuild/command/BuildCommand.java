// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.AggregateContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk build} — smart meta-goal that orchestrates the full pipeline:
 *
 * <ol>
 *   <li>{@code parse-build} — load {@code jk.toml}; if {@code jk.lock} is absent, run the lock
 *       resolver inline (same as {@code jk lock}).
 *   <li>{@code sync-deps} (IO) — ensure all locked artifacts are in the CAS; virtually a no-op when
 *       everything is already cached.
 *   <li>{@code ensure-jdk} (IO, parallel with sync-deps) — install the pinned JDK when it is not
 *       yet on disk.
 *   <li>{@code compile-java} (CPU) — javac, with action-cache + freshness stamp skip layers.
 *   <li>{@code compile-kotlin} (CPU) — no-op when no {@code .kt} sources.
 *   <li>{@code copy-resources} (CPU) — mirror {@code src/main/resources}.
 *   <li>{@code compile-test} (CPU) — compile {@code src/test/java}.
 *   <li>{@code run-tests} (IO) — fork JUnit Platform runner(s).
 *   <li>{@code package-jar} (CPU) — assemble the project jar.
 *   <li>{@code native-image} (IO, only when {@code native = "always"}) — GraalVM native-image
 *       compilation.
 *   <li>{@code write-stamp} (SYNC) — refresh the freshness stamp.
 * </ol>
 */
public final class BuildCommand implements CliCommand {

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String description() {
        return "Compile, test, and package the project";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"),
                Opt.flag("Build modules one at a time (rich serial view).", "--no-parallel"),
                Opt.flag("", "--parallel").hide(),
                Opt.flag("Run modules' tests concurrently too. Default: off.", "--parallel-tests"));
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;
    boolean noParallel;
    boolean parallelTests;

    // ---- GoalKeys -------------------------------------------------------
    //
    // BuildPipeline owns the phase DAG and all of its keys; BuildCommand only
    // reads a few results back out of the finished goal to render its result
    // line. GoalKeys are name-keyed, so these match BuildPipeline's by name.

    private static final GoalKey<String> BUILD_OUTCOME = GoalKey.of("build-outcome", String.class);
    private static final GoalKey<Path> JAR_PATH = GoalKey.of("jar-path", Path.class);
    private static final GoalKey<BuildLayout> LAYOUT = GoalKey.of("layout", BuildLayout.class);
    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);

    // ---- Entry point ----------------------------------------------------

    @Override
    public int run(Invocation in) throws Exception {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.noParallel = in.isSet("no-parallel") && !in.isSet("parallel") && !in.isSet("parallel-tests");
        this.global = GlobalOptions.from(in);
        // Opt-in: run modules' tests concurrently. Default serializes them
        // (shared ports/locks/fixtures) — see BuildPipeline's test gate.
        this.parallelTests = in.isSet("parallel-tests");
        BuildPipeline.setParallelTests(parallelTests);
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(startDir));
            return 2;
        }
        // Peek at the manifest before committing to a per-dir build. A
        // workspace root dispatches to runWorkspaceBuild. A workspace module
        // also redirects — jk build from any module builds the whole workspace
        // in topological order, same as running from the root.
        JkBuild peek;
        try {
            peek = JkBuildParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (peek.isWorkspaceRoot()) {
            return buildWorkspace(startDir, peek);
        }
        // Module redirect: discover the enclosing workspace and build from there.
        try {
            var rootOpt = WorkspaceLocator.findRoot(startDir);
            if (rootOpt.isPresent()) {
                Path root = rootOpt.get();
                if (!global.outputIsJson()) {
                    System.err.println("jk build: building workspace from "
                            + root.getFileName()
                            + " (module: "
                            + startDir.getFileName()
                            + ")");
                }
                return buildWorkspace(root, JkBuildParser.parse(root.resolve("jk.toml")));
            }
        } catch (java.io.IOException e) {
            // Workspace discovery failed — fall through to single-project build.
        }
        applyMemoryPlan(1); // single project: one module, tests fork `workers` JVMs
        int dep = buildCompositeDeps(startDir, peek);
        if (dep != 0) return dep;
        return runForDir(startDir);
    }

    /** Default: parallel graph build; {@code --no-parallel}: the serial rich aggregate view. */
    private int buildWorkspace(Path root, JkBuild rootBuild) throws Exception {
        return noParallel ? runWorkspaceBuild(root, rootBuild) : runGraphParallel(root, rootBuild);
    }

    /**
     * Size worker-JVM concurrency and per-JVM heaps from the host's free memory before any fork.
     * {@code requestedModules} is the peak number of modules that could build at once; folded with
     * {@code --workers} and {@code --parallel-tests} into a desired JVM count, which the memory plan
     * may shrink (down to serial). A no-op when the user pinned heap tuning.
     */
    private void applyMemoryPlan(int requestedModules) {
        int w = workers != null && workers > 0 ? workers : 1;
        int cap = Runtime.getRuntime().availableProcessors();
        int requested = dev.jkbuild.worker.HeapPlan.requestedJvms(requestedModules, w, parallelTests, cap);
        dev.jkbuild.worker.HeapPlan.Plan plan = dev.jkbuild.worker.JvmOptions.planAndApply(requested);
        if (plan != null && plan.warning() != null && !global.outputIsJson()) {
            System.err.println("jk build: " + plan.warning());
        }
    }

    /** Peak number of units that become ready simultaneously across the topo schedule. */
    private static int maxReadyWidth(List<BuildGraph.BuildUnit> units, Map<Path, Set<Path>> edges) {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : units) unitDirs.add(u.dir());
        Set<Path> done = new java.util.HashSet<>();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(units);
        int max = 1;
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) break; // defensive: a cycle would otherwise spin
            max = Math.max(max, ready.size());
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        return max;
    }

    private static final Object OUT_LOCK = new Object();

    /** One unit's build outcome, with its buffered output (flushed together on completion). */
    private record UnitOutcome(String coord, boolean success, int exitCode, long millis, List<String> output) {}

    /**
     * Build the whole composite + workspace graph in parallel (Option B): one {@link BuildGraph},
     * scheduled by topological level, independent units built concurrently on {@link JkThreads#io()}
     * (their CPU work shares the bounded cpu pool, so no oversubscription). Each unit runs buffered —
     * its output is captured and flushed as one contiguous block on completion, so parallel logs
     * never interleave. Tests are serialized across units by default (BuildPipeline's gate); {@code
     * --parallel-tests} lifts that.
     */
    private int runGraphParallel(Path entryDir, JkBuild entryBuild) throws Exception {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        // Detect mode first (zero I/O) so we can branch before touching the disk.
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        boolean live = mode == GoalConsole.Mode.AUTO || mode == GoalConsole.Mode.QUIET;

        if (!live) {
            // --output json / --verbose: buffered, non-animated path.  Resolve the
            // graph normally (no TUI to show during it) then run plain.
            BuildGraph.Result graph;
            try {
                graph = BuildGraph.resolve(entryDir, entryBuild, cache.resolve("git"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("jk build: interrupted resolving the build graph");
                return 2;
            }
            if (graph.hasErrors()) {
                for (String err : graph.errors()) System.err.println(ConsoleSpec.errorLine("composite", err));
                return 2;
            }
            List<BuildGraph.BuildUnit> units = graph.topoOrder();
            if (units.isEmpty()) {
                System.out.println("(workspace declares no modules)");
                return 0;
            }
            applyMemoryPlan(Math.min(maxReadyWidth(units, graph.edges()), JkThreads.CPU_THREADS));
            return runGraphPlain(units, graph.edges());
        }

        // Live path (AUTO / QUIET): start the TUI *before* any I/O so the spinner
        // is visible while BuildGraph.resolve() and forecastDirty() run.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Build", animate);

        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(entryDir, entryBuild, cache.resolve("git"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            view.finishGoalFailure("interrupted resolving the build graph");
            return 2;
        }
        if (graph.hasErrors()) {
            for (String err : graph.errors()) view.writeAbove(ConsoleSpec.errorLine("composite", err));
            view.finishGoalFailure("dependency resolution failed");
            return 2;
        }
        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        if (units.isEmpty()) {
            view.finishGoalSuccess("workspace declares no modules");
            return 0;
        }
        // Size worker concurrency + heaps to the graph's peak parallelism before
        // any unit forks (caps the JVM slot pool the scheduler draws from).
        applyMemoryPlan(Math.min(maxReadyWidth(units, graph.edges()), JkThreads.CPU_THREADS));
        // forecastDirty also runs under the spinner — stat/CAS-only scan, typically <200ms.
        long buildStart = System.nanoTime();
        Set<Path> dirtyDirs = forecastDirty(graph, cache);
        if (dirtyDirs.isEmpty()) {
            // Nothing to rebuild — settle with the chip line immediately; no progress bar flash.
            view.finishGoalSuccess(upToDateTail("all modules", buildStart));
            return 0;
        }
        return runGraphLive(view, units, graph.edges(), dirtyDirs, cache, buildStart, entryDir);
    }

    /**
     * Modules that will actually rebuild this run, in dependency order — the same cascade-aware,
     * content-hash forecast {@code jk explain} shows ({@link BuildPlanForecast}). The live pre-scan
     * reserves these modules' real compile/test slice up front (via {@code forceRebuild}), so the
     * bar's total is correct from the start instead of growing mid-build (which slid it backward). On
     * any failure, treat every module as dirty — over-reserving only ever makes the bar jump
     * <em>forward</em> as cached phases collapse, never backward.
     */
    private static Set<Path> forecastDirty(BuildGraph.Result graph, Path cache) {
        try {
            dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(cache);
            dev.jkbuild.task.ActionCache ac = new dev.jkbuild.task.ActionCache(cas, cache.resolve("actions"));
            Set<Path> dirty = new java.util.HashSet<>();
            for (BuildPlanForecast.Module m : BuildPlanForecast.of(graph, cas, ac, cache)) {
                if (m.dirty()) dirty.add(m.unit().dir());
            }
            return dirty;
        } catch (RuntimeException e) {
            Set<Path> all = new java.util.HashSet<>();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
            return all;
        }
    }

    /** Buffered, append-only scheduler: each unit flushes a block + a [k/N] line on completion. */
    private int runGraphPlain(List<BuildGraph.BuildUnit> units, Map<Path, Set<Path>> edges) {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : units) unitDirs.add(u.dir());
        Set<Path> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(units);
        int total = units.size();
        int completed = 0;
        long start = System.nanoTime();
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains))
                    .toList();
            List<java.util.concurrent.CompletableFuture<UnitOutcome>> futures = new ArrayList<>();
            for (BuildGraph.BuildUnit u : ready) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> buildUnit(u), JkThreads.io()));
            }
            for (java.util.concurrent.CompletableFuture<UnitOutcome> f : futures) {
                UnitOutcome o = f.join();
                report(o, ++completed, total);
                if (!o.success()) {
                    System.err.println("jk build: " + o.coord() + " failed (exit " + o.exitCode() + ")");
                    return o.exitCode();
                }
            }
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        System.out.println(GoalWedge.chipLine(
                dev.jkbuild.cli.tui.Glyphs.CHECK, "Build", GlobalConfig.nerdfont(), modulesTail(total, start)));
        return 0;
    }

    /**
     * Live aggregate scheduler: one {@link CommandManager} (goal mode) shows a spinner header + a
     * single bar calibrated to the whole graph + a tree of the modules building <em>right now</em>;
     * the tree grows to the parallelism limit and shrinks back to 0 as units drain. Each unit's
     * process output is buffered and flushed (with a ✓/✗ {@code [k/N]} line) above the region when it
     * completes — so concurrent logs never interleave. On a non-interactive terminal nothing
     * animates; the same blocks + lines print append-only.
     */
    private int runGraphLive(
            CommandManager view,
            List<BuildGraph.BuildUnit> units,
            Map<Path, Set<Path>> edges,
            Set<Path> dirtyDirs,
            Path cache,
            long start,
            Path workspaceRoot)
            throws Exception {
        Set<Path> unitDirs = new java.util.HashSet<>();
        for (BuildGraph.BuildUnit u : units) unitDirs.add(u.dir());
        AggregateContext agg = new AggregateContext(view);
        int total = units.size();

        // Pre-scan: prepare every unit's goal and sum its estimated weight so the
        // bar calibrates to the whole-graph total and advances 0→100% across it.
        // Each module also gets a timing recorder feeding one shared sink, folded
        // into the learned ledger once the build succeeds.
        List<dev.jkbuild.runtime.PhaseTimings.Sample> timingSamples =
                java.util.Collections.synchronizedList(new ArrayList<>());
        Map<Path, PreparedModule> prepared = new LinkedHashMap<>();
        long totalWeight = 0;
        for (BuildGraph.BuildUnit u : units) {
            PreparedModule pm =
                    prepareModule(u.dir(), u.isDependency() || buildOpts.skipTests, dirtyDirs.contains(u.dir()));
            if (pm == null) {
                view.finishGoalFailure(noTomlTail(u.dir().toString(), start));
                return 2;
            }
            pm.goal()
                    .addListener(new dev.jkbuild.runtime.PhaseTimingsRecorder(
                            pm.dir().toString(), timingSamples));
            prepared.put(u.dir(), pm);
            totalWeight += pm.barWeight();
        }
        agg.calibrate(totalWeight);
        // Seed the countdown with a schedule-aware estimate (the same model jk explain
        // uses): the parallel graph build overlaps independent modules, so the serial
        // sum over-counts. (--no-parallel runs runWorkspaceBuild, not this path, so here
        // is always parallel.) The bar still calibrates to the summed tick total above;
        // only the wall-clock countdown is parallel-aware.
        List<dev.jkbuild.runtime.EffortWeights.ModuleCost> costs = new ArrayList<>();
        for (var e : prepared.entrySet()) {
            int tw = e.getValue().goal().phases().stream()
                    .filter(p -> p.name().equals("run-tests"))
                    .mapToInt(dev.jkbuild.run.Phase::estimateWeight)
                    .sum();
            costs.add(new dev.jkbuild.runtime.EffortWeights.ModuleCost(
                    e.getKey(),
                    edges.getOrDefault(e.getKey(), Set.of()),
                    (int) e.getValue().barWeight(),
                    tw));
        }
        int etaWorkers = workers != null && workers > 0 ? workers : 1;
        int concurrency = dev.jkbuild.worker.HeapPlan.requestedJvms(
                maxReadyWidth(units, edges),
                etaWorkers,
                parallelTests,
                Runtime.getRuntime().availableProcessors());
        // Countdown only when this project has useful learned timings; otherwise leave the
        // estimate at 0 so the clock counts elapsed time up from 0s.
        boolean usefulTimings = dev.jkbuild.runtime.PhaseTimings.load(cache)
                .hasTimingsFor(prepared.keySet().stream().map(Path::toString).toList());
        view.setEtaEstimate(
                usefulTimings
                        ? dev.jkbuild.runtime.EffortWeights.scheduleMillis(costs, concurrency, false, parallelTests)
                        : 0);

        // Pre-compute workspace hard-link destinations for application modules so collision
        // detection sees the full module set before any build starts.
        Map<Path, Path> wsLinks = computeWorkspaceLinks(prepared.keySet(), workspaceRoot);

        Set<Path> done = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<BuildGraph.BuildUnit> remaining = new ArrayList<>(units);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        // Units' process output is buffered here and flushed below the live region
        // once the build settles, so concurrent logs never interleave with the view.
        List<String> deferredOutput = java.util.Collections.synchronizedList(new ArrayList<>());
        while (!remaining.isEmpty()) {
            List<BuildGraph.BuildUnit> ready = remaining.stream()
                    .filter(u -> edges.getOrDefault(u.dir(), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains))
                    .toList();
            List<java.util.concurrent.CompletableFuture<UnitOutcome>> futures = new ArrayList<>();
            List<Path> readyDirs = new ArrayList<>();
            for (BuildGraph.BuildUnit u : ready) {
                PreparedModule pm = prepared.get(u.dir());
                readyDirs.add(u.dir());
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> buildUnitLive(u, pm, agg, view, completed, total, deferredOutput), JkThreads.io()));
            }
            UnitOutcome failure = null;
            for (int i = 0; i < futures.size(); i++) {
                UnitOutcome o = futures.get(i).join();
                if (o.success()) {
                    linkModuleArtifacts(readyDirs.get(i), wsLinks);
                } else if (failure == null) {
                    failure = o;
                }
            }
            if (failure != null) {
                // Everything the build emitted prints ABOVE the result line, exactly
                // like the success route: buffered sub-process output first, then the
                // error diagnostics just above the "‼ Build failed" line — which stays
                // last so the user sees the outcome without scrolling.
                List<String> above = snapshot(deferredOutput);
                for (GoalResult.Diagnostic d : agg.lastErrors()) {
                    if ("test-failure".equals(d.code())) continue; // already printed by run-tests
                    above.add(ConsoleSpec.renderError(d));
                }
                view.finishGoalFailure(failureTail(failure.coord(), start), above);
                return failure.exitCode();
            }
            for (BuildGraph.BuildUnit u : ready) done.add(u.dir());
            remaining.removeAll(ready);
        }
        // Whole graph succeeded: fold this run's real phase durations into the
        // learned ledger (EWMA) so the next build's bar is time-accurate. Failed
        // builds don't record — their phase times are abnormal.
        dev.jkbuild.runtime.PhaseTimings.record(
                cache, timingSamples, dev.jkbuild.runtime.PhaseTimings.DEFAULT_ALPHA, System.currentTimeMillis());
        view.finishGoalSuccess(
                dirtyDirs.isEmpty() ? upToDateTail("all modules", start) : modulesTail(total, start),
                snapshot(deferredOutput));
        return 0;
    }

    /**
     * Build one unit feeding the shared live {@code view}; buffer its output. On completion: when
     * animating, the colored ✓/✗ {@code [k/N]} line joins the region's completed tail and the unit's
     * process output is appended to {@code deferredOutput} (flushed below the region once the build
     * settles, so concurrent logs never interleave with the live view); otherwise (pipes / {@code
     * --quiet}) the buffered block + line print append-only, atomically.
     */
    private UnitOutcome buildUnitLive(
            BuildGraph.BuildUnit unit,
            PreparedModule pm,
            AggregateContext agg,
            CommandManager view,
            java.util.concurrent.atomic.AtomicInteger completed,
            int total,
            List<String> deferredOutput) {
        List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
        long t0 = System.nanoTime();
        boolean ok;
        int exit;
        try {
            GoalResult result =
                    GoalConsole.runGoalIntoBuffered(pm.goal(), pm.cache(), unit.coord(), agg, pm.barWeight(), buf);
            ok = result.success();
            exit = ok ? 0 : exitCodeFor(pm.goal());
        } catch (RuntimeException e) {
            synchronized (buf) {
                buf.add("  ✗ " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            ok = false;
            exit = 1;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        int k = completed.incrementAndGet();
        String completion = completionLine(ok, k, total, unit.coord(), ms);
        if (view.animating()) {
            view.addCompletion(completion);
            synchronized (buf) {
                if (!buf.isEmpty()) deferredOutput.addAll(buf);
            }
        } else {
            StringBuilder block = new StringBuilder();
            synchronized (buf) {
                for (String l : buf) block.append(l).append('\n');
            }
            block.append(completion);
            view.writeAbove(block.toString());
        }
        return new UnitOutcome(unit.coord(), ok, exit, ms, List.of());
    }

    /** Print buffered unit output below the (settled) live region, in completion order. */
    /** Stable copy of the concurrently-appended deferred-output buffer. */
    private static List<String> snapshot(List<String> deferred) {
        synchronized (deferred) {
            return new ArrayList<>(deferred);
        }
    }

    /** Build one graph unit (dependency units compile-only) with output buffered. */
    private UnitOutcome buildUnit(BuildGraph.BuildUnit unit) {
        PreparedModule pm = prepareModule(unit.dir(), unit.isDependency() || buildOpts.skipTests);
        if (pm == null) {
            return new UnitOutcome(
                    unit.coord(),
                    false,
                    2,
                    0,
                    List.of("no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(unit.dir())));
        }
        long t0 = System.nanoTime();
        try {
            GoalConsole.Buffered b = GoalConsole.runGoalBuffered(pm.goal(), pm.cache());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int exit = b.result().success() ? 0 : exitCodeFor(pm.goal());
            return new UnitOutcome(unit.coord(), b.result().success(), exit, ms, b.output());
        } catch (RuntimeException e) {
            return new UnitOutcome(
                    unit.coord(),
                    false,
                    1,
                    (System.nanoTime() - t0) / 1_000_000,
                    List.of("  ✗ " + (e.getMessage() == null ? e.toString() : e.getMessage())));
        }
    }

    /** Test failures exit 4; everything else exits 1 (mirrors {@link #runPrepared}). */
    private static int exitCodeFor(Goal goal) {
        var testResult = goal.get(TEST_RESULT).orElse(null);
        return testResult != null && !testResult.allPassed() ? 4 : 1;
    }

    /** Flush a finished unit's buffered output, then a one-line [k/N] result. Serialized. */
    private void report(UnitOutcome o, int k, int total) {
        if (global.outputIsJson()) return;
        synchronized (OUT_LOCK) {
            for (String line : o.output()) System.out.println(line);
            System.out.println(completionLine(o.success(), k, total, o.coord(), o.millis()));
        }
    }

    /**
     * A finished unit's scroll-back line: {@code ✓ [01 of 16] group:artifact took 16ms}. No leading
     * indent (it's complete, not active); the numerator is zero-padded to the denominator's width;
     * the duration is normalized like every other jk duration ({@link ConsoleSpec#took}). Colors:
     * green check, bright-black {@code [ ]} brackets around a plain {@code NN of MM} count, the
     * {@code group:artifact} plain with a strikethrough to mark it done, and the bright-black italic
     * {@code took …} suffix. A failed unit keeps the red cross and {@code — failed}.
     */
    private static String completionLine(boolean ok, int index, int total, String coord, long millis) {
        var th = Theme.active();
        String mark = Theme.colorize(ok ? "✓" : "✗", ok ? th.success() : th.error());
        String num = String.format("%0" + Integer.toString(total).length() + "d", index);
        StringBuilder sb = new StringBuilder();
        sb.append(mark)
                .append(' ')
                .append(Theme.colorize("[", th.darkGray()))
                .append(num)
                .append(" of ")
                .append(total)
                .append(Theme.colorize("]", th.darkGray()))
                .append(' ');
        if (ok) {
            sb.append(Theme.colorize(coord, th.plainWhite().crossedOut()))
                    .append(' ')
                    .append(ConsoleSpec.took(java.time.Duration.ofMillis(millis)));
        } else {
            sb.append(CommandManager.coloredModule(coord)).append(' ').append(Theme.colorize("— failed", th.error()));
        }
        return sb.toString();
    }

    /**
     * Build every module of the workspace whose root is {@code workspaceRoot}. Modules compile in
     * topological order computed from each module's inter-sibling deps (a sibling listed as a regular
     * Maven coord whose group+artifact match another module's {@code [project]}). Each module's jar
     * lands at {@code <workspaceRoot>/target/} per the {@link BuildLayout} contract.
     *
     * <p>If the root manifest also declares its own {@code [project]} with source files, that build
     * is skipped — the workspace root is coordinator-only here. (We may revisit this once virtual
     * workspaces land; for now the assumption matches every multi-module JVM project we've seen.)
     */
    private int runWorkspaceBuild(Path workspaceRoot, JkBuild root) throws Exception {
        Map<Path, JkBuild> modulesByDir;
        try {
            modulesByDir = WorkspaceLoader.loadModules(workspaceRoot, root);
        } catch (RuntimeException e) {
            System.err.println("jk build: " + e.getMessage());
            return 2;
        }
        if (modulesByDir.isEmpty()) {
            System.out.println("(workspace declares no modules)");
            return 0;
        }
        applyMemoryPlan(1); // --no-parallel: modules build serially (peak = 1 module)
        // Build any composite (path / branch-git) dependency units the workspace
        // (or its modules) declare, before the modules that consume them.
        int dep = buildCompositeDeps(workspaceRoot, root);
        if (dep != 0) return dep;
        List<Path> sorted = topoSortModules(modulesByDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        // Pre-compute workspace link map once (covers all modes) — needs the full sorted list.
        Map<Path, Path> wsLinks = computeWorkspaceLinks(sorted, workspaceRoot);

        // --output json / --verbose keep per-module rendering (NDJSON streams,
        // verbose wants the full per-phase log). Banners separate the modules.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path moduleDir = sorted.get(i);
                System.out.println();
                System.out.println(
                        "══ " + workspaceRoot.relativize(moduleDir) + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runForDir(moduleDir);
                if (exit != 0) {
                    System.err.println(
                            "jk build: " + workspaceRoot.relativize(moduleDir) + " failed (exit " + exit + ")");
                    return exit;
                }
                linkModuleArtifacts(moduleDir, wsLinks);
            }
            return 0;
        }

        // AUTO / QUIET: every module feeds ONE aggregate view (spinner header +
        // single bar + merged phase list). Settle it once after the last module.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Build", animate);
        AggregateContext agg = new AggregateContext(view);
        int built = 0;
        long buildStart = System.nanoTime();
        // Route every module's phase/process output above the one shared region.
        try (var cap = view.captureOutput()) {
            // Breadth-first pre-scan — build every module's goal and sum its
            // estimated ticks so the bar calibrates to the whole-workspace total
            // and advances 0→100% without resetting per module. These are the very
            // goals we then run in-process, one module at a time.
            Map<Path, PreparedModule> prepared = new LinkedHashMap<>();
            long total = 0;
            for (Path moduleDir : sorted) {
                PreparedModule pm;
                try {
                    pm = prepareModule(moduleDir);
                } catch (Exception e) {
                    view.finishFailure(
                            Theme.colorize("Build failed", Theme.active().error()) + " " + elapsedSince(buildStart));
                    throw e;
                }
                if (pm == null) {
                    view.finishFailure(
                            "No jk.toml in " + workspaceRoot.relativize(moduleDir) + " " + elapsedSince(buildStart));
                    return 2;
                }
                total += pm.barWeight();
                prepared.put(moduleDir, pm);
            }
            agg.calibrate(total);
            // Countdown only with useful learned timings; otherwise count up from 0s.
            Path etaCache = cacheDir != null ? cacheDir : JkDirs.cache();
            boolean usefulTimings = dev.jkbuild.runtime.PhaseTimings.load(etaCache)
                    .hasTimingsFor(
                            prepared.keySet().stream().map(Path::toString).toList());
            view.setEtaEstimate(usefulTimings ? (long) total * dev.jkbuild.runtime.EffortWeights.MS_PER_WEIGHT : 0);

            for (Path moduleDir : sorted) {
                String module = workspaceRoot.relativize(moduleDir).toString();
                PreparedModule pm = prepared.get(moduleDir);
                int exit;
                try {
                    exit = runPrepared(pm, agg, buildStart);
                } catch (Exception e) {
                    view.finishFailure(buildFailedAt(module, buildStart));
                    throw e;
                }
                if (exit != 0) {
                    // Error diagnostics print above the result line (which stays last),
                    // mirroring the success route.
                    List<String> above = new ArrayList<>();
                    for (GoalResult.Diagnostic d : agg.lastErrors()) {
                        // Per-test failures (code "test-failure") were already printed
                        // in full by the run-tests phase; keep them for --output json
                        // but don't echo them again here.
                        if ("test-failure".equals(d.code())) continue;
                        above.add(ConsoleSpec.renderError(d));
                    }
                    view.finishFailure(buildFailedAt(module, buildStart), above);
                    return exit;
                }
                linkModuleArtifacts(moduleDir, wsLinks);
                built++;
            }
        }
        view.finishGoalSuccess(modulesTail(sorted.size(), buildStart));
        return 0;
    }

    /**
     * Order workspace modules so each builds after its sibling deps. Kahn's algorithm against the
     * in-workspace dep graph. Sibling matches are by full Maven coord ({@code group:artifact}) —
     * modules declare sibling deps explicitly with inline coords, no {@code .workspace = true}
     * shorthand needed.
     *
     * <p>The graph also includes {@code [build].order-after} edges: build-order-only prerequisites
     * (by project name or {@code group:artifact}) that carry no classpath or lockfile weight — used
     * when a module must build after a sibling it doesn't actually depend on (e.g. to embed that
     * sibling's artifact hash).
     *
     * <p>Cycles (which the workspace's {@link dev.jkbuild.config.WorkspaceLoader} doesn't currently
     * detect) result in any unsorted modules being appended in declaration order so the build still
     * attempts to make progress.
     */
    static List<Path> topoSortModules(Map<Path, JkBuild> modulesByDir) {
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName = new HashMap<>(); // for workspace: references
        for (var e : modulesByDir.entrySet()) {
            String coord = e.getValue().project().group() + ":"
                    + e.getValue().project().name();
            dirByCoord.put(coord, e.getKey());
            dirByName.put(e.getValue().project().name(), e.getKey());
        }
        Map<Path, Set<Path>> requires = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            Set<Path> prereqs = new LinkedHashSet<>();
            for (Scope scope : Scope.values()) {
                for (Dependency d : e.getValue().dependencies().of(scope)) {
                    String module = d.module();
                    Path depDir = dirByCoord.get(module);
                    // workspace: deps use "workspace:<name>" — resolve by bare name
                    if (depDir == null && module.startsWith("workspace:")) {
                        depDir = dirByName.get(module.substring("workspace:".length()));
                    }
                    if (depDir != null && !depDir.equals(e.getKey())) {
                        prereqs.add(depDir);
                    }
                }
            }
            // [build].order-after (+ [build.embed-sha] sources): build-order-only
            // edges (no classpath/lock). Each entry names a sibling by project name
            // or group:artifact coord.
            for (String ref : e.getValue().build().allOrderAfter()) {
                Path depDir = dirByCoord.get(ref);
                if (depDir == null) depDir = dirByName.get(ref);
                if (depDir != null && !depDir.equals(e.getKey())) {
                    prereqs.add(depDir);
                }
            }
            requires.put(e.getKey(), prereqs);
        }
        Map<Path, Integer> remainingPrereqs = new HashMap<>();
        for (var e : requires.entrySet()) {
            remainingPrereqs.put(e.getKey(), e.getValue().size());
        }
        java.util.Deque<Path> queue = new java.util.ArrayDeque<>();
        for (var e : remainingPrereqs.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<Path> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Path next = queue.removeFirst();
            sorted.add(next);
            for (var e : requires.entrySet()) {
                if (e.getValue().contains(next)) {
                    int rem = remainingPrereqs.merge(e.getKey(), -1, Integer::sum);
                    if (rem == 0) queue.add(e.getKey());
                }
            }
        }
        if (sorted.size() != modulesByDir.size()) {
            // Cycle. Fall back to declaration order for the stragglers
            // so the build still tries to make progress.
            for (Path p : modulesByDir.keySet()) {
                if (!sorted.contains(p)) sorted.add(p);
            }
        }
        return sorted;
    }

    /**
     * Build the entry's transitive composite ({@code path} / branch-git) dependency units from source
     * — compile-only, in dependency order — via the SAME real pipeline as any project ({@code
     * prepareModule} → {@code coreBuilder}). jk's {@code includeBuild} analog. The consumer/modules
     * then locate these jars on their classpath ({@code CompositeLocator}). No-op when none are
     * declared. Returns 0 on success, else an exit code (errors already printed).
     */
    private int buildCompositeDeps(Path entryDir, JkBuild entry) throws Exception {
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        return CompositeBuild.buildDependencies(entryDir, entry, cache, jdksDir, profileName, global);
    }

    private int runForDir(Path dir) throws Exception {
        return runForDir(dir, null);
    }

    /**
     * Build one project directory. When {@code agg} is non-null this is a workspace module whose
     * events feed the shared aggregate view rather than a per-module progress display.
     */
    private int runForDir(Path dir, AggregateContext agg) throws Exception {
        long startNanos = System.nanoTime(); // captured before prepareModule so timing includes the predict
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk build: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return 2;
        }
        return runPrepared(prepareModule(dir), agg, startNanos);
    }

    /**
     * Construct (but do not run) a module's build goal: inputs → core phases → declared tails.
     * Returns {@code null} when {@code dir} has no {@code jk.toml}. Split out of {@link #runForDir}
     * so the workspace path can build every module's goal up front and sum {@link
     * Goal#estimatedTotalWeight()} to calibrate the shared progress bar before any module runs.
     */
    private PreparedModule prepareModule(Path dir) {
        return prepareModule(dir, buildOpts.skipTests);
    }

    /**
     * As {@link #prepareModule(Path)} but with an explicit {@code skipTests} — used to build
     * composite dependency units compile-only (a dependency's tests aren't run when it's consumed as
     * a source dependency).
     */
    private PreparedModule prepareModule(Path dir, boolean skipTests) {
        return prepareModule(dir, skipTests, false);
    }

    /**
     * As {@link #prepareModule(Path, boolean)} but with a {@code forceRebuild} hint (this module will
     * rebuild because an upstream sibling changed) so its compile/test slice is reserved in the
     * calibrated total up front — keeping the aggregate bar honest from the start. Set from {@link
     * #forecastDirty}.
     */
    private PreparedModule prepareModule(Path dir, boolean skipTests, boolean forceRebuild) {
        try {
            dir = dir.toRealPath();
        } catch (java.io.IOException ignored) {
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        Path lockFile = dir.resolve("jk.lock");
        int workerCount = workers != null && workers > 0 ? workers : 1;
        // Lexical pre-discovery so the run-tests phase's scope is known before
        // any phase runs — see TestCommand.estimateTestCount.
        int estimatedTestCount = skipTests ? 0 : TestCommand.estimateTestCount(dir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                dir,
                cache,
                buildFile,
                lockFile,
                dir,
                workerCount,
                estimatedTestCount,
                profileName,
                jdksDir,
                skipTests,
                global.verbose);
        // Quick pre-check: is every work phase already cached? Uses only stat/CAS
        // lookups — the same operations the parse-build phase would run lazily.
        // Doubles as a gate for the single-module TUI bypass in runPrepared().
        boolean fullyCached = false;
        if (!forceRebuild) {
            try {
                dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(inputs.cache());
                JkBuild build = JkBuildParser.parse(buildFile); // memoized; effectively free
                dev.jkbuild.runtime.CompileSupport.Languages langs =
                        dev.jkbuild.runtime.CompileSupport.resolveLanguages(build.project(), dir);
                boolean useJava = langs.java();
                boolean useKotlin = langs.kotlin();
                boolean compact = dev.jkbuild.runtime.CompileSupport.isSimpleLayout(build.project(), dir);
                dev.jkbuild.runtime.EffortWeights.Plan plan =
                        dev.jkbuild.runtime.EffortWeights.predict(inputs, cas, compact, useJava, useKotlin);
                fullyCached = plan.fullyCached();
            } catch (Exception ignored) {
                // best-effort; proceed normally if prediction throws
            }
        }
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs, forceRebuild);
        BuildPipeline.appendDeclaredTails(builder, inputs);
        Goal goal = builder.build();
        // Estimate the module's bar weight once, here — the workspace pre-scan sums
        // these into the calibrated total, and the same value is the module's slice
        // of the aggregate bar (see AggregateModuleListener). Computing it once
        // keeps the slice byte-for-byte equal to what was summed into `total`.
        return new PreparedModule(
                dir, buildTarget(buildFile, dir), cache, goal, goal.estimatedTotalWeight(), fullyCached);
    }

    /** Run an already-built module goal and map its result to an exit code. */
    private int runPrepared(PreparedModule pm, AggregateContext agg, long startNanos) {
        Goal goal = pm.goal();
        // Single-module fast path: skip the TUI entirely when every work phase is already
        // cached. The pre-check in prepareModule() confirmed this with stat/CAS lookups only.
        // Workspace modules (agg != null) always run so the aggregate view stays consistent.
        if (agg == null && pm.fullyCached() && GoalConsole.isInteractiveTerminal() && !global.outputIsJson()) {
            System.out.println(dev.jkbuild.cli.tui.GoalWedge.chipLine(
                    dev.jkbuild.cli.tui.Glyphs.CHECK,
                    "Build",
                    dev.jkbuild.config.GlobalConfig.nerdfont(),
                    buildOk() + ", project up to date " + elapsedSince(startNanos)));
            return 0;
        }
        GoalResult result;
        if (agg != null) {
            // Workspace module: feed the one shared aggregate view, scaling this
            // module's progress into its reserved slice of the calibrated total.
            result = GoalConsole.runGoalInto(goal, pm.cache(), pm.target(), agg, pm.barWeight());
        } else {
            // chip = true → settle through the goal chip (" ✓ Build ▶ Build successful …"),
            // matching the workspace path. onSuccess/onFailure return the tail after the verb.
            ConsoleSpec spec =
                    new ConsoleSpec("Build", r -> projectTail(goal), r -> GoalWedge.coord(pm.target()), true);
            result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), pm.cache(), spec, pm.target());
        }

        if (result.success()) {
            // Cache settings are user-global only; resolve() reads ~/.jk/config.toml
            // (a project jk.toml's [cache] is intentionally ignored).
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, pm.cache(), exe));
            return 0;
        }
        // Test failures get exit 4; other failures exit 1.
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    /**
     * A workspace module's goal, built and ready to run, paired with its pre-scan bar weight — the
     * module's slice of the calibrated aggregate total.
     */
    private record PreparedModule(
            Path dir, String target, Path cache, Goal goal, long barWeight, boolean fullyCached) {}

    // ---- success summary -----------------------------------------------

    /** Header module label for the goal view: the project's {@code group:artifact}. */
    static String buildTarget(Path buildFile, Path dir) {
        try {
            var p = JkBuildParser.parse(buildFile).project();
            return p.group() + ":" + p.name();
        } catch (Exception e) {
            return dir.getFileName() == null ? "" : dir.getFileName().toString();
        }
    }

    /**
     * Dim italic {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime()}.
     */
    static String elapsedSince(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return dev.jkbuild.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(ms));
    }

    /**
     * Workspace build-failure result line: red "Build failed", the failing module in cyan, dim
     * duration — e.g. {@code ‼ Build failed: Failure at kernel/core in 8.7s} (the {@code ‼} + red is
     * added by {@code finishFailure}).
     */
    /** The green {@code Build successful} lead that opens every build success message. */
    private static String buildOk() {
        return Theme.colorize("Build successful", Theme.active().success());
    }

    /** Success tail {@code Build successful for N modules took T} (work done) — N bold-white. */
    private static String modulesTail(int total, long start) {
        return buildOk()
                + " for "
                + Theme.colorize(String.valueOf(total), Theme.active().focused())
                + " module"
                + (total == 1 ? "" : "s")
                + " "
                + elapsedSince(start);
    }

    /**
     * Success tail {@code Build successful, <scope> up to date took T} — when nothing was rebuilt.
     */
    private static String upToDateTail(String scope, long start) {
        return buildOk() + ", " + scope + " up to date " + elapsedSince(start);
    }

    /**
     * Single-project success tail: {@code Build successful, project up to date} when nothing was
     * rebuilt, else {@code Build successful. Built <artifact>} naming the headline output. No
     * duration — the framework appends it.
     */
    static String projectTail(Goal goal) {
        if ("up-to-date".equals(goal.get(BUILD_OUTCOME).orElse(""))) {
            return buildOk() + ", project up to date";
        }
        String art = builtArtifact(goal);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /**
     * The headline artifact this build produced, as {@code ". Built <relpath>"} in the path color —
     * the native binary/library if present, else the shadow (fat) jar, else the plain jar. Empty when
     * none exists. Shared with {@code jk native}.
     */
    static String builtArtifact(Goal goal) {
        BuildLayout layout = goal.get(LAYOUT).orElse(null);
        if (layout == null) return "";
        Path art = firstExisting(layout.nativeBinary(), layout.nativeLibrary(), layout.shadowJar(), layout.mainJar());
        return art == null
                ? ""
                : ". Built "
                        + Theme.colorize(
                                relForDisplay(layout.moduleRoot(), art),
                                Theme.active().path());
    }

    private static Path firstExisting(Path... paths) {
        for (Path p : paths) {
            if (p != null && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Pre-computes hard-link destinations for all application module artifacts in a workspace.
     * For each module dir (excluding {@code workspaceRoot} itself) with {@code project.main},
     * maps each candidate artifact path to its link path under {@code workspaceRoot/target/}.
     * When two or more modules produce the same filename the link name is prefixed with the
     * module's group: {@code group-filename}.
     */
    private static Map<Path, Path> computeWorkspaceLinks(Iterable<Path> moduleDirs, Path workspaceRoot) {
        Path wsRoot = workspaceRoot.toAbsolutePath().normalize();
        Map<Path, List<Path>> moduleArtifacts = new LinkedHashMap<>();
        Map<Path, String> moduleGroup = new LinkedHashMap<>();
        for (Path moduleDir : moduleDirs) {
            Path normalDir = moduleDir.toAbsolutePath().normalize();
            if (normalDir.equals(wsRoot)) continue;
            Path buildFile = moduleDir.resolve("jk.toml");
            if (!Files.exists(buildFile)) continue;
            JkBuild build;
            try {
                build = JkBuildParser.parse(buildFile);
            } catch (Exception ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(wsRoot, moduleDir, build);
            if (!layout.hasMain()) continue;
            List<Path> candidates = new ArrayList<>();
            candidates.add(layout.mainJar());
            candidates.add(layout.shadowJar());
            candidates.add(layout.nativeBinary());
            candidates.add(layout.nativeLibrary());
            candidates.add(layout.ociImageTar());
            moduleArtifacts.put(normalDir, candidates);
            moduleGroup.put(normalDir, build.project().group());
        }
        // Count per filename across all modules to detect collisions.
        Map<String, Long> filenameCounts = new java.util.HashMap<>();
        for (List<Path> arts : moduleArtifacts.values()) {
            for (Path art : arts) filenameCounts.merge(art.getFileName().toString(), 1L, Long::sum);
        }
        // Build the final src→linkDest map.
        Path wsTarget = wsRoot.resolve("target");
        Map<Path, Path> links = new LinkedHashMap<>();
        for (var entry : moduleArtifacts.entrySet()) {
            Path normalDir = entry.getKey();
            String group = moduleGroup.get(normalDir);
            for (Path art : entry.getValue()) {
                String filename = art.getFileName().toString();
                String linkName = filenameCounts.getOrDefault(filename, 0L) > 1
                        ? group + "-" + filename : filename;
                links.put(art, wsTarget.resolve(linkName));
            }
        }
        return links;
    }

    /**
     * Hard-links (or copies) any application artifacts that exist under {@code moduleDir} to their
     * pre-computed workspace {@code target/} destinations. Best-effort — failures are swallowed
     * because the build has already succeeded.
     */
    private static void linkModuleArtifacts(Path moduleDir, Map<Path, Path> workspaceLinks) {
        if (workspaceLinks.isEmpty()) return;
        Path normalDir = moduleDir.toAbsolutePath().normalize();
        for (var entry : workspaceLinks.entrySet()) {
            Path src = entry.getKey();
            if (!src.startsWith(normalDir)) continue;
            if (!Files.isRegularFile(src)) continue;
            try {
                dev.jkbuild.cache.Linking.linkOrCopy(src, entry.getValue());
            } catch (java.io.IOException ignored) {
                // best-effort
            }
        }
    }

    private static String relForDisplay(Path base, Path p) {
        try {
            return base.relativize(p).toString().replace(java.io.File.separatorChar, '/');
        } catch (RuntimeException e) {
            return p.getFileName().toString();
        }
    }

    /** Failure tail {@code group:name took T} — coord colored, {@code took T} bright-black. */
    private static String failureTail(String coord, long start) {
        return GoalWedge.coord(coord) + " " + elapsedSince(start);
    }

    /** Failure tail for a module missing its {@code jk.toml}. */
    private static String noTomlTail(String where, long start) {
        return "— no jk.toml in "
                + where
                + " "
                + Theme.colorize(elapsedSince(start), Theme.active().darkGray());
    }

    private static String buildFailedAt(String module, long buildStart) {
        return Theme.colorize("Build failed", Theme.active().error())
                + ": Failure at "
                + Theme.colorize(module, Theme.active().cyan())
                + " "
                + elapsedSince(buildStart);
    }
}
