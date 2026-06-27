// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.runtime.BuildPipeline;
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
 * progress UI.
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
            System.err.println("jk native: " + dev.jkbuild.cli.PathDisplay.styledRaw(buildFile) + " not found.");
            return 66;
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
                System.err.println("jk native: building from workspace root "
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

    // --- workspace cascade ---------------------------------------------------

    private int runWorkspaceNative(Path wsRoot, JkBuild root, Path cache) throws Exception {
        Map<Path, JkBuild> modulesByDir;
        try {
            modulesByDir = WorkspaceLoader.loadModules(wsRoot, root);
        } catch (RuntimeException e) {
            System.err.println("jk native: " + e.getMessage());
            return 2;
        }
        if (modulesByDir.isEmpty()) {
            System.out.println("(workspace declares no modules)");
            return 0;
        }

        List<Path> sorted = BuildCommand.topoSortModules(modulesByDir);
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        long buildStart = System.nanoTime();

        // Resolve the GraalVM for every native-eligible module up front — BEFORE
        // any progress UI opens — so a prompt or install never lands inside the
        // captured-output region (which would corrupt the display). GraalResolver
        // memoizes by spec, so a shared graal pin fetches/installs at most once.
        Map<Path, Path> graalHomes = new java.util.HashMap<>();
        for (Path moduleDir : sorted) {
            JkBuild module = modulesByDir.get(moduleDir);
            if (!isNativeEligible(module)) continue;
            Optional<Path> home = graal.resolve(moduleDir, module.project().graal());
            if (home.isEmpty()) return 2; // GraalResolver already printed why
            graalHomes.put(moduleDir, home.get());
        }

        // JSON / verbose: per-module banners.
        if (mode != GoalConsole.Mode.AUTO && mode != GoalConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path moduleDir = sorted.get(i);
                JkBuild module = modulesByDir.get(moduleDir);
                System.out.println();
                System.out.println(
                        "══ " + wsRoot.relativize(moduleDir) + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runPreparedNative(
                        prepareNativeModule(moduleDir, module, cache, graalHomes.get(moduleDir)), null);
                if (exit != 0) {
                    System.err.println("jk native: " + wsRoot.relativize(moduleDir) + " failed (exit " + exit + ")");
                    return exit;
                }
            }
            return 0;
        }

        // AUTO / QUIET: one shared aggregate view.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.goal(System.out, "Build", animate);
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
                        System.err.println(ConsoleSpec.renderError(d));
                    }
                    return exit;
                }
                built++;
            }
        }

        long nativeCount = sorted.stream()
                .map(modulesByDir::get)
                .filter(NativeCommand::isNativeEligible)
                .count();
        String elapsed = " " + BuildCommand.elapsedSince(buildStart);
        String summary = built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
        view.finishGoalSuccess(
                Theme.colorize("Native build successful", Theme.active().success()) + ", " + summary + elapsed);
        return 0;
    }

    /**
     * Construct (but do not run) one module's goal: core phases plus the native-image tail when the
     * module is native-eligible. Split out of the run step so the workspace path can build every
     * module's goal up front and sum {@link Goal#estimatedTotalWeight()} — including the native
     * phase's weight — to calibrate the shared progress bar before any module runs.
     */
    private PreparedNativeModule prepareNativeModule(Path moduleDir, JkBuild module, Path cache, Path graalHome) {
        boolean eligible = isNativeEligible(module);
        Path buildFile = moduleDir.resolve("jk.toml");
        Path lockFile = moduleDir.resolve("jk.lock");
        int estimatedTests = TestCommand.estimateTestCount(moduleDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                moduleDir,
                cache,
                buildFile,
                lockFile,
                moduleDir,
                1,
                estimatedTests,
                null,
                jdksDir,
                buildOpts.skipTests,
                global.verbose);
        Goal.Builder goalBuilder = BuildPipeline.coreBuilder(inputs);
        if (eligible) {
            String resolvedMain = resolveMain(buildFile);
            goalBuilder.addPhase(
                    BuildPipeline.nativePhase(moduleDir, cache, lockFile, jdksDir, graalHome, resolvedMain, extra));
        }
        Goal goal = goalBuilder.build();
        return new PreparedNativeModule(
                moduleDir,
                BuildCommand.buildTarget(buildFile, moduleDir),
                cache,
                goal,
                goal.estimatedTotalWeight(),
                eligible);
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
        String graalSpec;
        if (build.project().nativeMode() != JkBuild.NativeMode.ALWAYS) {
            System.err.println("jk native: "
                    + projectDir.getFileName()
                    + " is not native-eligible — set `native = true` under [project] to enable.");
            return 2;
        }
        graalSpec = build.project().graal();

        // Resolve GraalVM before the goal/progress UI starts (a prompt/install
        // can't run inside the captured-output region).
        Optional<Path> graalHome = graal.resolve(projectDir, graalSpec);
        if (graalHome.isEmpty()) return 2; // GraalResolver already printed why

        String resolvedMain = resolveMain(buildFile);
        Path lockFile = projectDir.resolve("jk.lock");

        int estimatedTestCount = TestCommand.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir,
                cache,
                buildFile,
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                jdksDir,
                buildOpts.skipTests,
                global.verbose);

        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        builder.addPhase(
                BuildPipeline.nativePhase(projectDir, cache, lockFile, jdksDir, graalHome.get(), resolvedMain, extra));
        Goal goal = builder.build();

        String coord = BuildCommand.buildTarget(buildFile, projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(goal),
                r -> GoalWedge.coord(coord),
                true);
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec, coord);

        if (result.success()) return 0;
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message().contains("main class")) return 64;
        }
        var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    // --- helpers -------------------------------------------------------------

    /**
     * A module is native-eligible only when it sets {@code native = true} (NativeMode.ALWAYS). Absent
     * {@code native} (SUPPORTED) and {@code native = false} (DISABLED) are both skipped by {@code jk
     * native}. A main class is <em>not</em> required: with one we build an executable, without one a
     * shared library.
     */
    static boolean isNativeEligible(JkBuild build) {
        return build.project().nativeMode() == JkBuild.NativeMode.ALWAYS;
    }

    private String resolveMain(Path buildFile) {
        // CLI --main flag wins.
        if (mainClass != null && !mainClass.isBlank()) return mainClass;
        // [native].main-class — dedicated native config, preferred over [image].
        try {
            String fromNative = JkBuildParser.parse(buildFile).nativeConfig().mainClass();
            if (fromNative != null && !fromNative.isBlank()) return fromNative;
        } catch (Exception ignored) {
        }
        // [image].main — fall back to the OCI image section.
        try {
            String fromImage = ImageConfigParser.parse(buildFile).main();
            if (fromImage != null && !fromImage.isBlank()) return fromImage;
        } catch (Exception ignored) {
        }
        // Fall back to [project].main — handled inside nativePhase.
        return null;
    }
}
