// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.AggregateContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.TestSummary;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
                Opt.flag("Package an extracted layout + trained JVM startup cache.", "--aot-cache"),
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
    boolean aotCache;
    // ---- GoalKeys -------------------------------------------------------
    //
    // BuildPipeline owns the phase DAG and all of its keys; BuildCommand only
    // reads a few results back out of the finished goal to render its result
    // line. GoalKeys are name-keyed, so these match BuildPipeline's by name.

    private static final GoalKey<String> BUILD_OUTCOME = GoalKey.of("build-outcome", String.class);
    private static final GoalKey<Path> JAR_PATH = GoalKey.of("jar-path", Path.class);
    private static final GoalKey<BuildLayout> LAYOUT = GoalKey.of("layout", BuildLayout.class);
    private static final GoalKey<TestSummary> TEST_RESULT =
            GoalKey.of("test-result", TestSummary.class);

    // ---- Entry point ----------------------------------------------------

    @Override
    public int run(Invocation in) throws Exception {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.aotCache = in.isSet("aot-cache");
        this.noParallel = in.isSet("no-parallel") && !in.isSet("parallel") && !in.isSet("parallel-tests");
        this.global = GlobalOptions.from(in);
        // Opt-in: run modules' tests concurrently. Default serializes them
        // (shared ports/locks/fixtures) — see BuildPipeline's test gate.
        this.parallelTests = in.isSet("parallel-tests");
        dev.jkbuild.config.SessionContext.install(
                dev.jkbuild.config.SessionContext.current().withParallelTests(parallelTests));
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err("jk build: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(startDir));
            return Exit.CONFIG;
        }
        // Peek at the engine's project summary before committing to a per-dir build. A
        // workspace root dispatches to buildWorkspace. A workspace module also redirects —
        // jk build from any module builds the whole workspace in topological order.
        // (The workspace view still threads a client-parsed JkBuild into WorkspaceRequest —
        // that request-shape refactor is thin-client Milestone B.)
        dev.jkbuild.engine.protocol.ProjectInfo peek = projectInfoOrNull(startDir);
        if (peek == null && engineDisabledForTests()) {
            // In-process summary failed → surface the parse error like the old direct parse.
            var raw = dev.jkbuild.cli.engine.InProcessEngine.require().projectInfo(startDir);
            CliOutput.err("jk build: " + raw.error());
            return Exit.CONFIG;
        }
        if (peek != null && peek.workspaceRoot()) {
            if (aotCache) {
                CliOutput.err("jk build: --aot-cache packages a single application project;"
                        + " run it from the module directory.");
                return Exit.USAGE;
            }
            return buildWorkspace(startDir);
        }
        if (peek != null && !peek.workspaceRootDir().isEmpty()
                && !peek.workspaceRootDir().equals(startDir.toString())) {
            Path root = Path.of(peek.workspaceRootDir());
            if (!global.outputIsJson()) {
                CliOutput.err("jk build: building workspace from "
                        + root.getFileName()
                        + " (module: "
                        + startDir.getFileName()
                        + ")");
            }
            return buildWorkspace(root);
        }
        int code = runForDir(startDir);
        if (code == 0 && aotCache) {
            // Post-build tail (like run's exec): extract layout + training run, client-side —
            // the layout inputs come from the engine's exec plan (thin client).
            code = AotCachePackage.run(startDir, cacheDir != null ? cacheDir : JkDirs.cache());
        }
        return code;
    }

    /** Default: parallel graph build; {@code --no-parallel}: the serial rich aggregate view. */
    private int buildWorkspace(Path root) throws Exception {
        // The whole-workspace lock-staleness guard now runs engine-side, inside
        // BuildService.buildWorkspace (the request carries freshenLock=true) — the CLI only renders
        // the failure via the standard workspace-errors path. Both modes drive the one engine
        // planner; --no-parallel just caps module concurrency to 1 (strict serial) via the request
        // the view layer builds below.
        //
        // Thin client: entryBuild never crosses the wire (EngineProtocol.buildRequest serializes
        // only entryDir + flags; the engine re-parses). The parsed model is needed ONLY by the
        // in-process test seam, so parse lazily on that branch alone.
        JkBuild rootBuild = engineDisabledForTests() ? JkBuildParser.parse(root.resolve("jk.toml")) : null;
        return runGraphParallel(root, rootBuild);
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY: routes a workspace build through {@link
     * dev.jkbuild.runtime.BuildService#buildWorkspace} in-process instead of the engine. Set via
     * {@code -Djk.test.noEngine=true} by {@code cli/build.gradle.kts}'s {@code test {}} task —
     * never a user-facing flag, and never read by any production entry point outside this check.
     *
     * <p>Why this exists: a Gradle test JVM has no real {@code jk} binary for {@link
     * dev.jkbuild.cli.engine.EngineClient} to exec as an engine, and the test suite doesn't isolate
     * {@code ~/.jk/state/engine/} per test — building that infrastructure (real subprocess spawn,
     * per-test isolation, cleanup) for a fast unit-test suite is out of scope. The engine transport
     * itself is covered separately: {@code EngineServer}/{@code EngineClient} unit tests, and manual
     * verification against the real native binary (see {@code docs/engine.md}). Every other command
     * (a real {@code jk build} invocation) always goes through the engine, per its "no in-process
     * fallback" design — this bypass is deliberately not reachable any other way.
     *
     * <p>The same bypass applies inside a jk-forked test worker (identified by the {@code
     * jk.plugin.class=JkRunner} property every such JVM carries): under the self-hosted {@code jk
     * build}, cli tests dispatch commands in-process with no Gradle test task to set the property,
     * and routing them through the resident engine would recurse into the very engine hosting the
     * test run — which serves one build at a time, so the nested request deadlocks the build.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    private static final Object OUT_LOCK = new Object();

    /** One unit's build outcome, with its buffered output (flushed together on completion). */

    /**
     * Build the whole composite + workspace graph in parallel (Option B): one build graph,
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
            // --output json / --verbose: buffered, non-animated path. The engine drives the whole
            // workspace build (BuildService.buildWorkspace — resolve graph, memory plan, schedule,
            // run each module's goal); this listener renders the append-only block + [k/N] line.
            return runWorkspaceHeadless(entryDir, entryBuild, cache);
        }

        // Live path (AUTO / QUIET): resolve the graph and run the cache forecast
        // *before* creating the CommandManager so a fully-cached build never
        // flashes the animated spinner. The TUI is created only when there is
        // confirmed work to do; for a cached build we print the chip line directly.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();

        long buildStart = System.nanoTime();
        // Pre-flight forecast — engine-hosted since the Stage 5 dependency cut (the client no
        // longer links the forecaster; the resident engine answers one synchronous round-trip,
        // and its -O3 hashing runs the same stat/CAS lookups faster than this -Os client did).
        // The test-only bypass runs the identical BuildService calls in-process via the seam.
        dev.jkbuild.runtime.BuildForecast forecast;
        try {
            forecast = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require()
                            .forecast(entryDir, entryBuild, cache, buildOpts.skipTests)
                    : dev.jkbuild.cli.engine.EngineClient.forecast(
                            dev.jkbuild.engine.EnginePaths.current(), entryDir, cache, buildOpts.skipTests);
        } catch (java.io.IOException e) {
            CliOutput.err("jk build: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (forecast.hasErrors()) {
            for (String err : forecast.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            CliOutput.err(dev.jkbuild.cli.tui.GoalWedge.failureLine("Build", nerdfont, "dependency resolution failed"));
            return Exit.CONFIG;
        }
        if (forecast.empty()) {
            CliOutput.out(dev.jkbuild.cli.tui.GoalWedge.chipLine(
                    dev.jkbuild.cli.tui.Glyphs.CHECK, "Build", nerdfont, "workspace declares no modules"));
            return 0;
        }
        Set<Path> dirtyDirs = forecast.dirtyDirs();
        if (System.getenv("JK_PERF") != null) {
            System.err.println("[jk-perf] client-forecast " + (System.nanoTime() - buildStart) / 1_000_000 + "ms dirty="
                    + dirtyDirs.size());
        }
        // The forecast runs against the per-module locks; when the merged workspace lock is stale
        // the engine will re-lock (freshenLock on the request) and the forecast may be wrong — so a
        // stale lock disables the fully-cached shortcut AND the dirty hint (the engine re-forecasts
        // after freshening).
        boolean lockStale = forecast.lockStale();
        if (forecast.fullyCached()) {
            // Fully cached — print chip line directly with no spinner ever created.
            CliOutput.out(dev.jkbuild.cli.tui.GoalWedge.chipLine(
                    dev.jkbuild.cli.tui.Glyphs.CHECK, "Build", nerdfont,
                    upToDateTail("all modules", buildStart)));
            return 0;
        }
        // Work confirmed — create the CommandManager now so the spinner starts the instant we know
        // there's something to build. The engine (BuildService.buildWorkspace, invoked by
        // runGraphLive) sizes the memory plan and drives the build; we pass the forecast as a hint.
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Build", animate);
        return runGraphLive(view, entryDir, entryBuild, cache, buildStart, dirtyDirs, lockStale);
    }

    /**
     * Non-animated workspace build: the engine ({@link dev.jkbuild.runtime.BuildService#buildWorkspace})
     * owns the whole loop; this listener renders each module's buffered output block + a ✓/✗ {@code
     * [k/N]} line, then the summary chip — the same append-only output the CLI produced before, now a
     * pure renderer over the engine's events.
     */
    private int runWorkspaceHeadless(Path entryDir, JkBuild entryBuild, Path cache) {
        var request = new dev.jkbuild.runtime.WorkspaceRequest(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers != null ? workers : 1,
                profileName,
                buildOpts.skipTests,
                global.verbose,
                noParallel ? 1 : 0, // --no-parallel → strict serial; else auto/unbounded
                null, // headless: let the engine forecast dirty modules
                true, // single-process CLI: plan our own worker-JVM memory budget
                true); // jk build: auto-freshen a stale workspace lock engine-side
        Map<Path, List<String>> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        int[] total = {0};
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        long start = System.nanoTime();
        dev.jkbuild.runtime.WorkspaceResult result;
        try {
            dev.jkbuild.runtime.WorkspaceBuildListener headlessListener = new dev.jkbuild.runtime.WorkspaceBuildListener() {
                    @Override
                    public void onPlan(List<dev.jkbuild.runtime.ModulePlan> plan) {
                        total[0] = plan.size();
                    }

                    @Override
                    public dev.jkbuild.run.GoalListener onModuleStart(dev.jkbuild.runtime.ModulePlan m) {
                        // Durable run log, same as the old buffered path. Composed into the *returned*
                        // listener (not attached to m.goal() directly) since an engine-hosted module's
                        // goal is a client-side reconstruction that's never run() — only the returned
                        // listener is actually driven by wire-replayed events either way.
                        var log = dev.jkbuild.cli.run.EventLogListener.open(
                                m.cache(), m.goal().name());
                        List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
                        buffers.put(m.dir(), buf);
                        return dev.jkbuild.cli.run.CompositeGoalListener.of(
                                new dev.jkbuild.run.GoalListener() {
                                    @Override
                                    public synchronized void output(String phase, String line) {
                                        buf.add(line);
                                    }

                                    @Override
                                    public synchronized void warn(String phase, String code, String message) {
                                        buf.add("  " + Glyphs.BANG + " " + phase + ": " + message);
                                    }

                                    @Override
                                    public synchronized void error(String phase, String code, String message) {
                                        buf.add("  " + Glyphs.CROSS + " " + phase + ": " + message);
                                    }
                                },
                                log);
                    }

                    @Override
                    public void onModuleFinish(dev.jkbuild.runtime.ModuleOutcome o) {
                        if (global.outputIsJson()) return;
                        List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                        synchronized (OUT_LOCK) {
                            for (String line : buf) CliOutput.out(line);
                            CliOutput.out(
                                    completionLine(o.success(), done.incrementAndGet(), total[0], o.coord(), o.millis()));
                        }
                    }
            };
            result = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require().buildWorkspace(request, headlessListener)
                    : dev.jkbuild.cli.engine.EngineClient.buildWorkspace(
                            dev.jkbuild.engine.EnginePaths.current(), request, headlessListener);
        } catch (java.io.IOException e) {
            CliOutput.err("jk build: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            // exitCode carries the engine's verdict: 2 for graph errors, 6 for an unsatisfiable
            // workspace lock (the freshen guard) — preserved rather than flattened to CONFIG.
            return result.exitCode();
        }
        if (total[0] == 0) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }
        if (!result.success()) {
            result.modules().stream()
                    .filter(m -> !m.success())
                    .findFirst()
                    .ifPresent(f -> CliOutput.err("jk build: " + f.coord() + " failed (exit " + f.exitCode() + ")"));
            return result.exitCode();
        }
        CliOutput.out(GoalWedge.chipLine(
                dev.jkbuild.cli.tui.Glyphs.CHECK, "Build", GlobalConfig.nerdfont(), modulesTail(total[0], start)));
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
            Path entryDir,
            JkBuild entryBuild,
            Path cache,
            long start,
            Set<Path> dirtyDirs,
            boolean lockStale) {
        AggregateContext agg = new AggregateContext(view);
        Map<Path, List<String>> buffers = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> deferredOutput = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        int[] total = {0};
        var request = new dev.jkbuild.runtime.WorkspaceRequest(
                entryDir,
                entryBuild,
                cache,
                jdksDir,
                workers != null ? workers : 1,
                profileName,
                buildOpts.skipTests,
                global.verbose,
                noParallel ? 1 : 0, // --no-parallel → strict serial; else auto/unbounded
                // Reuse the forecast the fully-cached shortcut computed — unless the workspace lock
                // was stale, in which case the engine re-locks first and must re-forecast itself.
                lockStale ? null : dirtyDirs,
                true, // single-process CLI: plan our own worker-JVM memory budget
                true); // jk build: auto-freshen a stale workspace lock engine-side
        dev.jkbuild.runtime.WorkspaceResult result;
        try {
            dev.jkbuild.runtime.WorkspaceBuildListener liveListener = new dev.jkbuild.runtime.WorkspaceBuildListener() {
                    @Override
                    public void onPlan(List<dev.jkbuild.runtime.ModulePlan> plan) {
                        total[0] = plan.size();
                        long tw = 0;
                        for (var p : plan) tw += p.weight();
                        agg.calibrate(tw); // bar calibrated to the whole-graph tick total
                    }

                    @Override
                    public void onEtaEstimate(long millis) {
                        view.setEtaEstimate(millis); // engine computes the schedule-aware estimate; we render it
                    }

                    @Override
                    public dev.jkbuild.run.GoalListener onModuleStart(dev.jkbuild.runtime.ModulePlan m) {
                        // Composed into the returned listener, not attached to m.goal() directly — see
                        // the headless path's onModuleStart above for why.
                        var log = dev.jkbuild.cli.run.EventLogListener.open(m.cache(), m.goal().name());
                        List<String> buf = java.util.Collections.synchronizedList(new ArrayList<>());
                        buffers.put(m.dir(), buf);
                        // The module's goal feeds the shared aggregate bar; its output buffers for
                        // ordered flush (parallel modules' logs never interleave).
                        var lis = new dev.jkbuild.cli.run.AggregateModuleListener(
                                agg, m.coord(), m.goal().phases(), m.weight());
                        lis.bufferOutputInto(buf);
                        return dev.jkbuild.cli.run.CompositeGoalListener.of(lis, log);
                    }

                    @Override
                    public void onModuleFinish(dev.jkbuild.runtime.ModuleOutcome o) {
                        List<String> buf = buffers.getOrDefault(o.dir(), List.of());
                        String completion =
                                completionLine(o.success(), completed.incrementAndGet(), total[0], o.coord(), o.millis());
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
                    }
            };
            result = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require().buildWorkspace(request, liveListener)
                    : dev.jkbuild.cli.engine.EngineClient.buildWorkspace(
                            dev.jkbuild.engine.EnginePaths.current(), request, liveListener);
        } catch (java.io.IOException e) {
            // finishGoalFailure's own `tail` already gets wrapped in GoalWedge.failureLine(goalName(),
            // nerdfont, tail) internally — pass the plain message, not a pre-rendered failure line
            // (passing one double-wraps it into a garbled "‼ Build ‼ Build ..." chip).
            view.finishGoalFailure(String.valueOf(e.getMessage()), List.of());
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            List<String> above = new ArrayList<>();
            for (String err : result.errors()) above.add(ConsoleSpec.errorLine("composite", err));
            view.finishGoalFailure("dependency resolution failed", above);
            // 2 for graph errors, 6 for an unsatisfiable workspace lock (the engine's freshen guard).
            return result.exitCode();
        }
        if (!result.success()) {
            // Buffered sub-process output first, then the error diagnostics just above the
            // "‼ Build failed" line — which stays last so the outcome is visible without scrolling.
            List<String> above = snapshot(deferredOutput);
            for (GoalResult.Diagnostic d : agg.lastErrors()) {
                if ("test-failure".equals(d.code())) continue; // already printed by run-tests
                above.add(ConsoleSpec.renderError(d));
            }
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(dev.jkbuild.runtime.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishGoalFailure(failureTail(failedCoord, start), above);
            return result.exitCode();
        }
        view.finishGoalSuccess(
                dirtyDirs.isEmpty() ? upToDateTail("all modules", start) : modulesTail(total[0], start),
                snapshot(deferredOutput));
        return 0;
    }

    /** Print buffered unit output below the (settled) live region, in completion order. */
    /** Stable copy of the concurrently-appended deferred-output buffer. */
    private static List<String> snapshot(List<String> deferred) {
        synchronized (deferred) {
            return new ArrayList<>(deferred);
        }
    }

    /** Build one graph unit with output buffered. */
    /** Test failures exit 4; everything else exits 1 (mirrors {@link #runPrepared}). */
    private static int exitCodeFor(Goal goal) {
        var testResult = goal.get(TEST_RESULT).orElse(null);
        return testResult != null && !testResult.allPassed() ? 4 : 1;
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
        String mark = Theme.colorize(ok ? Glyphs.CHECK : Glyphs.CROSS, ok ? th.success() : th.error());
        StringBuilder sb = new StringBuilder();
        sb.append(mark)
                .append(' ')
                .append(ConsoleSpec.countBracket(index, total, th))
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
     * Build one (non-workspace) project directory. Engine-hosted since the slim-client migration:
     * a synchronous forecast round-trip backs the fully-cached "project up to date" shortcut (the
     * client no longer links the forecaster — Stage 5), then the build itself streams over {@link
     * dev.jkbuild.cli.engine.EngineClient#runSingleBuild}. The test-only bypass runs the identical
     * pipeline in-process through the {@link dev.jkbuild.cli.engine.InProcessEngine} seam.
     */
    private int runForDir(Path dir) throws Exception {
        long startNanos = System.nanoTime(); // captured before the forecast so timing includes it
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err("jk build: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        if (engineDisabledForTests()) {
            return dev.jkbuild.cli.engine.InProcessEngine.require()
                    .buildProjectInProcess(dir, cache, jdksDir, workers != null ? workers : 1, profileName,
                            buildOpts.skipTests, global, startNanos);
        }
        try {
            dir = dir.toRealPath();
        } catch (java.io.IOException ignored) {
        }
        String target = buildTarget(buildFile, dir);

        // Single-module fast path: skip the TUI entirely when the engine's forecast says every
        // work phase is already cached (stat/CAS lookups engine-side, one round trip here).
        if (GoalConsole.isInteractiveTerminal() && !global.outputIsJson()) {
            try {
                var forecast = dev.jkbuild.cli.engine.EngineClient.forecast(
                        dev.jkbuild.engine.EnginePaths.current(), dir, cache, buildOpts.skipTests);
                if (!forecast.hasErrors() && !forecast.empty() && forecast.fullyCached()) {
                    CliOutput.out(dev.jkbuild.cli.tui.GoalWedge.chipLine(
                            dev.jkbuild.cli.tui.Glyphs.CHECK,
                            "Build",
                            dev.jkbuild.config.GlobalConfig.nerdfont(),
                            buildOk() + ", project up to date " + elapsedSince(startNanos)));
                    return 0;
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // best-effort shortcut — fall through to the real build
            }
        }

        // The wire has no real Goal to read BUILD_OUTCOME/LAYOUT from ahead of time (they arrive
        // on the terminal goal-finish event), so projectTail's ingredients are supplied two ways:
        // BUILD_OUTCOME rides the wire (only the engine, which actually ran the goal, knows it);
        // LAYOUT is reconstructed independently — it's a pure derivation from dir + the parsed
        // jk.toml, both of which the client already has, and the artifact file it points at lives
        // on the same local filesystem the engine just built into. The engine does the
        // calibration-refine + cache-prune itself on success (it measured the work).
        TestSummary[] testResultHolder = new TestSummary[1];
        String[] buildOutcomeHolder = new String[1];
        dev.jkbuild.engine.protocol.ProjectInfo tailInfo = projectInfoOrNull(dir);
        final Path tailDir = dir;
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> projectTail(buildOutcomeHolder[0], tailDir, tailInfo),
                r -> GoalWedge.coord(target),
                true);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        try {
            result = dev.jkbuild.cli.engine.EngineClient.runSingleBuild(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new dev.jkbuild.cli.engine.EngineClient.SingleBuildRequest(
                            dir,
                            cache,
                            jdksDir,
                            workers != null ? workers : 1,
                            profileName,
                            buildOpts.skipTests,
                            global.verbose,
                            dev.jkbuild.config.SessionContext.current().offline(),
                            dev.jkbuild.config.SessionContext.current().force()),
                    phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, target),
                    testResultHolder,
                    buildOutcomeHolder);
        } catch (java.io.IOException e) {
            CliOutput.err("jk build: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (result.success()) return 0;
        // Test failures get exit 4; other failures exit 1.
        TestSummary testResult = testResultHolder[0];
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // ---- success summary -----------------------------------------------

    /** Header module label for the goal view: the project's {@code group:artifact}. */
    static String buildTarget(Path buildFile, Path dir) {
        var info = projectInfoOrNull(dir);
        if (info != null) return info.coord();
        return dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    /**
     * The engine's parsed-project summary, or {@code null} when it can't be had (engine
     * unreachable, no jk.toml, parse error) — thin-client replacement for client-side peeks.
     * In-process twin under jk.test.noEngine.
     */
    static dev.jkbuild.engine.protocol.ProjectInfo projectInfoOrNull(Path dir) {
        try {
            dev.jkbuild.engine.protocol.ProjectInfo info = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require().projectInfo(dir)
                    : dev.jkbuild.cli.engine.EngineClient.projectInfo(
                            dev.jkbuild.engine.EnginePaths.current(), dir);
            return info.error() != null ? null : info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dim italic {@code "took Xms"} from a wall-clock start captured with {@link System#nanoTime()}.
     */
    static String elapsedSince(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        return dev.jkbuild.cli.run.ConsoleSpec.took(java.time.Duration.ofMillis(ms));
    }

    /** The green {@code Build successful} lead that opens every build success message. */
    static String buildOk() {
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
        return projectTail(goal.get(BUILD_OUTCOME).orElse(""), goal.get(LAYOUT).orElse(null));
    }

    /**
     * As {@link #projectTail(Goal)}, but from already-resolved values instead of a live {@code Goal}
     * — for an engine-hosted build, where there's no local {@code Goal} to read {@code BUILD_OUTCOME}/
     * {@code LAYOUT} off of (they arrive over the wire / get reconstructed independently instead; see
     * {@code EngineClient.runSingleBuild}).
     */
    static String projectTail(String buildOutcome, BuildLayout layout) {
        if ("up-to-date".equals(buildOutcome)) {
            return buildOk() + ", project up to date";
        }
        String art = builtArtifact(layout);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /** As above, from the engine's project summary (thin-client path — no client-side layout). */
    static String projectTail(String buildOutcome, Path moduleRoot, dev.jkbuild.engine.protocol.ProjectInfo info) {
        if ("up-to-date".equals(buildOutcome)) {
            return buildOk() + ", project up to date";
        }
        String art = info == null ? "" : builtArtifact(moduleRoot, info);
        return buildOk() + (art.isEmpty() ? ", project built" : art);
    }

    /** The headline artifact from ProjectInfo's candidate paths (native > shadow > jar). */
    static String builtArtifact(Path moduleRoot, dev.jkbuild.engine.protocol.ProjectInfo info) {
        for (String candidate : java.util.List.of(
                info.nativeBinPath(), info.nativeLibPath(), info.shadowJarPath(), info.mainJarPath())) {
            if (candidate.isEmpty()) continue;
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return ". Built " + Theme.colorize(relForDisplay(moduleRoot, p), Theme.active().path());
            }
        }
        return "";
    }

    /**
     * The headline artifact this build produced, as {@code ". Built <relpath>"} in the path color —
     * the native binary/library if present, else the shadow (fat) jar, else the plain jar. Empty when
     * none exists. Shared with {@code jk native}.
     */
    static String builtArtifact(Goal goal) {
        return builtArtifact(goal.get(LAYOUT).orElse(null));
    }

    /** As {@link #builtArtifact(Goal)}, from an already-resolved {@link BuildLayout} (or {@code null}). */
    static String builtArtifact(BuildLayout layout) {
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

    /**
     * Hard-links (or copies) any application artifacts that exist under {@code moduleDir} to their
     * pre-computed workspace {@code target/} destinations. Best-effort — failures are swallowed
     * because the build has already succeeded.
     */

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

}
