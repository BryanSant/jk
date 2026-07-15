// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.engine.EngineClient;
import build.jumpkick.cli.engine.InProcessEngine;
import build.jumpkick.cli.run.CompositePipelineListener;
import build.jumpkick.cli.run.EventLogListener;
import build.jumpkick.engine.EnginePaths;
import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.run.ConsoleSpec;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.CommandManager;
import build.jumpkick.cli.tui.PipelineWedge;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.layout.BuildLayout;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.config.ModuleOrder;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk native} — build a GraalVM-compiled native artifact for the project, from source. Runs
 * the full {@linkplain BuildPipelines build pipeline} (compile → test → package) and then composes
 * the native-image tail onto the <em>same</em> pipeline — an executable when a main class resolves,
 * else a shared library ({@code --shared}).
 *
 * <p>Native builds are opt-in: only modules with {@code native = true} are compiled to a native
 * artifact. Other modules are still compiled and packaged (so eligible modules can depend on them)
 * but produce no native output. The GraalVM that owns {@code native-image} is chosen by {@link
 * build.jumpkick.cli.GraalResolver} (honoring {@code project.graal}), resolved up front before the
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
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<class>", "Main class. Default: jk.toml image.main or project.main.", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide(),
                Opt.flag("Install Oracle GraalVM if native-image is missing.", "--yes", "-y"),
                Opt.flag("Skip compiling and running tests.", "--skip-tests")));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public List<build.jumpkick.model.command.Param> parameters() {
        return List.of(build.jumpkick.model.command.Param.of(
                "native-image-args",
                build.jumpkick.model.command.Arity.ZERO_OR_MORE,
                "Extra arguments forwarded to\nnative-image (after --)."));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    boolean assumeYes;
    List<String> extra = new ArrayList<>();
    build.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;
    build.jumpkick.cli.GraalResolver graal;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()} for the full rationale; every real {@code jk native}
     * invocation goes through the engine (slim-client Wave 3).
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=TestRunnerPlugin) — see BuildCommand.
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunnerPlugin".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.assumeYes = in.isSet("yes");
        this.extra = in.positionals();
        this.buildOpts = new build.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.graal = new build.jumpkick.cli.GraalResolver(jdksDir, assumeYes);

        Path startDir = global.workingDir();
        VariantSelection.install(in, startDir);
        Path buildFile = startDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            CliOutput.err("jk native: " + build.jumpkick.cli.PathDisplay.styledRaw(buildFile) + " not found.");
            return Exit.NO_INPUT;
        }

        build.jumpkick.engine.protocol.ProjectInfo peek = BuildCommand.projectInfoOrNull(startDir);

        // Workspace root: cascade to all eligible modules.
        if (peek != null && peek.workspaceRoot()) {
            return runWorkspaceNative(startDir, cache);
        }

        // Module redirect: if we're inside a workspace, build from the root.
        if (peek != null
                && !peek.workspaceRootDir().isEmpty()
                && !peek.workspaceRootDir().equals(startDir.toString())) {
            Path wsRoot = Path.of(peek.workspaceRootDir());
            {
                CliOutput.err("jk native: building from workspace root "
                        + wsRoot.getFileName()
                        + " (module: "
                        + startDir.getFileName()
                        + ")");
                return runWorkspaceNative(wsRoot, cache);
            }
        }

        // Single project.
        return runSingleProject(startDir, buildFile, cache);
    }

    /**
     * Native builds are opt-in ({@code native = true} → ALWAYS) — the same rule as the engine's
     * {@code NativePipelines.isNativeEligible} (a one-line enum check on the shared model).
     */
    static boolean nativeEligible(JkBuild build) {
        return build.nativeMode() == JkBuild.NativeMode.ALWAYS;
    }

    /** The engine request for {@code entryDir}, with the client-resolved GraalVM homes attached. */
    private EngineClient.NativeRequest hostedRequest(
            Path entryDir, Path cache, Map<Path, Path> graalHomes) {
        var session = build.jumpkick.config.SessionContext.current();
        return new EngineClient.NativeRequest(
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

    private int runWorkspaceNative(Path wsRoot, Path cache) throws Exception {
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        long buildStart = System.nanoTime();

        if (engineDisabledForTests()) {
            // In-process seam: the JVM test dist links the parser; load the full workspace
            // model exactly as before. The native client never reaches this branch.
            Map<Path, JkBuild> modulesByDir;
            try {
                // Behind the reflective seam: a direct WorkspaceLoader call would keep tomlj
                // statically reachable in the native image even though this branch never runs there.
                modulesByDir = InProcessEngine.require().loadWorkspaceModules(wsRoot);
            } catch (RuntimeException e) {
                CliOutput.err("jk native: " + e.getMessage());
                return Exit.CONFIG;
            }
            if (modulesByDir.isEmpty()) {
                CliOutput.out("(workspace declares no modules)");
                return 0;
            }
            List<Path> sorted = ModuleOrder.orderModules(modulesByDir);
            Map<Path, Path> graalHomes = new java.util.HashMap<>();
            for (Path moduleDir : sorted) {
                JkBuild module = modulesByDir.get(moduleDir);
                if (!nativeEligible(module)) continue;
                Optional<Path> home = graal.resolve(moduleDir, module.graal());
                if (home.isEmpty()) return Exit.CONFIG;
                graalHomes.put(moduleDir, home.get());
            }
            long nativeCount = sorted.stream()
                    .map(modulesByDir::get)
                    .filter(NativeCommand::nativeEligible)
                    .count();
            return InProcessEngine.require()
                    .nativeWorkspaceInProcess(wsRoot, modulesByDir, sorted, cache, graalHomes, mode, buildStart,
                            nativeCount, jdksDir, mainClass, extra, buildOpts.skipTests, global.verbose);
        }

        // Thin client: per-module native-mode + graal spec ride ProjectInfo summaries; the
        // engine owns ordering/scheduling. The GraalVM pre-resolve stays HERE — a prompt or
        // install owns this terminal and must never run inside the engine.
        var rootInfo = BuildCommand.projectInfoOrNull(wsRoot);
        if (rootInfo == null) {
            CliOutput.err("jk native: could not read the workspace summary at " + wsRoot);
            return Exit.CONFIG;
        }
        if (rootInfo.moduleDirs().isEmpty()) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }
        Map<Path, Path> graalHomes = new java.util.HashMap<>();
        long nativeCount = 0;
        for (String rel : rootInfo.moduleDirs()) {
            Path moduleDir = wsRoot.resolve(rel);
            var info = BuildCommand.projectInfoOrNull(moduleDir);
            if (info == null || !"ALWAYS".equals(info.nativeMode())) continue;
            nativeCount++;
            Optional<Path> home =
                    graal.resolve(moduleDir, info.graal().isEmpty() ? null : info.graal());
            if (home.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why
            graalHomes.put(moduleDir, home.get());
        }
        return runWorkspaceHosted(
                wsRoot, cache, graalHomes, mode, buildStart, rootInfo.moduleDirs().size(), nativeCount);
    }

    /**
     * Engine-hosted workspace cascade: the engine assembles and runs each module's pipeline (the
     * {@code native-image} child forks engine-side) and streams the workspace event vocabulary
     * back; this method only renders. Exit codes arrive engine-computed.
     */
    private int runWorkspaceHosted(
            Path wsRoot, Path cache, Map<Path, Path> graalHomes, PipelineConsole.Mode mode, long buildStart,
            int totalModules, long nativeCount) {
        var req = hostedRequest(wsRoot, cache, graalHomes);
        var paths = EnginePaths.current();

        // JSON / verbose: per-module banners, append-only per-module listeners.
        if (mode != PipelineConsole.Mode.AUTO && mode != PipelineConsole.Mode.QUIET) {
            int[] idx = {0};
            var listener = new build.jumpkick.runtime.WorkspaceBuildListener() {
                @Override
                public build.jumpkick.run.PipelineListener onModuleStart(build.jumpkick.runtime.ModulePlan m) {
                    CliOutput.out();
                    CliOutput.out("══ " + wsRoot.relativize(m.dir()) + " (" + (++idx[0]) + "/" + totalModules
                            + ") ══");
                    var log = EventLogListener.open(m.cache(), m.pipeline().name());
                    return CompositePipelineListener.of(
                            PipelineConsole.chooseConsoleListener(m.pipeline().name(), m.pipeline().steps(), mode), log);
                }

                @Override
                public void onModuleFinish(build.jumpkick.runtime.ModuleOutcome o) {
                    if (!o.success()) {
                        CliOutput.err(
                                "jk native: " + wsRoot.relativize(o.dir()) + " failed (exit " + o.exitCode() + ")");
                    }
                }
            };
            build.jumpkick.runtime.WorkspaceResult result;
            try {
                result = EngineClient.runNative(paths, req, listener);
            } catch (IOException e) {
                CliOutput.err("jk native: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            for (String err : result.errors()) CliOutput.err("jk native: " + err);
            return result.exitCode();
        }

        // AUTO / QUIET: one shared aggregate view, calibrated to the whole cascade up front
        // (the plan burst carries every module pipeline's estimated weight).
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Build", animate);
        build.jumpkick.cli.run.AggregateContext agg = new build.jumpkick.cli.run.AggregateContext(view);
        int[] built = {0};
        var listener = new build.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public void onPlan(List<build.jumpkick.runtime.ModulePlan> plan) {
                long total = 0;
                for (var p : plan) total += p.weight();
                agg.calibrate(total);
            }

            @Override
            public build.jumpkick.run.PipelineListener onModuleStart(build.jumpkick.runtime.ModulePlan m) {
                var log = EventLogListener.open(m.cache(), m.pipeline().name());
                return CompositePipelineListener.of(
                        new build.jumpkick.cli.run.AggregateModuleListener(
                                agg, m.coord(), m.pipeline().steps(), m.weight()),
                        log);
            }

            @Override
            public void onModuleFinish(build.jumpkick.runtime.ModuleOutcome o) {
                if (o.success()) built[0]++;
            }
        };
        build.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(paths, req, listener);
        } catch (IOException e) {
            view.finishPipelineFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            view.finishPipelineFailure("dependency resolution failed");
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return result.exitCode();
        }
        if (!result.success()) {
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(build.jumpkick.runtime.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishPipelineFailure(PipelineWedge.coord(failedCoord) + " " + BuildCommand.elapsedSince(buildStart));
            for (PipelineResult.Diagnostic d : agg.lastErrors()) {
                CliOutput.err(ConsoleSpec.renderError(d));
            }
            return result.exitCode();
        }
        view.finishPipelineSuccess(Theme.colorize("Native build successful", Theme.active().success())
                + ", "
                + workspaceSummary(built[0], nativeCount)
                + " "
                + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /** Success-summary tail shared by the hosted and in-process workspace paths. */
    static String workspaceSummary(int built, long nativeCount) {
        return built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
    }


    // --- single-project (unchanged behaviour) --------------------------------

    private int runSingleProject(Path projectDir, Path buildFile, Path cache) throws IOException, InterruptedException {
        // Native builds are opt-in: require [native] always = true. Absent, or
        // [native] declared without always, → not eligible, even for an explicit `jk native`.
        // Thin client: the gate reads the engine's summary, never a client-side parse.
        build.jumpkick.engine.protocol.ProjectInfo build = BuildCommand.projectInfoOrNull(projectDir);
        if (build == null || !"ALWAYS".equals(build.nativeMode())) {
            CliOutput.err("jk native: "
                    + projectDir.getFileName()
                    + " is not native-eligible — set `always = true` under [native] to enable.");
            return Exit.CONFIG;
        }

        // Resolve GraalVM before the pipeline/progress UI starts (a prompt/install
        // can't run inside the captured-output region, and must never run inside
        // the engine — see docs/engine.md).
        Optional<Path> graalHome = graal.resolve(projectDir, build.graal());
        if (graalHome.isEmpty()) return Exit.CONFIG; // GraalResolver already printed why

        String coord = BuildCommand.buildTarget(buildFile, projectDir);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        if (engineDisabledForTests()) {
            // The in-process seam still takes the parsed model — acceptable in the JVM test
            // dist, which links the parser anyway; the native client never reaches this.
            var inProc = InProcessEngine.require();
            return inProc.nativeSingleInProcess(projectDir, inProc.parseBuild(buildFile), cache, graalHome.get(),
                    coord, mode, jdksDir, mainClass, extra, buildOpts.skipTests, global.verbose);
        }

        // Engine-hosted (a cascade of one): the success tail names the built artifact from
        // the engine summary's candidate paths (thin client — no local layout derivation).
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(projectDir, build),
                r -> PipelineWedge.coord(coord),
                true);
        var listener = new build.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public build.jumpkick.run.PipelineListener onModuleStart(build.jumpkick.runtime.ModulePlan m) {
                var log = EventLogListener.open(m.cache(), m.pipeline().name());
                return CompositePipelineListener.of(
                        PipelineConsole.chooseConsoleListener(m.pipeline().steps(), mode, spec, coord), log);
            }
        };
        build.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(
                    EnginePaths.current(),
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
