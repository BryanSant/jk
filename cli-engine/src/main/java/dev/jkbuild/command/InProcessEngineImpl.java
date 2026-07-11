// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.engine.InProcessEngine;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.WorkspaceMerge;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.TestSummary;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.runtime.CompatGoals;
import dev.jkbuild.runtime.FormatGoals;
import dev.jkbuild.runtime.ImageGoals;
import dev.jkbuild.runtime.PublishGoals;
import dev.jkbuild.runtime.LockGoals;
import dev.jkbuild.runtime.NativeGoals;
import dev.jkbuild.runtime.ToolGoals;
import dev.jkbuild.runtime.HostedEvents;
import dev.jkbuild.runtime.TestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine-backed {@link InProcessEngine}: every method body here is the in-process fallback code
 * that used to live inline in the corresponding command class (behind {@code
 * engineDisabledForTests()}), moved verbatim when Stage 5 cut {@code :cli}'s engine classpath.
 * Lives in {@code dev.jkbuild.command} so those bodies keep calling the commands' package-private
 * rendering helpers exactly as before.
 */
public final class InProcessEngineImpl implements InProcessEngine {

    @Override
    public dev.jkbuild.model.JkBuild parseBuild(java.nio.file.Path buildFile) throws java.io.IOException {
        return dev.jkbuild.config.JkBuildParser.parse(buildFile);
    }

    @Override
    public String[] edit(java.nio.file.Path file, String op, java.util.List<String> args) {
        var result = dev.jkbuild.runtime.EditOps.apply(file, op, args);
        return new String[] {Boolean.toString(result.changed()), result.error()};
    }

    @Override
    public dev.jkbuild.engine.protocol.ProjectInfo projectInfo(java.nio.file.Path dir) {
        return dev.jkbuild.runtime.ExecPlans.projectInfo(dir);
    }

    @Override
    public dev.jkbuild.engine.protocol.DenyReport denyCheck(java.nio.file.Path dir) {
        return dev.jkbuild.runtime.PolicyOps.denyCheck(dir);
    }

    @Override
    public String treeRender(
            java.nio.file.Path dir, int maxDepth, boolean flatten, boolean stack, java.util.List<String> scopes)
            throws java.io.IOException {
        return dev.jkbuild.runtime.GraphOps.treeRender(dir, maxDepth, flatten, stack, scopes);
    }

    @Override
    public dev.jkbuild.engine.protocol.WhyReport why(java.nio.file.Path dir, String query) {
        return dev.jkbuild.runtime.GraphOps.why(dir, query);
    }

    @Override
    public dev.jkbuild.engine.protocol.GeneratedFiles generate(java.nio.file.Path dir, String kind) {
        return dev.jkbuild.runtime.GenerateOps.generate(dir, kind);
    }

    @Override
    public dev.jkbuild.engine.protocol.GeneratedFiles generate(
            java.nio.file.Path dir, String kind, java.util.Map<String, String> params) {
        return dev.jkbuild.runtime.GenerateOps.generate(dir, kind, params);
    }

    @Override
    public dev.jkbuild.engine.protocol.PluginVerbReport pluginVerb(
            java.nio.file.Path dir, java.nio.file.Path cache, String verb, java.util.List<String> args) {
        return dev.jkbuild.runtime.PluginVerbs.run(dir, cache, verb, args);
    }

    @Override
    public java.util.Map<java.nio.file.Path, dev.jkbuild.model.JkBuild> loadWorkspaceModules(
            java.nio.file.Path wsRoot) throws java.io.IOException {
        return dev.jkbuild.config.WorkspaceLoader.loadModules(
                wsRoot, dev.jkbuild.config.JkBuildParser.parse(wsRoot.resolve("jk.toml")));
    }

