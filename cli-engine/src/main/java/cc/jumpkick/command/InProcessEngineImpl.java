// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.InProcessEngine;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPipelines;
import cc.jumpkick.runtime.CompatPipelines;
import cc.jumpkick.runtime.CompileSupport;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.FormatPipelines;
import cc.jumpkick.runtime.HostedEvents;
import cc.jumpkick.runtime.ImagePipelines;
import cc.jumpkick.runtime.LockPipelines;
import cc.jumpkick.runtime.NativePipelines;
import cc.jumpkick.runtime.PublishPipelines;
import cc.jumpkick.runtime.TestSupport;
import cc.jumpkick.runtime.ToolPipelines;
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
 * Lives in {@code cc.jumpkick.command} so those bodies keep calling the commands' package-private
 * rendering helpers exactly as before.
 */
public final class InProcessEngineImpl implements InProcessEngine {

    @Override
    public JkBuild parseBuild(java.nio.file.Path buildFile) throws java.io.IOException {
        return JkBuildParser.parse(buildFile);
    }

    @Override
    public String[] edit(java.nio.file.Path file, String op, java.util.List<String> args) {
        var result = cc.jumpkick.runtime.EditOps.apply(file, op, args);
        return new String[] {Boolean.toString(result.changed()), result.error()};
    }

    @Override
    public cc.jumpkick.engine.protocol.ProjectInfo projectInfo(java.nio.file.Path dir) {
        return cc.jumpkick.runtime.ExecPlans.projectInfo(dir);
    }

    @Override
    public cc.jumpkick.engine.protocol.DenyReport denyCheck(java.nio.file.Path dir) {
        return cc.jumpkick.runtime.PolicyOps.denyCheck(dir);
    }

    @Override
    public String treeRender(
            java.nio.file.Path dir, int maxDepth, boolean flatten, boolean stack, java.util.List<String> scopes)
            throws java.io.IOException {
        return cc.jumpkick.runtime.GraphOps.treeRender(dir, maxDepth, flatten, stack, scopes);
    }

    @Override
    public cc.jumpkick.engine.protocol.WhyReport why(java.nio.file.Path dir, String query) {
        return cc.jumpkick.runtime.GraphOps.why(dir, query);
    }

    @Override
    public cc.jumpkick.engine.protocol.GeneratedFiles generate(java.nio.file.Path dir, String kind) {
        return cc.jumpkick.runtime.GenerateOps.generate(dir, kind);
    }

    @Override
    public cc.jumpkick.engine.protocol.GeneratedFiles generate(
            java.nio.file.Path dir, String kind, java.util.Map<String, String> params) {
        return cc.jumpkick.runtime.GenerateOps.generate(dir, kind, params);
    }

    @Override
    public cc.jumpkick.engine.protocol.PluginCommandReport pluginCommand(
            java.nio.file.Path dir, java.nio.file.Path cache, String command, java.util.List<String> args) {
        return cc.jumpkick.runtime.PluginCommands.run(dir, cache, command, args);
    }

    @Override
    public java.util.Map<java.nio.file.Path, JkBuild> loadWorkspaceModules(java.nio.file.Path wsRoot)
            throws java.io.IOException {
        return cc.jumpkick.config.WorkspaceLoader.loadModules(wsRoot, JkBuildParser.parse(wsRoot.resolve("jk.toml")));
    }

