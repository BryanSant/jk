// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.runtime.NativeGoals;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk native} — build a GraalVM-compiled native artifact for the project, from source. Runs
 * the full {@linkplain BuildPipeline build pipeline} (compile → test → package) and then composes
 * the native-image tail onto the <em>same</em> goal — an executable when a main class resolves,
 * else a shared library ({@code --shared}).
 *
 * <p>Native builds are opt-in: only modules with {@code native = true} are compiled to a native
 * artifact. Other modules are still compiled and packaged (so eligible modules can depend on them)
 * but produce no native output. The GraalVM that owns {@code native-image} is chosen by {@link
 * dev.jkbuild.cli.GraalResolver} (honoring {@code project.graal}), resolved up front before the
 * progress UI — and, since slim-client Wave 3, before the request ships to the resident engine,
 * which hosts the build cascade (the {@code native-image} child process forks engine-side) while
 * this command renders the streamed events.
 */
public final class NativeCommand implements CliCommand {

    @Override
    public String name() {
        return "native";
    }

    @Override
    public String description() {
        return "Build a native binary with GraalVM";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Main class. Default: jk.toml image.main or project.main.", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Install Oracle GraalVM if native-image is missing.", "--yes", "-y"),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"));
    }

    @Override
    public List<dev.jkbuild.model.command.Param> parameters() {
        return List.of(dev.jkbuild.model.command.Param.of(
                "native-image-args",
                dev.jkbuild.model.command.Arity.ZERO_OR_MORE,
                "Extra arguments forwarded to\nnative-image (after --)."));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    boolean assumeYes;
    List<String> extra = new ArrayList<>();
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;
    dev.jkbuild.cli.GraalResolver graal;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale; every real {@code jk native}
     * invocation goes through the engine (slim-client Wave 3).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=JkRunner) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.assumeYes = in.isSet("yes");
        this.extra = in.positionals();
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.graal = new dev.jkbuild.cli.GraalResolver(jdksDir, assumeYes);

        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            CliOutput.err("jk native: " + dev.jkbuild.cli.PathDisplay.styledRaw(buildFile) + " not found.");
            return Exit.NO_INPUT;
        }

        JkBuild peek = JkBuildParser.parse(buildFile);

        // Workspace root: cascade to all eligible modules.
        if (peek.isWorkspaceRoot()) {
            return runWorkspaceNative(startDir, peek, cache);
        }

        // Module redirect: if we're inside a workspace, build from the root.
        var rootOpt = WorkspaceLocator.findRoot(startDir);
        if (rootOpt.isPresent() && !rootOpt.get().equals(startDir)) {
            Path wsRoot = rootOpt.get();
            JkBuild rootBuild = JkBuildParser.parse(wsRoot.resolve("jk.toml"));
            if (rootBuild.isWorkspaceRoot()) {
                CliOutput.err("jk native: building from workspace root "
                        + wsRoot.getFileName()
                        + " (module: "
                        + startDir.getFileName()
                        + ")");
                return runWorkspaceNative(wsRoot, rootBuild, cache);
            }
        }

        // Single project.
        return runSingleProject(startDir, buildFile, cache);
    }

    /** The engine request for {@code entryDir}, with the client-resolved GraalVM homes attached. */
    private dev.jkbuild.cli.engine.EngineClient.NativeRequest hostedRequest(
            Path entryDir, Path cache, Map<Path, Path> graalHomes) {
        var session = dev.jkbuild.config.SessionContext.current();
        return new dev.jkbuild.cli.engine.EngineClient.NativeRequest(
                entryDir,
                cache,
                jdksDir,
                mainClass,
                buildOpts.skipTests,
                session.offline(),
                session.force(),
                global.verbose,
                extra,
                graalHomes);
    }

    // --- workspace cascade ---------------------------------------------------

    private int runWorkspaceNative(Path wsRoot, JkBuild root, Path cache) throws Exception {
        Map<Path, JkBuild> modulesByDir;
        try {
            modulesByDir = WorkspaceLoader.loadModules(wsRoot, root);
        } catch (RuntimeException e) {
            CliOutput.err("jk native: " + e.getMessage());
            return Exit.CONFIG;
        }
        if (modulesByDir.isEmpty()) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }

        List<Path> sorted = BuildGraph.orderModules(modulesByDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        long buildStart = System.nanoTime();

        // Resolve the GraalVM for every native-eligible module up front — BEFORE
        // any progress UI opens (a prompt or install would corrupt the captured
        // display) and BEFORE the request ships to the engine (the prompt/install
        // owns this terminal; the engine only ever runs a resolved toolchain).
        // GraalResolver memoizes by spec, so a shared graal pin fetches/installs
        // at most once.
        Map<Path, Path> graalHomes = new java.util.HashMap<>();
        for (Path moduleDir : sorted) {
            JkBuild module = modulesByDir.get(moduleDir);
            if (!NativeGoals.isNativeEligible(module)) continue;
            Optional<Path> home = graal.resolve(moduleDir, module.project().graal());
            if (home.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why
            graalHomes.put(moduleDir, home.get());
        }

        long nativeCount = sorted.stream()
                .map(modulesByDir::get)
                .filter(NativeGoals::isNativeEligible)
                .count();

        if (engineDisabledForTests()) {
            return runWorkspaceInProcess(wsRoot, modulesByDir, sorted, cache, graalHomes, mode, buildStart, nativeCount);
        }
        return runWorkspaceHosted(wsRoot, cache, graalHomes, mode, buildStart, sorted.size(), nativeCount);
    }

    /**
     * Engine-hosted workspace cascade: the engine assembles and runs each module's goal (the
     * {@code native-image} child forks engine-side) and streams the workspace event vocabulary
     * back; this method only renders. Exit codes arrive engine-computed.
     */
    private int runWorkspaceHosted(
            Path wsRoot, Path cache, Map<Path, Path> graalHomes, GoalConsole.Mode mode, long buildStart,
            int totalModules, long nativeCount) {
        var req = hostedRequest(wsRoot, cache, graalHomes);
        var paths = dev.jkbuild.engine.EnginePaths.current();

        // JSON / verbose: per-module banners, append-only per-module listeners.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            int[] idx = {0};
            var listener = new dev.jkbuild.runtime.WorkspaceBuildListener() {
                @Override
                public dev.jkbuild.run.GoalListener onModuleStart(dev.jkbuild.runtime.BuildService.ModulePlan m) {
                    CliOutput.out();
                    CliOutput.out("══ " + wsRoot.relativize(m.dir()) + " (" + (++idx[0]) + "/" + totalModules
                            + ") ══");
                    var log = dev.jkbuild.cli.run.EventLogListener.open(m.cache(), m.goal().name());
                    return dev.jkbuild.cli.run.CompositeGoalListener.of(
                            GoalConsole.chooseConsoleListener(m.goal().name(), m.goal().phases(), mode), log);
                }

                @Override
                public void onModuleFinish(dev.jkbuild.runtime.BuildService.ModuleOutcome o) {
                    if (!o.success()) {
                        CliOutput.err(
                                "jk native: " + wsRoot.relativize(o.dir()) + " failed (exit " + o.exitCode() + ")");
                    }
                }
            };
            dev.jkbuild.runtime.BuildService.WorkspaceResult result;
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runNative(paths, req, listener);
            } catch (IOException e) {
                CliOutput.err("jk native: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            for (String err : result.errors()) CliOutput.err("jk native: " + err);
            return result.exitCode();
        }

        // AUTO / QUIET: one shared aggregate view, calibrated to the whole cascade up front
        // (the plan burst carries every module goal's estimated weight).
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Build", animate);
        dev.jkbuild.cli.run.AggregateContext agg = new dev.jkbuild.cli.run.AggregateContext(view);
        int[] built = {0};
        var listener = new dev.jkbuild.runtime.WorkspaceBuildListener() {
            @Override
            public void onPlan(List<dev.jkbuild.runtime.BuildService.ModulePlan> plan) {
                long total = 0;
                for (var p : plan) total += p.weight();
                agg.calibrate(total);
            }

            @Override
            public dev.jkbuild.run.GoalListener onModuleStart(dev.jkbuild.runtime.BuildService.ModulePlan m) {
                var log = dev.jkbuild.cli.run.EventLogListener.open(m.cache(), m.goal().name());
                return dev.jkbuild.cli.run.CompositeGoalListener.of(
                        new dev.jkbuild.cli.run.AggregateModuleListener(
                                agg, m.coord(), m.goal().phases(), m.weight()),
                        log);
            }

            @Override
            public void onModuleFinish(dev.jkbuild.runtime.BuildService.ModuleOutcome o) {
                if (o.success()) built[0]++;
            }
        };
        dev.jkbuild.runtime.BuildService.WorkspaceResult result;
        try {
            result = dev.jkbuild.cli.engine.EngineClient.runNative(paths, req, listener);
        } catch (IOException e) {
            view.finishGoalFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            view.finishGoalFailure("dependency resolution failed");
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return result.exitCode();
        }
        if (!result.success()) {
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(dev.jkbuild.runtime.BuildService.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishGoalFailure(GoalWedge.coord(failedCoord) + " " + BuildCommand.elapsedSince(buildStart));
            for (GoalResult.Diagnostic d : agg.lastErrors()) {
                CliOutput.err(ConsoleSpec.renderError(d));
            }
            return result.exitCode();
        }
        view.finishGoalSuccess(Theme.colorize("Native build successful", Theme.active().success())
                + ", "
                + workspaceSummary(built[0], nativeCount)
                + " "
                + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /** In-process workspace cascade — the test-only bypass; builds the exact same goals via {@link NativeGoals}. */
    private int runWorkspaceInProcess(
            Path wsRoot,
            Map<Path, JkBuild> modulesByDir,
            List<Path> sorted,
            Path cache,
            Map<Path, Path> graalHomes,
            GoalConsole.Mode mode,
            long buildStart,
            long nativeCount)
            throws Exception {
        // JSON / verbose: per-module banners.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path moduleDir = sorted.get(i);
                JkBuild module = modulesByDir.get(moduleDir);
                CliOutput.out();
                CliOutput.out(
                        "══ " + wsRoot.relativize(moduleDir) + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runPreparedNative(
                        prepareNativeModule(moduleDir, module, cache, graalHomes.get(moduleDir)), null);
                if (exit != 0) {
                    CliOutput.err("jk native: " + wsRoot.relativize(moduleDir) + " failed (exit " + exit + ")");
                    return exit;
                }
            }
            return 0;
        }

        // AUTO / QUIET: one shared aggregate view.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Build", animate);
        dev.jkbuild.cli.run.AggregateContext agg = new dev.jkbuild.cli.run.AggregateContext(view);
        int built = 0;

        try (var cap = view.captureOutput()) {
            // Pre-scan every module's goal — the core phases plus the
            // native-image tail for eligible modules — and sum its estimated
            // ticks so the bar calibrates to the whole-workspace total up front
            // (the native-image build's W_NATIVE weight and its per-stage scope
            // included) and advances 0→100% without resetting per module,
            // instead of the denominator growing as modules start. These are the
            // very goals we then run, one module at a time.
            List<PreparedNativeModule> prepared = new ArrayList<>(sorted.size());
            long total = 0;
            for (Path moduleDir : sorted) {
                PreparedNativeModule pm =
                        prepareNativeModule(moduleDir, modulesByDir.get(moduleDir), cache, graalHomes.get(moduleDir));
                total += pm.barWeight();
                prepared.add(pm);
            }
            agg.calibrate(total);

            for (PreparedNativeModule pm : prepared) {
                String moduleName = wsRoot.relativize(pm.dir()).toString();
                int exit;
                try {
                    exit = runPreparedNative(pm, agg);
                } catch (Exception e) {
                    view.finishGoalFailure(GoalWedge.coord(moduleName) + " " + BuildCommand.elapsedSince(buildStart));
                    throw e;
                }
                if (exit != 0) {
                    view.finishGoalFailure(GoalWedge.coord(moduleName) + " " + BuildCommand.elapsedSince(buildStart));
                    for (GoalResult.Diagnostic d : agg.lastErrors()) {
                        CliOutput.err(ConsoleSpec.renderError(d));
                    }
                    return exit;
                }
                built++;
            }
        }

        view.finishGoalSuccess(Theme.colorize("Native build successful", Theme.active().success())
                + ", "
                + workspaceSummary(built, nativeCount)
                + " "
                + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /** Success-summary tail shared by the hosted and in-process workspace paths. */
    private static String workspaceSummary(int built, long nativeCount) {
        return built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
    }

    /**
     * Construct (but do not run) one module's goal via the shared {@link NativeGoals} factory (the
     * same assembly the engine hosts). Split out of the run step so the workspace path can build
     * every module's goal up front and sum {@link Goal#estimatedTotalWeight()} — including the
     * native phase's weight — to calibrate the shared progress bar before any module runs.
     */
    private PreparedNativeModule prepareNativeModule(Path moduleDir, JkBuild module, Path cache, Path graalHome) {
        Goal goal = NativeGoals.moduleGoal(
                moduleDir, module, cache, jdksDir, graalHome, mainClass, extra, buildOpts.skipTests, global.verbose);
        return new PreparedNativeModule(
                moduleDir,
                BuildCommand.buildTarget(moduleDir.resolve("jk.toml"), moduleDir),
                cache,
                goal,
                goal.estimatedTotalWeight(),
                NativeGoals.isNativeEligible(module));
    }

    /**
     * Run an already-built module goal and map its result to an exit code. When {@code agg} is
     * non-null the module feeds the one shared calibrated bar, scaling its progress into its reserved
     * slice; otherwise it renders on its own (the verbose/JSON per-module path).
     */
    private int runPreparedNative(PreparedNativeModule pm, dev.jkbuild.cli.run.AggregateContext agg) {
        GoalResult result;
        if (agg != null) {
            result = GoalConsole.runGoalInto(pm.goal(), pm.cache(), pm.target(), agg, pm.barWeight());
        } else {
            String verb = pm.eligible() ? "Native build successful" : "Build successful";
            ConsoleSpec spec = new ConsoleSpec(
                    "Build",
                    r -> Theme.colorize(verb, Theme.active().success()) + BuildCommand.builtArtifact(pm.goal()),
                    r -> GoalWedge.coord(pm.target()),
                    true);
            result = GoalConsole.runGoal(pm.goal(), GoalConsole.modeFor(global), pm.cache(), spec, pm.target());
        }
        return result.success() ? 0 : 1;
    }

    /**
     * A workspace module's goal, built and ready to run, paired with its pre-scan bar weight (its
     * slice of the calibrated aggregate total) and whether it carries the native-image tail.
     */
    private record PreparedNativeModule(
            Path dir, String target, Path cache, Goal goal, long barWeight, boolean eligible) {}

    // --- single-project (unchanged behaviour) --------------------------------

    private int runSingleProject(Path projectDir, Path buildFile, Path cache) throws IOException, InterruptedException {
        // Native builds are opt-in: require native = true (ALWAYS). Absent or
        // native = false → not eligible, even for an explicit `jk native`.
        JkBuild build = JkBuildParser.parse(buildFile);
        if (build.project().nativeMode() != JkBuild.NativeMode.ALWAYS) {
            CliOutput.err("jk native: "
                    + projectDir.getFileName()
                    + " is not native-eligible — set `native = true` under [project] to enable.");
            return Exit.CONFIG;
        }

        // Resolve GraalVM before the goal/progress UI starts (a prompt/install
        // can't run inside the captured-output region, and must never run inside
        // the engine — see docs/engine.md).
        Optional<Path> graalHome = graal.resolve(projectDir, build.project().graal());
        if (graalHome.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why

        String coord = BuildCommand.buildTarget(buildFile, projectDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);

        if (engineDisabledForTests()) {
            Goal goal = NativeGoals.moduleGoal(
                    projectDir,
                    build,
                    cache,
                    jdksDir,
                    graalHome.get(),
                    mainClass,
                    extra,
                    buildOpts.skipTests,
                    global.verbose);
            ConsoleSpec spec = new ConsoleSpec(
                    "Build",
                    r -> Theme.colorize("Native build successful", Theme.active().success())
                            + BuildCommand.builtArtifact(goal),
                    r -> GoalWedge.coord(coord),
                    true);
            GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, coord);
            return result.success() ? 0 : NativeGoals.failureExitCode(goal, result);
        }

        // Engine-hosted (a cascade of one): the wire has no real Goal to read LAYOUT off of, so
        // the success tail reconstructs it — a pure derivation from dir + the parsed jk.toml, and
        // the artifact it names lives on the same local filesystem the engine just built into.
        BuildLayout layout;
        try {
            layout = BuildLayout.of(projectDir, build);
        } catch (RuntimeException e) {
            layout = null; // best-effort — the tail degrades to the plain verb
        }
        BuildLayout finalLayout = layout;
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(finalLayout),
                r -> GoalWedge.coord(coord),
                true);
        var listener = new dev.jkbuild.runtime.WorkspaceBuildListener() {
            @Override
            public dev.jkbuild.run.GoalListener onModuleStart(dev.jkbuild.runtime.BuildService.ModulePlan m) {
                var log = dev.jkbuild.cli.run.EventLogListener.open(m.cache(), m.goal().name());
                return dev.jkbuild.cli.run.CompositeGoalListener.of(
                        GoalConsole.chooseConsoleListener(m.goal().phases(), mode, spec, coord), log);
            }
        };
        dev.jkbuild.runtime.BuildService.WorkspaceResult result;
        try {
            result = dev.jkbuild.cli.engine.EngineClient.runNative(
                    dev.jkbuild.engine.EnginePaths.current(),
                    hostedRequest(projectDir, cache, Map.of(projectDir, graalHome.get())),
                    listener);
        } catch (IOException e) {
            CliOutput.err("jk native: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : result.errors()) CliOutput.err("jk native: " + err);
        return result.exitCode();
    }
}