    @Override
    public dev.jkbuild.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir, java.nio.file.Path cache, String kind, String mainOverride, String binName) {
        return dev.jkbuild.runtime.ExecPlans.execPlan(dir, cache, kind, mainOverride, binName);
    }

    @Override
    public dev.jkbuild.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName,
            java.nio.file.Path binDir,
            java.nio.file.Path libDir) {
        return dev.jkbuild.runtime.ExecPlans.execPlan(dir, cache, kind, mainOverride, binName, binDir, libDir);
    }


    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public InProcessEngineImpl() {}

    @Override
    public int engineServerMain() {
        return dev.jkbuild.cli.EngineMain.run();
    }

    // Populated by the pipeline's run-tests phase; read for the summary + exit code.
    // Value-equal to BuildPipeline.TEST_RESULT (same name + type), so it resolves
    // the same slot in the shared goal state.
    private static final GoalKey<TestSummary> TEST_RESULT = GoalKey.of("test-result", TestSummary.class);

    @Override
    public GoalOutcome testGoal(
            Path dir,
            Path cache,
            Path buildFile,
            Path lockFile,
            int workerCount,
            String profileName,
            Path jdksDir,
            GlobalOptions global)
            throws IOException, InterruptedException {
        // Size the test-runner JVMs' heaps (and cap how many fork at once) to the
        // host's free memory before launching them.
        dev.jkbuild.worker.HeapPlan.Plan heapPlan =
                dev.jkbuild.worker.JvmOptions.planAndApply(dev.jkbuild.worker.HeapPlan.requestedJvms(
                        1, workerCount, false, Runtime.getRuntime().availableProcessors()));
        if (heapPlan != null && heapPlan.warning() != null && !global.outputIsJson()) {
            CliOutput.err("jk test: " + heapPlan.warning());
        }
        // Up-front lexical estimate (Java + Kotlin test sources) so the bar's
        // denominator is set once; the static plan gates the numerator and
        // phase-end auto-fill closes any residual gap.
        int estimatedTestCount = TestSupport.estimateTestCount(dir.resolve("src/test/java"))
                + TestSupport.estimateTestCount(dir.resolve("src/test/kotlin"));

        // testOnly → the core pipeline runs through run-tests but never packages.
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                dir,
                cache,
                buildFile,
                lockFile,
                lockFile.getParent(),
                workerCount,
                estimatedTestCount,
                profileName,
                jdksDir,
                /* skipTests */ false,
                global.verbose, /* testOnly */
                true);
        Goal goal = BuildPipeline.coreBuilder(inputs).build();

        // Deferred lookup: goal.get(TEST_RESULT) is empty until the run-tests phase populates it
        // mid-run, so these lambdas must read it at invocation time (when the console listener's
        // own goalFinish fires), not eagerly here.
        ConsoleSpec spec = new ConsoleSpec(
                "Test",
                r -> TestCommand.testSummary(goal.get(TEST_RESULT).orElse(null), r),
                r -> TestCommand.testFailureMessage(goal.get(TEST_RESULT).orElse(null), r));
        GoalResult result = GoalConsole.runGoal(
                goal, GoalConsole.modeFor(global), cache, spec, BuildCommand.buildTarget(buildFile, dir));
        return new GoalOutcome(result, goal.get(TEST_RESULT).orElse(null));
    }

    @Override
    public GoalResult compileGoal(
            Path dir,
            Path cache,
            String profileName,
            boolean verbose,
            GoalConsole.Mode mode,
            ConsoleSpec spec,
            String target)
            throws IOException, InterruptedException {
        // compileOnly → lock → sync → compile. The pipeline resolves jk.lock on
        // first run and re-locks when jk.toml changed; no "run jk lock first".
        Goal goal = dev.jkbuild.runtime.CompileGoals.compileGoal(dir, cache, profileName, verbose);
        return GoalConsole.runGoal(goal, mode, cache, spec, target);
    }

    @Override
    public GoalResult auditGoal(
            Path lockPath,
            Path cache,
            String severity,
            java.net.URI osvBatchUrl,
            java.net.URI osvVulnsUrl,
            HostedEvents.FindingObserver observer,
            GoalConsole.Mode mode)
            throws IOException, InterruptedException {
        Goal goal = dev.jkbuild.runtime.AuditGoals.auditGoal(
                lockPath, cache, severity, osvBatchUrl, osvVulnsUrl, observer::onFinding);
        return GoalConsole.run(goal, mode, cache);
    }

    @Override
    public FormatGoalOutcome formatGoal(
            Path projectDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            Path rewriteConfig,
            HostedEvents.FileObserver observer,
            GoalListener listener)
            throws IOException {
        Goal goal = FormatGoals.formatGoal(
                projectDir, cache, check, javaStyle, kotlinStyle, optimizeImports, rewriteConfig,
                observer::onFile);
        goal.addListener(listener);
        GoalResult result = goal.run();
        return new FormatGoalOutcome(
                result,
                goal.get(FormatGoals.CHANGED).orElse(-1),
                goal.get(FormatGoals.CLEAN).orElse(-1),
                goal.get(FormatGoals.ERRORS).orElse(-1),
                goal.get(FormatGoals.TOTAL).orElse(-1),
                goal.get(FormatGoals.WORKER_EXIT).orElse(-1));
    }

    @Override
    public PublishGoalOutcome publishGoal(
            Path projectDir,
            Path cache,
            java.net.URI repoUrl,
            String region,
            String endpoint,
            Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            Path keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            dev.jkbuild.credential.RepoCredential credential,
            GoalConsole.Mode mode)
            throws IOException, InterruptedException {
        Goal goal = PublishGoals.publishGoal(
                projectDir,
                cache,
                new PublishGoals.Request(
                        repoUrl,
                        region,
                        endpoint,
                        jarPath,
                        allowSnapshot,
                        dryRun,
                        keyFile,
                        gpgPassphrase,
                        sigstore,
                        slsa,
                        sbom,
                        credential));
        GoalResult result = GoalConsole.run(goal, mode, cache);
        return new PublishGoalOutcome(result, goal.get(PublishGoals.FILES).orElse(0));
    }

    @Override
    public GoalOutcome imageGoal(
            Path projectDir,
            Path cache,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutable,
            GoalConsole.Mode mode,
            String module)
            throws IOException, InterruptedException {
        Goal goal = ImageGoals.imageGoal(
                projectDir, cache, jdksDir, skipTests, verbose, mainClass, registry, tag, tarballArg,
                dockerExecutable);
        ConsoleSpec spec =
                new ConsoleSpec("Image", r -> imageSuccessTail(goal), r -> "Image build failed", true);
        GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, module);
        return new GoalOutcome(result, goal.get(BuildPipeline.TEST_RESULT).orElse(null));
    }

    @Override
    public ImportGoalOutcome importGoal(
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            Path report,
            Path cache,
            HostedEvents.NoteObserver notes)
            throws IOException, InterruptedException {
        Goal goal = CompatGoals.importGoal(source, out, baseDir, tmpDir, force, report, cache, notes::onNote);
        GoalResult result = goal.run();
        return new ImportGoalOutcome(
                result,
                goal.get(CompatGoals.EXIT).orElse(1),
                goal.get(CompatGoals.WARNINGS).orElse(0),
                goal.get(CompatGoals.ERROR).orElse(null),
                goal.get(CompatGoals.DIAG).orElse(null));
    }

    @Override
    public HostedEvents.Provision provision(Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException, InterruptedException {
        CompatGoals.Provision p = CompatGoals.provision(cache, projectDir, toolsRoot, noDiscover, gradle);
        return new HostedEvents.Provision(p.bin(), p.version(), p.source(), p.error(), p.exit(), p.diag());
    }

    @Override
    public EngineClient.CacheMaintSummary cacheGc(Path cache) throws IOException {
        dev.jkbuild.task.CacheGc.Report report = dev.jkbuild.task.CacheGc.run(cache, false);
        return new EngineClient.CacheMaintSummary(
                report.purgedBlobs(), report.freedBytes(), -1, report.repoLinksRemoved());
    }

    @Override
    public ToolGoalOutcome toolResolveGoal(
            dev.jkbuild.model.ToolCoordSpec spec,
            List<dev.jkbuild.model.ToolCoordSpec> with,
            String bin,
            String mainClass,
            java.net.URI repoUrl,
            Path cacheDir,
            String label,
            GoalConsole.Mode mode)
            throws IOException, InterruptedException {
        Goal goal = ToolGoals.resolveGoal(spec, with, bin, mainClass, repoUrl, cacheDir, label);
        GoalResult result = GoalConsole.run(goal, mode, cacheDir);
        if (!result.success()) return new ToolGoalOutcome(result, null);
        return new ToolGoalOutcome(result, goal.get(ToolGoals.TOOL_ENV).orElseThrow());
    }

    // ---- jk lock's test-only in-process path (identical pipeline via LockGoals) ----------------

    @Override
    public int lockInProcess(
            Path dir,
            Path cache,
            GoalConsole.Mode mode,
            boolean live,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception {
        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            CliOutput.err("jk lock: " + e.getMessage());
            return Exit.CONFIG;
        }

        JkBuild effectiveRoot = LockGoals.applyWorkspaceContextIfModule(dir, root);

        if (!live) {
            // --verbose / --output json: existing simple-task rendering.
            int result = lockSingleProject(dir, effectiveRoot, cache, "Lock", mode, features, noDefaultFeatures, sources, repoUrl);
            if (result != 0) return result;
            if (effectiveRoot.isWorkspaceRoot()) {
                Map<Path, JkBuild> modules;
                try {
                    modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
                } catch (RuntimeException e) {
                    CliOutput.err("jk lock: " + e.getMessage());
                    return Exit.CONFIG;
                }
                for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                    Path moduleDir = entry.getKey();
                    JkBuild rawModule = entry.getValue();
                    JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                    String moduleLabel = dir.getFileName() + "/" + dir.relativize(moduleDir);
                    int moduleResult = lockSingleProject(moduleDir, effectiveModule, cache, moduleLabel, mode, features, noDefaultFeatures, sources, repoUrl);
                    if (moduleResult != 0) return moduleResult;
                }
            }
            return 0;
        }

        // Live goal-mode TUI: one shared CommandManager spanning root + all workspace modules.
        boolean animate = mode == GoalConsole.Mode.AUTO && GoalConsole.isInteractiveTerminal();
        return runLive(dir, effectiveRoot, cache, animate, features, noDefaultFeatures, sources, repoUrl);
    }

    /**
     * Live path: one {@link CommandManager} in goal mode, one row per module, a shared progress bar
     * calibrated to total packages across all modules, and a completed-package tail.
     */
    private static int runLive(
            Path dir,
            JkBuild effectiveRoot,
            Path cache,
            boolean animate,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception {
        CommandManager view = CommandManager.goal(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();

        String rootCoord = LockGoals.coordLabel(effectiveRoot, dir);
        int exit = lockModuleLive(dir, effectiveRoot, cache, rootCoord, view, globalLocked, errorLines, features, noDefaultFeatures, sources, repoUrl);
        if (exit != 0) {
            view.finishGoalFailure(LockCommand.lockFailTail(), errorLines);
            return exit;
        }

        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                errorLines.add(e.getMessage());
                view.finishGoalFailure(LockCommand.lockFailTail(), errorLines);
                return Exit.CONFIG;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                String moduleCoord = LockGoals.coordLabel(rawModule, moduleDir);
                exit = lockModuleLive(moduleDir, effectiveModule, cache, moduleCoord, view, globalLocked, errorLines, features, noDefaultFeatures, sources, repoUrl);
                if (exit != 0) {
                    view.finishGoalFailure(LockCommand.lockFailTail(), errorLines);
                    return exit;
                }
            }
        }

        view.finishGoalSuccess(LockCommand.lockSuccessTail(globalLocked.get(), start));
        return 0;
    }

    /**
     * Lock one module with the goal-mode TUI. Registers an active row in {@code view}, runs the full
     * lock goal (parse → resolve → lock-plugins → write), feeds per-package completions into the
     * tail, then marks the row done. Returns 0 on success, or a non-zero exit code on failure
     * (errors collected into {@code errorLines} for the caller to print above the failure chip).
     */
    private static int lockModuleLive(
            Path dir,
            JkBuild effective,
            Path cache,
            String coord,
            CommandManager view,
            AtomicInteger globalLocked,
            List<String> errorLines,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception {
        // Register one phase row for this module. The display label is empty so
        // renderActiveRow produces "module › dep" (two segments, not three).
        view.addPhaseLabeled(coord, "lock", "");
        view.phaseRunning(coord, "lock");

        // Lock is purely resolution — total is unknown upfront, so we show a
        // static top-line label and record each resolved dep as a completion line.
        view.solveLabel("Locking versions…");

        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {
                // total growth already rides the goal's scope updates
            }

            @Override
            public void onPackage(String pkg, String version) {
                // Show active dep in the phase row (module › dep via renderActiveRow).
                view.phaseMessage(coord, "lock", Coords.module(pkg, version));
                // Record as a completion line with an absolute count bracket.
                int n = globalLocked.incrementAndGet();
                Theme t = Theme.active();
                String line = Theme.colorize(Glyphs.CHECK, t.success())
                        + " "
                        + ConsoleSpec.countBracket(n, t)
                        + " "
                        + Coords.module(pkg, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    CliOutput.out(line);
                }
            }
        };

        // Build and run the lock goal directly (no GoalConsole wrapper).
        Goal goal = LockGoals.lockGoal(
                dir, effective, cache, repoUrl, features, !noDefaultFeatures, sources, observer, Coords::module);
        GoalResult result = goal.run();

        if (result.success()) {
            view.phaseDone(coord, "lock", true);
            return 0;
        }

        // Failure: collect diagnostics, mark row failed.
        view.phaseDone(coord, "lock", false);
        for (GoalResult.Diagnostic d : result.errors()) {
            errorLines.add(ConsoleSpec.renderError(d));
        }
        return LockGoals.failureExitCode(result);
    }

    /**
     * Run the lock pipeline (parse → resolve → lock-plugins → write) for one project directory.
     * Used by the non-live in-process path (--verbose / --output json). {@code effective} is the
     * pre-parsed {@link JkBuild} with any {@code workspace:} placeholders already resolved.
     */
    private static int lockSingleProject(
            Path dir,
            JkBuild effective,
            Path cache,
            String label,
            GoalConsole.Mode mode,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception {
        Goal goal = LockGoals.lockGoal(
                dir, effective, cache, repoUrl, features, !noDefaultFeatures, sources,
                ResolveObserver.NOOP, Coords::module);

        ConsoleSpec spec = new ConsoleSpec(
                label,
                r -> {
                    Lockfile lock = goal.get(LockGoals.LOCKFILE).orElseThrow();
                    int pkgs = lock.artifacts().size();
                    int plgs = lock.plugins().size();
                    long srcs = lock.artifacts().stream()
                            .filter(p -> p.sourcesChecksum() != null)
                            .count();
                    String depStr = "Resolved " + pkgs + " dependenc" + (pkgs == 1 ? "y" : "ies");
                    if (srcs > 0) depStr += ", " + srcs + " with sources";
                    return plgs > 0 ? depStr + ", " + plgs + " plugin" + (plgs == 1 ? "" : "s") : depStr;
                },
                r -> "Failed to resolve dependencies");

        GoalResult result = GoalConsole.run(goal, mode, cache, spec);
        return result.success() ? 0 : LockGoals.failureExitCode(result);
    }


    // ---- jk update's test-only in-process path (identical pipeline via LockGoals) --------------

    @Override
    public int updateInProcess(
            Path dir,
            Path cache,
            boolean gitOnly,
            String gitTarget,
            List<String> features,
            boolean noDefaultFeatures,
            java.net.URI repoUrl,
            GlobalOptions global)
            throws Exception {
        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            CliOutput.err("jk update: " + e.getMessage());
            return Exit.CONFIG;
        }

        // `jk update --git [<name>]`: re-resolve git dependencies only, leaving every
        // other dependency's locked version untouched.
        if (gitOnly) {
            LockGoals.GitUpdateOutcome outcome =
                    LockGoals.updateGitOnly(dir, root, cache, repoUrl, features, !noDefaultFeatures, gitTarget);
            if (outcome.exitCode() != 0) {
                CliOutput.err("jk update: " + outcome.error());
                return outcome.exitCode();
            }
            if (!global.outputIsJson()) {
                UpdateCommand.printGitSummary(outcome.refreshed());
            }
            return 0;
        }

        // When updating a workspace module directly, filter sibling-internal deps.
        JkBuild effectiveRoot = LockGoals.applyWorkspaceContextIfModule(dir, root);

        // Re-resolve the current directory (root or standalone project).
        int result = updateSingleProject(dir, effectiveRoot, cache, features, noDefaultFeatures, repoUrl, global);
        if (result != 0) return result;

        // Cascade: re-resolve each declared workspace module in declaration order.
        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                CliOutput.err("jk update: " + e.getMessage());
                return Exit.CONFIG;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                int moduleResult = updateSingleProject(moduleDir, effectiveModule, cache, features, noDefaultFeatures, repoUrl, global);
                if (moduleResult != 0) return moduleResult;
            }
        }
        return 0;
    }

    private static int updateSingleProject(
            Path dir,
            JkBuild effective,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            java.net.URI repoUrl,
            GlobalOptions global)
            throws Exception {
        Path lockFile = dir.resolve("jk.lock");
        Goal goal = LockGoals.updateGoal(dir, effective, cache, repoUrl, features, !noDefaultFeatures);

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);
        if (!result.success()) {
            return LockGoals.failureExitCode(result);
        }

        Lockfile lock = goal.get(LockGoals.LOCKFILE).orElseThrow();
        if (!global.outputIsJson()) {
            UpdateCommand.printUpdatedLine(lockFile, lock.artifacts().size(), global.workingDir());
        }
        return 0;
    }


    // ---- jk sync's test-only in-process path (identical goal via SyncGoals) --------------------

    @Override
    public int syncInProcess(
            Path dir,
            Path cache,
            Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            GoalConsole.Mode mode,
            String targetLabel) {
        java.util.concurrent.atomic.AtomicInteger totalFetched = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalUpToDate = new java.util.concurrent.atomic.AtomicInteger(0);

        Goal goal = dev.jkbuild.runtime.SyncGoals.syncGoal(
                dir, cache, jdksDir, repoUrl, sources, totalFetched, totalUpToDate,
                dev.jkbuild.cli.theme.Coords::module, true);

        ConsoleSpec spec = SyncCommand.syncSpec(totalFetched::get, totalUpToDate::get);
        GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, targetLabel);

        if (result.success()) {
            // Opportunistic cache prune — no-op when auto-prune is off.
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            return 0;
        }
        // The progress-bar listener (or SilentListener on a pipe) has
        // already surfaced the failure — no command-side summary.
        return 1;
    }

    @Override
    public dev.jkbuild.engine.protocol.IdeWireModel ideModel(
            java.nio.file.Path dir, java.nio.file.Path cache, java.nio.file.Path jdksDir) {
        return dev.jkbuild.runtime.IdeOps.ideModel(dir, cache, jdksDir, true);
    }

    @Override
    public GoalOutcome runBuildGoal(
            Path projectDir,
            Path cache,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            GoalConsole.Mode mode,
            ConsoleSpec spec,
            String coord)
            throws IOException, InterruptedException {
        // Build through the one pipeline, producing whatever jk.toml declares
        // (jar always; shadow/native when configured). Cache-aware, so a clean
        // tree is near-instant.
        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestSupport.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir,
                cache,
                projectDir.resolve("jk.toml"),
                lockFile,
                projectDir,
                1,
                estimatedTestCount,
                null,
                jdksDir,
                skipTests,
                verbose);
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        BuildPipeline.appendDeclaredTails(builder, inputs);
        Goal goal = builder.build();
        GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, coord);
        return new GoalOutcome(result, goal.get(BuildPipeline.TEST_RESULT).orElse(null));
    }

    @Override
    public dev.jkbuild.runtime.WorkspaceResult buildWorkspace(
            dev.jkbuild.runtime.WorkspaceRequest request, dev.jkbuild.runtime.WorkspaceBuildListener listener) {
        return dev.jkbuild.runtime.BuildService.buildWorkspace(request, listener);
    }

    @Override
    public dev.jkbuild.runtime.ExplainPlan explain(
            Path startDir,
            dev.jkbuild.model.JkBuild entry,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean serial,
            boolean parallelTests,
            long[] etaOut)
            throws IOException {
        dev.jkbuild.runtime.ExplainPlan plan = dev.jkbuild.runtime.BuildService.explain(startDir, entry, cache);
        etaOut[0] = plan.hasErrors()
                ? 0
                : dev.jkbuild.runtime.BuildService.estimateEtaMillis(
                        plan, cache, workers, jdksDir, profile, skipTests, verbose, serial, parallelTests);
        return plan;
    }

    /** Success tail for the in-process path — reads the finished goal's structured keys. */
    private static String imageSuccessTail(Goal goal) {
        dev.jkbuild.model.JkBuild project = goal.get(BuildPipeline.PROJECT).orElse(null);
        dev.jkbuild.image.ImageConfig cfg = goal.get(ImageGoals.CONFIG).orElse(null);
        Path tarballPath = goal.get(ImageGoals.TARBALL_PATH).orElse(null);
        String name = project != null ? project.project().name() : "";
        String version = project != null ? project.project().version() : "";
        boolean daemonMode = tarballPath == null
                && (cfg == null || cfg.registry() == null || cfg.registry().isBlank());
        String daemonExe = !daemonMode
                ? null
                : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
        String ref = goal.get(ImageGoals.IMAGE_REF)
                .orElse(cfg != null ? cfg.targetReference(name, version) : "");
        return ImageCommand.imageSuccessTail(tarballPath != null ? tarballPath.toString() : null, name, version, daemonExe, ref);
    }


    // ---- jk cache prune/purge in-process paths (test-only + legacy --background child) ----------

    @Override
    public int pruneInProcess(
            Path root,
            boolean defaultCacheDir,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            String maxSize,
            boolean background,
            GlobalOptions global)
            throws IOException {
        java.nio.channels.FileChannel lockChan = null;
        java.nio.channels.FileLock lock = null;
        java.io.PrintStream originalOut = null, originalErr = null;
        if (background) {
            java.nio.file.Files.createDirectories(root);
            Path lockFile = root.resolve(".prune.lock");
            lockChan = java.nio.channels.FileChannel.open(
                    lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            lock = lockChan.tryLock();
            if (lock == null) {
                lockChan.close();
                return 0;
            }
            Path logFile = root.resolve(".prune-log");
            var logStream = new java.io.PrintStream(java.nio.file.Files.newOutputStream(
                    logFile,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
            originalOut = CliOutput.stdout();
            originalErr = CliOutput.stderr();
            System.setOut(logStream);
            System.setErr(logStream);
        }
        try {
            Goal goal = dev.jkbuild.runtime.CacheGoals.pruneGoal(
                    root, olderThanDays, dryRun, sweep, maxSize, defaultCacheDir);

            ConsoleSpec spec = CacheCommand.CachePruneCommand.pruneSpec(
                    dryRun,
                    () -> goal.get(dev.jkbuild.runtime.CacheGoals.FILES).orElse(0L),
                    () -> goal.get(dev.jkbuild.runtime.CacheGoals.BYTES).orElse(0L));

            GoalResult goalResult = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), root, spec, "Cache");

            CacheCommand.CachePruneCommand.warnReachableEvicted(
                    goal.get(dev.jkbuild.runtime.CacheGoals.REACHABLE_EVICTED).orElse(0L));

            return goalResult.success() ? 0 : 1;
        } finally {
            if (background && !dryRun) {
                try {
                    java.nio.file.Files.writeString(
                            root.resolve(dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE),
                            Long.toString(System.currentTimeMillis()),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
            if (originalOut != null) System.setOut(originalOut);
            if (originalErr != null) System.setErr(originalErr);
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException ignored) {
                }
            }
            if (lockChan != null) {
                try {
                    lockChan.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public int purgeInProcess(Path root, GoalConsole.Mode mode, ConsoleSpec spec) throws IOException {
        Goal goal = dev.jkbuild.runtime.CacheGoals.purgeGoal(root);
        GoalResult goalResult = GoalConsole.runGoal(goal, mode, root, spec, "Cache");
        return goalResult.success() ? 0 : 1;
    }

    @Override
    public int clearInProcess(Path root, Path projectDir, boolean dryRun, GoalConsole.Mode mode) throws IOException {
        Goal goal = dev.jkbuild.runtime.CacheGoals.clearGoal(root, projectDir, dryRun);
        ConsoleSpec spec = CacheCommand.CacheClearCommand.clearSpec(
                dryRun,
                () -> goal.get(dev.jkbuild.runtime.CacheGoals.FILES).orElse(0L),
                () -> goal.get(dev.jkbuild.runtime.CacheGoals.BYTES).orElse(0L));
        GoalResult goalResult = GoalConsole.runGoal(goal, mode, root, spec, "Cache");
        return goalResult.success() ? 0 : 1;
    }

    // ---- jk native's test-only in-process cascade (identical goals via NativeGoals) ------------

    /** In-process workspace cascade — the test-only bypass; builds the exact same goals via {@link NativeGoals}. */
    @Override
    public int nativeWorkspaceInProcess(
            Path wsRoot,
            java.util.Map<Path, JkBuild> modulesByDir,
            java.util.List<Path> sorted,
            Path cache,
            java.util.Map<Path, Path> graalHomes,
            GoalConsole.Mode mode,
            long buildStart,
            long nativeCount,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extra,
            boolean skipTests,
            boolean verbose)
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
                        prepareNativeModule(moduleDir, module, cache, graalHomes.get(moduleDir), jdksDir,
                                mainClass, extra, skipTests, verbose),
                        null,
                        mode);
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
                PreparedNativeModule pm = prepareNativeModule(
                        moduleDir, modulesByDir.get(moduleDir), cache, graalHomes.get(moduleDir), jdksDir,
                        mainClass, extra, skipTests, verbose);
                total += pm.barWeight();
                prepared.add(pm);
            }
            agg.calibrate(total);

            for (PreparedNativeModule pm : prepared) {
                String moduleName = wsRoot.relativize(pm.dir()).toString();
                int exit;
                try {
                    exit = runPreparedNative(pm, agg, mode);
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
                + NativeCommand.workspaceSummary(built, nativeCount)
                + " "
                + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /**
     * Construct (but do not run) one module's goal via the shared {@link NativeGoals} factory (the
     * same assembly the engine hosts). Split out of the run step so the workspace path can build
     * every module's goal up front and sum {@link Goal#estimatedTotalWeight()} — including the
     * native phase's weight — to calibrate the shared progress bar before any module runs.
     */
    private static PreparedNativeModule prepareNativeModule(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path graalHome,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extra,
            boolean skipTests,
            boolean verbose) {
        Goal goal = NativeGoals.moduleGoal(
                moduleDir, module, cache, jdksDir, graalHome, mainClass, extra, skipTests, verbose);
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
    private static int runPreparedNative(
            PreparedNativeModule pm, dev.jkbuild.cli.run.AggregateContext agg, GoalConsole.Mode mode) {
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
            result = GoalConsole.runGoal(pm.goal(), mode, pm.cache(), spec, pm.target());
        }
        return result.success() ? 0 : 1;
    }

    /**
     * A workspace module's goal, built and ready to run, paired with its pre-scan bar weight (its
     * slice of the calibrated aggregate total) and whether it carries the native-image tail.
     */
    private record PreparedNativeModule(
            Path dir, String target, Path cache, Goal goal, long barWeight, boolean eligible) {}


    @Override
    public int nativeSingleInProcess(
            Path projectDir,
            JkBuild build,
            Path cache,
            Path graalHome,
            String coord,
            GoalConsole.Mode mode,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extra,
            boolean skipTests,
            boolean verbose) {
        Goal goal = NativeGoals.moduleGoal(
                projectDir, build, cache, jdksDir, graalHome, mainClass, extra, skipTests, verbose);
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> dev.jkbuild.cli.theme.Theme.colorize(
                                "Native build successful",
                                dev.jkbuild.cli.theme.Theme.active().success())
                        + BuildCommand.builtArtifact(goal),
                r -> dev.jkbuild.cli.tui.GoalWedge.coord(coord),
                true);
        GoalResult result = GoalConsole.runGoal(goal, mode, cache, spec, coord);
        return result.success() ? 0 : NativeGoals.failureExitCode(goal, result);
    }

    @Override
    public EngineClient.GitFetchOutcome gitFetchGoal(
            String url,
            String canonicalUrl,
            String ref,
            Path cache,
            boolean refresh,
            boolean requireJkToml,
            GoalConsole.Mode mode)
            throws IOException, InterruptedException {
        Goal fetchGoal =
                dev.jkbuild.runtime.InstallGoals.gitFetchGoal(url, canonicalUrl, ref, cache, refresh, requireJkToml);
        GoalResult result = GoalConsole.run(fetchGoal, mode, cache);
        return new EngineClient.GitFetchOutcome(
                result,
                fetchGoal.get(dev.jkbuild.runtime.InstallGoals.CHECKOUT).orElse(null),
                fetchGoal.get(dev.jkbuild.runtime.InstallGoals.FETCHED_SHA).orElse(null));
    }

    @Override
    public GoalOutcome installProjectGoal(
            Path projectDir,
            Path cacheDir,
            Path m2Dir,
            boolean skipTests,
            boolean verbose,
            Path graalHome,
            GoalConsole.Mode mode)
            throws IOException {
        Goal goal = dev.jkbuild.runtime.InstallGoals.projectInstallGoal(
                projectDir, cacheDir, m2Dir, skipTests, verbose, graalHome);
        GoalResult result = GoalConsole.run(goal, mode, cacheDir);
        return new GoalOutcome(result, goal.get(BuildPipeline.TEST_RESULT).orElse(null));
    }

    // ---- jk build's test-only in-process paths -------------------------------------------------

    @Override
    public dev.jkbuild.runtime.BuildForecast forecast(
            Path entryDir, dev.jkbuild.model.JkBuild entryBuild, Path cache, boolean skipTests) throws IOException {
        dev.jkbuild.runtime.BuildService.ResolvedGraph graph =
                dev.jkbuild.runtime.BuildService.resolveGraph(entryDir, entryBuild);
        if (graph.hasErrors()) {
            return new dev.jkbuild.runtime.BuildForecast(java.util.Set.of(), false, false, graph.errors());
        }
        java.util.Set<Path> dirty = dev.jkbuild.runtime.BuildService.forecastDirtyDirs(graph, cache, skipTests);
        boolean lockStale = dev.jkbuild.runtime.BuildService.workspaceLockStale(
                entryDir, entryBuild, entryDir.resolve("jk.lock"));
        return new dev.jkbuild.runtime.BuildForecast(dirty, lockStale, graph.isEmpty(), java.util.List.of());
    }

    @Override
    public int buildProjectInProcess(
            Path dir,
            Path cache,
            Path jdksDir,
            int workers,
            String profileName,
            boolean skipTests,
            GlobalOptions global,
            long startNanos)
            throws Exception {
        // Size worker-JVM concurrency and per-JVM heaps from the host's free memory before any
        // fork — one module; tests fork `workers` JVMs.
        int cap = Runtime.getRuntime().availableProcessors();
        boolean parallelTests = dev.jkbuild.config.SessionContext.current().parallelTests();
        int requested = dev.jkbuild.worker.HeapPlan.requestedJvms(1, workers, parallelTests, cap);
        dev.jkbuild.worker.HeapPlan.Plan plan = dev.jkbuild.worker.JvmOptions.planAndApply(requested);
        if (plan != null && plan.warning() != null && !global.outputIsJson()) {
            CliOutput.err("jk build: " + plan.warning());
        }

        try {
            dir = dir.toRealPath();
        } catch (java.io.IOException ignored) {
        }
        Path buildFile = dir.resolve("jk.toml");
        // One shared Inputs factory with jk explain (see BuildPlanForecast.inputsFor) so the two
        // can't drift in what they feed the effort-weight prediction — it also does the lexical
        // run-tests pre-discovery so the phase's scope is known before any phase runs.
        BuildPipeline.Inputs inputs = dev.jkbuild.runtime.BuildPlanForecast.inputsFor(
                dir, cache, workers, jdksDir, profileName, skipTests, global.verbose, java.util.Set.of());
        // Quick pre-check: is every work phase already cached? Uses only stat/CAS
        // lookups — the same operations the parse-build phase would run lazily.
        boolean fullyCached = false;
        try {
            dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(inputs.cache());
            dev.jkbuild.model.JkBuild build = dev.jkbuild.config.JkBuildParser.parse(buildFile);
            dev.jkbuild.runtime.CompileSupport.Languages langs =
                    dev.jkbuild.runtime.CompileSupport.resolveLanguages(build.project(), dir);
            boolean compact = dev.jkbuild.runtime.CompileSupport.isSimpleLayout(build.project(), dir);
            dev.jkbuild.runtime.EffortWeights.Plan effort =
                    dev.jkbuild.runtime.EffortWeights.predict(inputs, cas, compact, langs.java(), langs.kotlin());
            fullyCached = effort.fullyCached();
        } catch (Exception ignored) {
            // best-effort; proceed normally if prediction throws
        }
        String target = BuildCommand.buildTarget(buildFile, dir);
        // Single-module fast path: skip the TUI entirely when every work phase is already cached.
        if (fullyCached && GoalConsole.isInteractiveTerminal() && !global.outputIsJson()) {
            CliOutput.out(dev.jkbuild.cli.tui.GoalWedge.chipLine(
                    dev.jkbuild.cli.tui.Glyphs.CHECK,
                    "Build",
                    dev.jkbuild.config.GlobalConfig.nerdfont(),
                    BuildCommand.buildOk() + ", project up to date " + BuildCommand.elapsedSince(startNanos)));
            return 0;
        }
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        BuildPipeline.appendDeclaredTails(builder, inputs);
        Goal goal = builder.build();
        long barWeight = goal.estimatedTotalWeight();
        // chip = true → settle through the goal chip (" ✓ Build ▶ Build successful …"),
        // matching the workspace path. onSuccess/onFailure return the tail after the verb.
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> BuildCommand.projectTail(goal),
                r -> dev.jkbuild.cli.tui.GoalWedge.coord(target),
                true);
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec, target);
        TestSummary testResult = goal.get(TEST_RESULT).orElse(null);
        if (result.success()) {
            // Fold this run's measured throughput into the host calibration so the cold estimate
            // self-heals. Skip fully-cached runs (near-zero time, not representative of work).
            if (!fullyCached && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    dev.jkbuild.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
            }
            // Cache settings are user-global only; resolve() reads ~/.jk/config.toml
            // (a project jk.toml's [cache] is intentionally ignored).
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            return 0;
        }
        // Test failures get exit 4; other failures exit 1.
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    @Override
    public EngineClient.ScriptPrepareOutcome scriptPrepare(
            String mode,
            Path script,
            Path cacheDir,
            Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            List<String> with,
            GoalConsole.Mode consoleMode)
            throws IOException, InterruptedException {
        List<dev.jkbuild.model.Dependency> extraDeps = with.stream()
                .map(dev.jkbuild.script.ScriptHeaderParser::parseDependency)
                .toList();
        Goal goal =
                switch (mode) {
                    case "java" -> dev.jkbuild.runtime.ScriptGoals.javaScriptGoal(
                            script, cacheDir, stateDir, repoUrl, forceRecompile, extraDeps);
                    case "kt" -> dev.jkbuild.runtime.ScriptGoals.kotlinScriptGoal(
                            script, cacheDir, stateDir, repoUrl, forceRecompile, extraDeps);
                    case "kts" -> dev.jkbuild.runtime.ScriptGoals.ktsScriptGoal(script, cacheDir, repoUrl, extraDeps);
                    case "jar" -> dev.jkbuild.runtime.ScriptGoals.jarGoal(script, cacheDir, repoUrl);
                    default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                };
        GoalResult result = GoalConsole.run(goal, consoleMode, cacheDir);
        return new EngineClient.ScriptPrepareOutcome(
                result,
                goal.get(dev.jkbuild.runtime.ScriptGoals.MAIN_CLASS).orElse(null),
                dev.jkbuild.runtime.ScriptGoals.classpathOf(goal),
                goal.get(dev.jkbuild.runtime.ScriptGoals.CLASSES_DIR).orElse(null),
                goal.get(dev.jkbuild.runtime.ScriptGoals.KOTLINC_BIN).orElse(null),
                goal.get(dev.jkbuild.runtime.ScriptGoals.KT_STDLIB).orElse(null));
    }
}