    @Override
    public cc.jumpkick.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir, java.nio.file.Path cache, String kind, String mainOverride, String binName) {
        return cc.jumpkick.runtime.ExecPlans.execPlan(dir, cache, kind, mainOverride, binName);
    }

    @Override
    public cc.jumpkick.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName,
            java.nio.file.Path binDir,
            java.nio.file.Path libDir) {
        return cc.jumpkick.runtime.ExecPlans.execPlan(dir, cache, kind, mainOverride, binName, binDir, libDir);
    }

    /** Public no-arg constructor for {@link java.util.ServiceLoader}. */
    public InProcessEngineImpl() {}

    @Override
    public int engineServerMain() {
        return cc.jumpkick.cli.EngineMain.run();
    }

    // Populated by the pipeline's run-tests step; read for the summary + exit code.
    // Value-equal to BuildPipelines.TEST_RESULT (same name + type), so it resolves
    // the same slot in the shared pipeline state.
    private static final PipelineKey<TestSummary> TEST_RESULT = PipelineKey.of("test-result", TestSummary.class);

    @Override
    public PipelineOutcome testPipeline(
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
        cc.jumpkick.engine.plugin.HeapPlan.Plan heapPlan =
                cc.jumpkick.engine.plugin.JvmOptions.planAndApply(cc.jumpkick.engine.plugin.HeapPlan.requestedJvms(
                        1, workerCount, false, Runtime.getRuntime().availableProcessors()));
        if (heapPlan != null && heapPlan.warning() != null && !global.outputIsJson()) {
            CliOutput.err("jk test: " + heapPlan.warning());
        }
        // Up-front lexical estimate (Java + Kotlin test sources) so the bar's
        // denominator is set once; the static plan gates the numerator and
        // step-end auto-fill closes any residual gap.
        int estimatedTestCount = TestSupport.estimateTestCount(dir.resolve("src/test/java"))
                + TestSupport.estimateTestCount(dir.resolve("src/test/kotlin"));

        // testOnly → the core pipeline runs through run-tests but never packages.
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                true,
                false,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        Pipeline pipeline = BuildPipelines.coreBuilder(inputs).build();

        // Deferred lookup: pipeline.get(TEST_RESULT) is empty until the run-tests step populates it
        // mid-run, so these lambdas must read it at invocation time (when the console listener's
        // own pipelineFinish fires), not eagerly here.
        ConsoleSpec spec = new ConsoleSpec(
                "Test",
                r -> TestCommand.testSummary(pipeline.get(TEST_RESULT).orElse(null), r),
                r -> TestCommand.testFailureMessage(pipeline.get(TEST_RESULT).orElse(null), r));
        PipelineResult result = PipelineConsole.runPipeline(
                pipeline, PipelineConsole.modeFor(global), cache, spec, BuildCommand.buildTarget(buildFile, dir));
        return new PipelineOutcome(result, pipeline.get(TEST_RESULT).orElse(null));
    }

    @Override
    public PipelineResult compilePipeline(
            Path dir,
            Path cache,
            String profileName,
            boolean verbose,
            PipelineConsole.Mode mode,
            ConsoleSpec spec,
            String target)
            throws IOException, InterruptedException {
        // compileOnly → lock → sync → compile. The pipeline resolves jk.lock on
        // first run and re-locks when jk.toml changed; no "run jk lock first".
        Pipeline pipeline = cc.jumpkick.runtime.CompilePipelines.compilePipeline(dir, cache, profileName, verbose);
        return PipelineConsole.runPipeline(pipeline, mode, cache, spec, target);
    }

    @Override
    public PipelineResult auditPipeline(
            Path lockPath,
            Path cache,
            String severity,
            java.net.URI osvBatchUrl,
            java.net.URI osvVulnsUrl,
            HostedEvents.FindingObserver observer,
            PipelineConsole.Mode mode)
            throws IOException, InterruptedException {
        Pipeline pipeline = cc.jumpkick.runtime.AuditPipelines.auditPipeline(
                lockPath, cache, severity, osvBatchUrl, osvVulnsUrl, observer::onFinding);
        return PipelineConsole.run(pipeline, mode, cache);
    }

    @Override
    public FormatPipelineOutcome formatPipeline(
            Path projectDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            Path rewriteConfig,
            HostedEvents.FileObserver observer,
            PipelineListener listener)
            throws IOException {
        Pipeline pipeline = FormatPipelines.formatPipeline(
                projectDir, cache, check, javaStyle, kotlinStyle, optimizeImports, rewriteConfig, observer::onFile);
        pipeline.addListener(listener);
        PipelineResult result = pipeline.run();
        return new FormatPipelineOutcome(
                result,
                pipeline.get(FormatPipelines.CHANGED).orElse(-1),
                pipeline.get(FormatPipelines.CLEAN).orElse(-1),
                pipeline.get(FormatPipelines.ERRORS).orElse(-1),
                pipeline.get(FormatPipelines.TOTAL).orElse(-1),
                pipeline.get(FormatPipelines.WORKER_EXIT).orElse(-1));
    }

    @Override
    public PublishPipelineOutcome publishPipeline(
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
            cc.jumpkick.credential.RepoCredential credential,
            PipelineConsole.Mode mode)
            throws IOException, InterruptedException {
        Pipeline pipeline = PublishPipelines.publishPipeline(
                projectDir,
                cache,
                new PublishPipelines.Request(
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
        PipelineResult result = PipelineConsole.run(pipeline, mode, cache);
        return new PublishPipelineOutcome(
                result, pipeline.get(PublishPipelines.FILES).orElse(0));
    }

    @Override
    public PipelineOutcome imagePipeline(
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
            PipelineConsole.Mode mode,
            String module)
            throws IOException, InterruptedException {
        Pipeline pipeline = ImagePipelines.imagePipeline(
                projectDir, cache, jdksDir, skipTests, verbose, mainClass, registry, tag, tarballArg, dockerExecutable);
        ConsoleSpec spec = new ConsoleSpec("Image", r -> imageSuccessTail(pipeline), r -> "Image build failed", true);
        PipelineResult result = PipelineConsole.runPipeline(pipeline, mode, cache, spec, module);
        return new PipelineOutcome(
                result, pipeline.get(BuildPipelines.TEST_RESULT).orElse(null));
    }

    @Override
    public ImportPipelineOutcome importPipeline(
            Path source,
            Path out,
            Path baseDir,
            Path tmpDir,
            boolean force,
            Path report,
            Path cache,
            HostedEvents.NoteObserver notes)
            throws IOException, InterruptedException {
        Pipeline pipeline =
                CompatPipelines.importPipeline(source, out, baseDir, tmpDir, force, report, cache, notes::onNote);
        PipelineResult result = pipeline.run();
        return new ImportPipelineOutcome(
                result,
                pipeline.get(CompatPipelines.EXIT).orElse(1),
                pipeline.get(CompatPipelines.WARNINGS).orElse(0),
                pipeline.get(CompatPipelines.ERROR).orElse(null),
                pipeline.get(CompatPipelines.DIAG).orElse(null));
    }

    @Override
    public HostedEvents.Provision provision(
            Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException, InterruptedException {
        CompatPipelines.Provision p = CompatPipelines.provision(cache, projectDir, toolsRoot, noDiscover, gradle);
        return new HostedEvents.Provision(p.bin(), p.version(), p.source(), p.error(), p.exit(), p.diag());
    }

    @Override
    public EngineClient.CacheMaintSummary cacheGc(Path cache) throws IOException {
        cc.jumpkick.task.CacheGc.Report report = cc.jumpkick.task.CacheGc.run(cache, false);
        return new EngineClient.CacheMaintSummary(
                report.purgedBlobs(), report.freedBytes(), -1, report.repoLinksRemoved());
    }

    @Override
    public ToolPipelineOutcome toolResolvePipeline(
            cc.jumpkick.model.ToolCoordSpec spec,
            List<cc.jumpkick.model.ToolCoordSpec> with,
            String bin,
            String mainClass,
            java.net.URI repoUrl,
            Path cacheDir,
            String label,
            PipelineConsole.Mode mode)
            throws IOException, InterruptedException {
        Pipeline pipeline = ToolPipelines.resolvePipeline(spec, with, bin, mainClass, repoUrl, cacheDir, label);
        PipelineResult result = PipelineConsole.run(pipeline, mode, cacheDir);
        if (!result.success()) return new ToolPipelineOutcome(result, null);
        return new ToolPipelineOutcome(
                result, pipeline.get(ToolPipelines.TOOL_ENV).orElseThrow());
    }

    // ---- jk lock's test-only in-process path (identical pipeline via LockPipelines) ----------------

    @Override
    public int lockInProcess(
            Path dir,
            Path cache,
            PipelineConsole.Mode mode,
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

        JkBuild effectiveRoot = LockPipelines.applyWorkspaceContextIfModule(dir, root);

        if (!live) {
            // --verbose / --output json: existing simple-task rendering.
            int result = lockSingleProject(
                    dir, effectiveRoot, cache, "Lock", mode, features, noDefaultFeatures, sources, repoUrl);
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
                    int moduleResult = lockSingleProject(
                            moduleDir,
                            effectiveModule,
                            cache,
                            moduleLabel,
                            mode,
                            features,
                            noDefaultFeatures,
                            sources,
                            repoUrl);
                    if (moduleResult != 0) return moduleResult;
                }
            }
            return 0;
        }

        // Live pipeline-mode TUI: one shared CommandManager spanning root + all workspace modules.
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        return runLive(dir, effectiveRoot, cache, animate, features, noDefaultFeatures, sources, repoUrl);
    }

    /**
     * Live path: one {@link CommandManager} in pipeline mode, one row per module, a shared progress bar
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
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();

        String rootCoord = LockPipelines.coordLabel(effectiveRoot, dir);
        int exit = lockModuleLive(
                dir,
                effectiveRoot,
                cache,
                rootCoord,
                view,
                globalLocked,
                errorLines,
                features,
                noDefaultFeatures,
                sources,
                repoUrl);
        if (exit != 0) {
            view.finishPipelineFailure(LockCommand.lockFailTail(), errorLines);
            return exit;
        }

        if (effectiveRoot.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules;
            try {
                modules = WorkspaceLoader.loadModules(dir, effectiveRoot);
            } catch (RuntimeException e) {
                errorLines.add(e.getMessage());
                view.finishPipelineFailure(LockCommand.lockFailTail(), errorLines);
                return Exit.CONFIG;
            }
            for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
                Path moduleDir = entry.getKey();
                JkBuild rawModule = entry.getValue();
                JkBuild effectiveModule = WorkspaceMerge.applyToModule(effectiveRoot, rawModule, modules.values());
                String moduleCoord = LockPipelines.coordLabel(rawModule, moduleDir);
                exit = lockModuleLive(
                        moduleDir,
                        effectiveModule,
                        cache,
                        moduleCoord,
                        view,
                        globalLocked,
                        errorLines,
                        features,
                        noDefaultFeatures,
                        sources,
                        repoUrl);
                if (exit != 0) {
                    view.finishPipelineFailure(LockCommand.lockFailTail(), errorLines);
                    return exit;
                }
            }
        }

        view.finishPipelineSuccess(LockCommand.lockSuccessTail(globalLocked.get(), start));
        return 0;
    }

    /**
     * Lock one module with the pipeline-mode TUI. Registers an active row in {@code view}, runs the full
     * lock pipeline (parse → resolve → lock-plugins → write), feeds per-package completions into the
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
        // Register one step row for this module. The display label is empty so
        // renderActiveRow produces "module › dep" (two segments, not three).
        view.addStepLabeled(coord, "lock", "");
        view.stepRunning(coord, "lock");

        // Lock is purely resolution — total is unknown upfront, so we show a
        // static top-line label and record each resolved dep as a completion line.
        view.solveLabel("Locking versions…");

        ResolveObserver observer = new ResolveObserver() {
            @Override
            public void onTotal(int total) {
                // total growth already rides the pipeline's scope updates
            }

            @Override
            public void onPackage(String pkg, String version) {
                // Show active dep in the step row (module › dep via renderActiveRow).
                view.stepMessage(coord, "lock", Coords.module(pkg, version));
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

        // Build and run the lock pipeline directly (no PipelineConsole wrapper).
        Pipeline pipeline = LockPipelines.lockPipeline(
                dir, effective, cache, repoUrl, features, !noDefaultFeatures, sources, observer, Coords::module);
        PipelineResult result = pipeline.run();

        if (result.success()) {
            view.stepDone(coord, "lock", true);
            return 0;
        }

        // Failure: collect diagnostics, mark row failed.
        view.stepDone(coord, "lock", false);
        for (PipelineResult.Diagnostic d : result.errors()) {
            errorLines.add(ConsoleSpec.renderError(d));
        }
        return LockPipelines.failureExitCode(result);
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
            PipelineConsole.Mode mode,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception {
        Pipeline pipeline = LockPipelines.lockPipeline(
                dir,
                effective,
                cache,
                repoUrl,
                features,
                !noDefaultFeatures,
                sources,
                ResolveObserver.NOOP,
                Coords::module);

        ConsoleSpec spec = new ConsoleSpec(
                label,
                r -> {
                    Lockfile lock = pipeline.get(LockPipelines.LOCKFILE).orElseThrow();
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

        PipelineResult result = PipelineConsole.run(pipeline, mode, cache, spec);
        return result.success() ? 0 : LockPipelines.failureExitCode(result);
    }

    // ---- jk outdated's test-only in-process path (identical computation via OutdatedPipelines) -----

    @Override
    public cc.jumpkick.engine.protocol.OutdatedReport outdatedInProcess(Path dir, Path cache, java.net.URI repoUrl) {
        return cc.jumpkick.runtime.OutdatedPipelines.compute(dir, cache, repoUrl);
    }

    // ---- jk update's test-only in-process path (identical pipeline via LockPipelines) --------------

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
            LockPipelines.GitUpdateOutcome outcome =
                    LockPipelines.updateGitOnly(dir, root, cache, repoUrl, features, !noDefaultFeatures, gitTarget);
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
        JkBuild effectiveRoot = LockPipelines.applyWorkspaceContextIfModule(dir, root);

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
                int moduleResult = updateSingleProject(
                        moduleDir, effectiveModule, cache, features, noDefaultFeatures, repoUrl, global);
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
        Pipeline pipeline = LockPipelines.updatePipeline(dir, effective, cache, repoUrl, features, !noDefaultFeatures);

        PipelineResult result = PipelineConsole.run(pipeline, PipelineConsole.modeFor(global), cache);
        if (!result.success()) {
            return LockPipelines.failureExitCode(result);
        }

        Lockfile lock = pipeline.get(LockPipelines.LOCKFILE).orElseThrow();
        if (!global.outputIsJson()) {
            UpdateCommand.printUpdatedLine(lockFile, lock.artifacts().size(), global.workingDir());
        }
        return 0;
    }

    // ---- jk sync's test-only in-process path (identical pipeline via SyncPipelines) --------------------

    @Override
    public int syncInProcess(
            Path dir,
            Path cache,
            Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            PipelineConsole.Mode mode,
            String targetLabel) {
        java.util.concurrent.atomic.AtomicInteger totalFetched = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalUpToDate = new java.util.concurrent.atomic.AtomicInteger(0);

        Pipeline pipeline = cc.jumpkick.runtime.SyncPipelines.syncPipeline(
                dir,
                cache,
                jdksDir,
                repoUrl,
                sources,
                totalFetched,
                totalUpToDate,
                cc.jumpkick.cli.theme.Coords::module,
                true);

        ConsoleSpec spec = SyncCommand.syncSpec(totalFetched::get, totalUpToDate::get);
        PipelineResult result = PipelineConsole.runPipeline(pipeline, mode, cache, spec, targetLabel);

        if (result.success()) {
            // Opportunistic cache prune — no-op when auto-prune is off.
            var cacheConfig = cc.jumpkick.config.JkCacheConfig.resolve();
            cc.jumpkick.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> cc.jumpkick.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            return 0;
        }
        // The progress-bar listener (or SilentListener on a pipe) has
        // already surfaced the failure — no command-side summary.
        return 1;
    }

    @Override
    public cc.jumpkick.engine.protocol.IdeWireModel ideModel(
            java.nio.file.Path dir, java.nio.file.Path cache, java.nio.file.Path jdksDir) {
        return cc.jumpkick.runtime.IdeOps.ideModel(dir, cache, jdksDir, true);
    }

    @Override
    public PipelineOutcome runBuildPipeline(
            Path projectDir,
            Path cache,
            Path jdksDir,
            boolean skipTests,
            boolean verbose,
            PipelineConsole.Mode mode,
            ConsoleSpec spec,
            String coord)
            throws IOException, InterruptedException {
        // Build through the one pipeline, producing whatever jk.toml declares
        // (jar always; shadow/native when configured). Cache-aware, so a clean
        // tree is near-instant.
        Path lockFile = projectDir.resolve("jk.lock");
        int estimatedTestCount = TestSupport.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                verbose,
                false,
                false,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current());
        Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs);
        BuildPipelines.appendDeclaredTails(builder, inputs);
        Pipeline pipeline = builder.build();
        PipelineResult result = PipelineConsole.runPipeline(pipeline, mode, cache, spec, coord);
        return new PipelineOutcome(
                result, pipeline.get(BuildPipelines.TEST_RESULT).orElse(null));
    }

    @Override
    public cc.jumpkick.runtime.WorkspaceResult buildWorkspace(
            cc.jumpkick.runtime.WorkspaceRequest request, cc.jumpkick.runtime.WorkspaceBuildListener listener) {
        return cc.jumpkick.runtime.BuildService.buildWorkspace(request, listener);
    }

    @Override
    public cc.jumpkick.runtime.ExplainPlan explain(
            Path startDir,
            JkBuild entry,
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
        cc.jumpkick.runtime.ExplainPlan plan = cc.jumpkick.runtime.BuildService.explain(startDir, entry, cache);
        etaOut[0] = plan.hasErrors()
                ? 0
                : cc.jumpkick.runtime.BuildService.estimateEtaMillis(
                        plan, startDir, cache, workers, jdksDir, profile, skipTests, verbose, serial, parallelTests);
        return plan;
    }

    /** Success tail for the in-process path — reads the finished pipeline's structured keys. */
    private static String imageSuccessTail(Pipeline pipeline) {
        JkBuild project = pipeline.get(BuildPipelines.PROJECT).orElse(null);
        cc.jumpkick.image.ImageConfig cfg = pipeline.get(ImagePipelines.CONFIG).orElse(null);
        Path tarballPath = pipeline.get(ImagePipelines.TARBALL_PATH).orElse(null);
        String name = project != null ? project.project().name() : "";
        String version = project != null ? project.project().version() : "";
        boolean daemonMode = tarballPath == null
                && (cfg == null || cfg.registry() == null || cfg.registry().isBlank());
        String daemonExe =
                !daemonMode ? null : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
        String ref =
                pipeline.get(ImagePipelines.IMAGE_REF).orElse(cfg != null ? cfg.targetReference(name, version) : "");
        return ImageCommand.imageSuccessTail(
                tarballPath != null ? tarballPath.toString() : null, name, version, daemonExe, ref);
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
            Pipeline pipeline = cc.jumpkick.runtime.CachePipelines.prunePipeline(
                    root, olderThanDays, dryRun, sweep, maxSize, defaultCacheDir);

            ConsoleSpec spec = CacheCommand.CachePruneCommand.pruneSpec(
                    dryRun,
                    () -> pipeline.get(cc.jumpkick.runtime.CachePipelines.FILES).orElse(0L),
                    () -> pipeline.get(cc.jumpkick.runtime.CachePipelines.BYTES).orElse(0L));

            PipelineResult pipelineResult =
                    PipelineConsole.runPipeline(pipeline, PipelineConsole.modeFor(global), root, spec, "Cache");

            CacheCommand.CachePruneCommand.warnReachableEvicted(
                    pipeline.get(cc.jumpkick.runtime.CachePipelines.REACHABLE_EVICTED)
                            .orElse(0L));

            return pipelineResult.success() ? 0 : 1;
        } finally {
            if (background && !dryRun) {
                try {
                    java.nio.file.Files.writeString(
                            root.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
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
    public int purgeInProcess(Path root, PipelineConsole.Mode mode, ConsoleSpec spec) throws IOException {
        Pipeline pipeline = cc.jumpkick.runtime.CachePipelines.purgePipeline(root);
        PipelineResult pipelineResult = PipelineConsole.runPipeline(pipeline, mode, root, spec, "Cache");
        return pipelineResult.success() ? 0 : 1;
    }

    @Override
    public int clearInProcess(Path root, Path projectDir, boolean dryRun, PipelineConsole.Mode mode)
            throws IOException {
        Pipeline pipeline = cc.jumpkick.runtime.CachePipelines.clearPipeline(root, projectDir, dryRun);
        ConsoleSpec spec = CacheCommand.CacheClearCommand.clearSpec(
                dryRun,
                () -> pipeline.get(cc.jumpkick.runtime.CachePipelines.FILES).orElse(0L),
                () -> pipeline.get(cc.jumpkick.runtime.CachePipelines.BYTES).orElse(0L));
        PipelineResult pipelineResult = PipelineConsole.runPipeline(pipeline, mode, root, spec, "Cache");
        return pipelineResult.success() ? 0 : 1;
    }

    // ---- jk native's test-only in-process cascade (identical pipelines via NativePipelines) ------------

    /** In-process workspace cascade — the test-only bypass; builds the exact same pipelines via {@link NativePipelines}. */
    @Override
    public int nativeWorkspaceInProcess(
            Path wsRoot,
            java.util.Map<Path, JkBuild> modulesByDir,
            java.util.List<Path> sorted,
            Path cache,
            java.util.Map<Path, Path> graalHomes,
            PipelineConsole.Mode mode,
            long buildStart,
            long nativeCount,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extra,
            boolean skipTests,
            boolean verbose)
            throws Exception {
        // JSON / verbose: per-module banners.
        if (mode != PipelineConsole.Mode.AUTO && mode != PipelineConsole.Mode.QUIET) {
            for (int i = 0; i < sorted.size(); i++) {
                Path moduleDir = sorted.get(i);
                JkBuild module = modulesByDir.get(moduleDir);
                CliOutput.out();
                CliOutput.out("══ " + wsRoot.relativize(moduleDir) + " (" + (i + 1) + "/" + sorted.size() + ") ══");
                int exit = runPreparedNative(
                        prepareNativeModule(
                                moduleDir,
                                module,
                                cache,
                                graalHomes.get(moduleDir),
                                jdksDir,
                                mainClass,
                                extra,
                                skipTests,
                                verbose),
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
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Build", animate);
        cc.jumpkick.cli.run.AggregateContext agg = new cc.jumpkick.cli.run.AggregateContext(view);
        int built = 0;

        try (var cap = view.captureOutput()) {
            // Pre-scan every module's pipeline — the core steps plus the
            // native-image tail for eligible modules — and sum its estimated
            // ticks so the bar calibrates to the whole-workspace total up front
            // (the native-image build's W_NATIVE weight and its per-stage ticks
            // included) and advances 0→100% without resetting per module,
            // instead of the denominator growing as modules start. These are the
            // very pipelines we then run, one module at a time.
            List<PreparedNativeModule> prepared = new ArrayList<>(sorted.size());
            long total = 0;
            for (Path moduleDir : sorted) {
                PreparedNativeModule pm = prepareNativeModule(
                        moduleDir,
                        modulesByDir.get(moduleDir),
                        cache,
                        graalHomes.get(moduleDir),
                        jdksDir,
                        mainClass,
                        extra,
                        skipTests,
                        verbose);
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
                    view.finishPipelineFailure(
                            PipelineWedge.coord(moduleName) + " " + BuildCommand.elapsedSince(buildStart));
                    throw e;
                }
                if (exit != 0) {
                    view.finishPipelineFailure(
                            PipelineWedge.coord(moduleName) + " " + BuildCommand.elapsedSince(buildStart));
                    for (PipelineResult.Diagnostic d : agg.lastErrors()) {
                        CliOutput.err(ConsoleSpec.renderError(d));
                    }
                    return exit;
                }
                built++;
            }
        }

        view.finishPipelineSuccess(
                Theme.colorize("Native build successful", Theme.active().success())
                        + ", "
                        + NativeCommand.workspaceSummary(built, nativeCount)
                        + " "
                        + BuildCommand.elapsedSince(buildStart));
        return 0;
    }

    /**
     * Construct (but do not run) one module's pipeline via the shared {@link NativePipelines} factory (the
     * same assembly the engine hosts). Split out of the run step so the workspace path can build
     * every module's pipeline up front and sum {@link Pipeline#estimatedTotalWeight()} — including the
     * native step's weight — to calibrate the shared progress bar before any module runs.
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
        Pipeline pipeline = NativePipelines.modulePipeline(
                moduleDir, module, cache, jdksDir, graalHome, mainClass, extra, skipTests, verbose);
        return new PreparedNativeModule(
                moduleDir,
                BuildCommand.buildTarget(moduleDir.resolve("jk.toml"), moduleDir),
                cache,
                pipeline,
                pipeline.estimatedTotalWeight(),
                NativePipelines.isNativeEligible(module));
    }

    /**
     * Run an already-built module pipeline and map its result to an exit code. When {@code agg} is
     * non-null the module feeds the one shared calibrated bar, scaling its progress into its reserved
     * slice; otherwise it renders on its own (the verbose/JSON per-module path).
     */
    private static int runPreparedNative(
            PreparedNativeModule pm, cc.jumpkick.cli.run.AggregateContext agg, PipelineConsole.Mode mode) {
        PipelineResult result;
        if (agg != null) {
            result = PipelineConsole.runPipelineInto(pm.pipeline(), pm.cache(), pm.target(), agg, pm.barWeight());
        } else {
            String command = pm.eligible() ? "Native build successful" : "Build successful";
            ConsoleSpec spec = new ConsoleSpec(
                    "Build",
                    r -> Theme.colorize(command, Theme.active().success()) + BuildCommand.builtArtifact(pm.pipeline()),
                    r -> PipelineWedge.coord(pm.target()),
                    true);
            result = PipelineConsole.runPipeline(pm.pipeline(), mode, pm.cache(), spec, pm.target());
        }
        return result.success() ? 0 : 1;
    }

    /**
     * A workspace module's pipeline, built and ready to run, paired with its pre-scan bar weight (its
     * slice of the calibrated aggregate total) and whether it carries the native-image tail.
     */
    private record PreparedNativeModule(
            Path dir, String target, Path cache, Pipeline pipeline, long barWeight, boolean eligible) {}

    @Override
    public int nativeSingleInProcess(
            Path projectDir,
            JkBuild build,
            Path cache,
            Path graalHome,
            String coord,
            PipelineConsole.Mode mode,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extra,
            boolean skipTests,
            boolean verbose) {
        Pipeline pipeline = NativePipelines.modulePipeline(
                projectDir, build, cache, jdksDir, graalHome, mainClass, extra, skipTests, verbose);
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(pipeline),
                r -> PipelineWedge.coord(coord),
                true);
        PipelineResult result = PipelineConsole.runPipeline(pipeline, mode, cache, spec, coord);
        return result.success() ? 0 : NativePipelines.failureExitCode(pipeline, result);
    }

    @Override
    public EngineClient.GitFetchOutcome gitFetchPipeline(
            String url,
            String canonicalUrl,
            String ref,
            Path cache,
            boolean refresh,
            boolean requireJkToml,
            PipelineConsole.Mode mode)
            throws IOException, InterruptedException {
        Pipeline fetchPipeline = cc.jumpkick.runtime.InstallPipelines.gitFetchPipeline(
                url, canonicalUrl, ref, cache, refresh, requireJkToml);
        PipelineResult result = PipelineConsole.run(fetchPipeline, mode, cache);
        return new EngineClient.GitFetchOutcome(
                result,
                fetchPipeline.get(cc.jumpkick.runtime.InstallPipelines.CHECKOUT).orElse(null),
                fetchPipeline
                        .get(cc.jumpkick.runtime.InstallPipelines.FETCHED_SHA)
                        .orElse(null));
    }

    @Override
    public PipelineOutcome installProjectPipeline(
            Path projectDir,
            Path cacheDir,
            Path m2Dir,
            boolean skipTests,
            boolean verbose,
            Path graalHome,
            PipelineConsole.Mode mode)
            throws IOException {
        Pipeline pipeline = cc.jumpkick.runtime.InstallPipelines.projectInstallPipeline(
                projectDir, cacheDir, m2Dir, skipTests, verbose, graalHome);
        PipelineResult result = PipelineConsole.run(pipeline, mode, cacheDir);
        return new PipelineOutcome(
                result, pipeline.get(BuildPipelines.TEST_RESULT).orElse(null));
    }

    // ---- jk build's test-only in-process paths -------------------------------------------------

    @Override
    public cc.jumpkick.runtime.BuildForecast forecast(Path entryDir, JkBuild entryBuild, Path cache, boolean skipTests)
            throws IOException {
        cc.jumpkick.runtime.BuildService.ResolvedGraph graph =
                cc.jumpkick.runtime.BuildService.resolveGraph(entryDir, entryBuild);
        if (graph.hasErrors()) {
            return new cc.jumpkick.runtime.BuildForecast(java.util.Set.of(), false, false, graph.errors());
        }
        java.util.Set<Path> dirty = cc.jumpkick.runtime.BuildService.forecastDirtyDirs(graph, cache, skipTests);
        boolean lockStale =
                cc.jumpkick.runtime.BuildService.workspaceLockStale(entryDir, entryBuild, entryDir.resolve("jk.lock"));
        return new cc.jumpkick.runtime.BuildForecast(dirty, lockStale, graph.isEmpty(), java.util.List.of());
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
        boolean parallelTests = cc.jumpkick.config.SessionContext.current().parallelTests();
        int requested = cc.jumpkick.engine.plugin.HeapPlan.requestedJvms(1, workers, parallelTests, cap);
        cc.jumpkick.engine.plugin.HeapPlan.Plan plan = cc.jumpkick.engine.plugin.JvmOptions.planAndApply(requested);
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
        // run-tests pre-discovery so the step's ticks is known before any step runs.
        BuildPipelines.Inputs inputs = cc.jumpkick.runtime.BuildPlanForecast.inputsFor(
                dir, cache, workers, jdksDir, profileName, skipTests, global.verbose, java.util.Set.of());
        // Quick pre-check: is every work step already cached? Uses only stat/CAS
        // lookups — the same operations the parse-build step would run lazily.
        boolean fullyCached = false;
        try {
            cc.jumpkick.cache.Cas cas = new cc.jumpkick.cache.Cas(inputs.cache());
            JkBuild build = JkBuildParser.parse(buildFile);
            CompileSupport.Languages langs = CompileSupport.resolveLanguages(build.project(), dir);
            boolean compact = CompileSupport.isSimpleLayout(build.project(), dir);
            EffortWeights.Plan effort = EffortWeights.predict(inputs, cas, compact, langs.java(), langs.kotlin());
            fullyCached = effort.fullyCached();
        } catch (Exception ignored) {
            // best-effort; proceed normally if prediction throws
        }
        String target = BuildCommand.buildTarget(buildFile, dir);
        // Single-module fast path: skip the TUI entirely when every work step is already cached.
        if (fullyCached && PipelineConsole.isInteractiveTerminal() && !global.outputIsJson()) {
            CliOutput.out(PipelineWedge.chipLine(
                    cc.jumpkick.cli.tui.Glyphs.CHECK,
                    "Build",
                    cc.jumpkick.config.GlobalConfig.nerdfont(),
                    BuildCommand.buildOk() + ", project up to date " + BuildCommand.elapsedSince(startNanos)));
            return 0;
        }
        Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs);
        BuildPipelines.appendDeclaredTails(builder, inputs);
        Pipeline pipeline = builder.build();
        long barWeight = pipeline.estimatedTotalWeight();
        // chip = true → settle through the pipeline chip (" ✓ Build ▶ Build successful …"),
        // matching the workspace path. onSuccess/onFailure return the tail after the command.
        ConsoleSpec spec = new ConsoleSpec(
                "Build", r -> BuildCommand.projectTail(pipeline), r -> PipelineWedge.coord(target), true);
        PipelineResult result =
                PipelineConsole.runPipeline(pipeline, PipelineConsole.modeFor(global), cache, spec, target);
        TestSummary testResult = pipeline.get(TEST_RESULT).orElse(null);
        if (result.success()) {
            // Fold this run's measured throughput into the host calibration so the cold estimate
            // self-heals. Skip fully-cached runs (near-zero time, not representative of work).
            if (!fullyCached && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    cc.jumpkick.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
            }
            // Cache settings are user-global only; resolve() reads ~/.jk/config.toml
            // (a project jk.toml's [cache] is intentionally ignored).
            var cacheConfig = cc.jumpkick.config.JkCacheConfig.resolve();
            cc.jumpkick.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> cc.jumpkick.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
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
            PipelineConsole.Mode consoleMode)
            throws IOException, InterruptedException {
        List<cc.jumpkick.model.Dependency> extraDeps = with.stream()
                .map(cc.jumpkick.script.ScriptHeaderParser::parseDependency)
                .toList();
        Pipeline pipeline =
                switch (mode) {
                    case "java" ->
                        cc.jumpkick.runtime.ScriptPipelines.javaScriptPipeline(
                                script, cacheDir, stateDir, repoUrl, forceRecompile, extraDeps);
                    case "kt" ->
                        cc.jumpkick.runtime.ScriptPipelines.kotlinScriptPipeline(
                                script, cacheDir, stateDir, repoUrl, forceRecompile, extraDeps);
                    case "kts" ->
                        cc.jumpkick.runtime.ScriptPipelines.ktsScriptPipeline(script, cacheDir, repoUrl, extraDeps);
                    case "jar" -> cc.jumpkick.runtime.ScriptPipelines.jarPipeline(script, cacheDir, repoUrl);
                    default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                };
        PipelineResult result = PipelineConsole.run(pipeline, consoleMode, cacheDir);
        return new EngineClient.ScriptPrepareOutcome(
                result,
                pipeline.get(cc.jumpkick.runtime.ScriptPipelines.MAIN_CLASS).orElse(null),
                cc.jumpkick.runtime.ScriptPipelines.classpathOf(pipeline),
                pipeline.get(cc.jumpkick.runtime.ScriptPipelines.CLASSES_DIR).orElse(null),
                pipeline.get(cc.jumpkick.runtime.ScriptPipelines.KOTLINC_BIN).orElse(null),
                pipeline.get(cc.jumpkick.runtime.ScriptPipelines.KT_STDLIB).orElse(null));
    }
}
