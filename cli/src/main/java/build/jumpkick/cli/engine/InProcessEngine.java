// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.engine;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The client-side seam to an engine hosted <em>in this process</em> — the slim-client Stage 5
 * counterpart of {@link EngineClient}'s wire calls. The one implementation lives in the
 * {@code :cli-engine} module (which links the full engine kernel) and is discovered via {@link
 * ServiceLoader}, so whether an in-process engine exists is purely a classpath fact:
 *
 * <ul>
 *   <li><b>JVM dist / Gradle test classpath:</b> {@code :cli-engine} is present — {@code jk
 *       --engine-server} works, and the test-only {@code jk.test.noEngine} bypass (see {@code
 *       BuildCommand.engineDisabledForTests}) can run every command's pipelines in-process.
 *   <li><b>Client native image:</b> {@code :cli-engine} is absent by construction — the binary
 *       physically cannot host an engine, which is the point of the dependency cut. Engine work
 *       reaches a resident engine over the wire ({@link EngineClient}), never in-process.
 * </ul>
 *
 * <p>Methods mirror the command layer's in-process fallback branches one-to-one; each is the exact
 * code that used to live inline in the command class, so the two dispatch paths (wire vs in-process)
 * stay behaviourally identical where they always were.
 */
public interface InProcessEngine {

    /**
     * Run the engine-server loop in this process ({@code jk --engine-server} — the JVM-dist route
     * and the no-sibling spawn fallback). Blocks until shutdown; returns the process exit code.
     */
    int engineServerMain();

    /** Thin-client project summary — the in-process twin of PROJECT_INFO_REQUEST. */
    build.jumpkick.engine.protocol.ProjectInfo projectInfo(java.nio.file.Path dir);

    /** Thin-client execution plan — the in-process twin of EXEC_PLAN_REQUEST. */
    build.jumpkick.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir, java.nio.file.Path cache, String kind, String mainOverride, String binName);

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    build.jumpkick.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName,
            java.nio.file.Path binDir,
            java.nio.file.Path libDir);

    /**
     * Parse a project's jk.toml — used ONLY by the in-process test seams. Living behind this
     * reflectively-loaded interface keeps JkBuildParser (and tomlj) out of the native image's
     * STATIC reachable set: a seam branch that called the parser directly would drag the whole
     * TOML stack into the binary even though the branch never executes there.
     */
    build.jumpkick.model.JkBuild parseBuild(java.nio.file.Path buildFile) throws java.io.IOException;

    /** Thin-client edit twin: returns {changedAsString, errorOrNull}. */
    String[] edit(java.nio.file.Path file, String op, java.util.List<String> args);

    /** Thin-client deny-check twin — the in-process counterpart of DENY_CHECK_REQUEST. */
    build.jumpkick.engine.protocol.DenyReport denyCheck(java.nio.file.Path dir);

    /** Thin-client tree twin: the marker-tagged render (TREE_REQUEST's in-process counterpart). */
    String treeRender(
            java.nio.file.Path dir, int maxDepth, boolean flatten, boolean stack, java.util.List<String> scopes)
            throws java.io.IOException;

    /** Thin-client why twin — the in-process counterpart of WHY_REQUEST. */
    build.jumpkick.engine.protocol.WhyReport why(java.nio.file.Path dir, String query);

    /** Parse a workspace root and load its module models — test-seam-only, like parseBuild. */
    java.util.Map<java.nio.file.Path, build.jumpkick.model.JkBuild> loadWorkspaceModules(java.nio.file.Path wsRoot)
            throws java.io.IOException;

    /** Thin-client generator twin — the in-process counterpart of GENERATE_REQUEST. */
    build.jumpkick.engine.protocol.GeneratedFiles generate(java.nio.file.Path dir, String kind);

    /** As above with generator parameters (scaffold inputs etc.). */
    build.jumpkick.engine.protocol.GeneratedFiles generate(
            java.nio.file.Path dir, String kind, java.util.Map<String, String> params);

    /** Plugin-command twin — the in-process counterpart of PLUGIN_VERB_REQUEST. */
    build.jumpkick.engine.protocol.PluginCommandReport pluginCommand(
            java.nio.file.Path dir, java.nio.file.Path cache, String command, java.util.List<String> args);

    /** A single hosted pipeline's outcome: the pipeline result plus the test counts (null when no tests ran). */
    record PipelineOutcome(build.jumpkick.run.PipelineResult result, build.jumpkick.run.TestSummary testResult) {}

    /** {@code jk test}'s in-process pipeline: assemble the test-only core pipeline and run it on the console. */
    PipelineOutcome testPipeline(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path buildFile,
            java.nio.file.Path lockFile,
            int workerCount,
            String profileName,
            java.nio.file.Path jdksDir,
            build.jumpkick.cli.GlobalOptions global)
            throws java.io.IOException, InterruptedException;

    /** {@code jk compile}'s in-process pipeline: lock → sync → compile, run on the console. */
    build.jumpkick.run.PipelineResult compilePipeline(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String profileName,
            boolean verbose,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            build.jumpkick.cli.run.ConsoleSpec spec,
            String target)
            throws java.io.IOException, InterruptedException;

    /** {@code jk audit}'s in-process pipeline: fork the auditor worker and stream findings. */
    build.jumpkick.run.PipelineResult auditPipeline(
            java.nio.file.Path lockPath,
            java.nio.file.Path cache,
            String severity,
            java.net.URI osvBatchUrl,
            java.net.URI osvVulnsUrl,
            build.jumpkick.runtime.HostedEvents.FindingObserver observer,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** A finished in-process {@code jk format} pipeline's summary counts (mirrors the hosted pipeline-finish). */
    record FormatPipelineOutcome(
            build.jumpkick.run.PipelineResult result, int changed, int clean, int errors, int total, int workerExit) {}

    /** {@code jk format}'s in-process pipeline: fork the formatter worker and stream per-file results. */
    FormatPipelineOutcome formatPipeline(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            java.nio.file.Path rewriteConfig,
            build.jumpkick.runtime.HostedEvents.FileObserver observer,
            build.jumpkick.run.PipelineListener listener)
            throws java.io.IOException;

    /** A finished in-process {@code jk publish} pipeline: the result plus the published-file count. */
    record PublishPipelineOutcome(build.jumpkick.run.PipelineResult result, int files) {}

    /** {@code jk publish}'s in-process pipeline: fork the publisher worker with pre-resolved credentials. */
    PublishPipelineOutcome publishPipeline(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            java.net.URI repoUrl,
            String region,
            String endpoint,
            java.nio.file.Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            java.nio.file.Path keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            build.jumpkick.credential.RepoCredential credential,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk image}'s in-process pipeline: full pipeline + the image-builder worker fork. */
    PipelineOutcome imagePipeline(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            boolean skipTests,
            boolean verbose,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutable,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            String module)
            throws java.io.IOException, InterruptedException;

    /** A finished in-process {@code jk import} pipeline's summary (mirrors the hosted pipeline-finish). */
    record ImportPipelineOutcome(build.jumpkick.run.PipelineResult result, int exitCode, int warnings, String error, String diag) {}

    /**
     * {@code jk import}'s in-process pipeline: fork the compat-bridge worker. A missing worker jar
     * throws {@code WorkerJarNotFoundException} here, which {@code CommandDispatch} renders with
     * side-load hints.
     */
    ImportPipelineOutcome importPipeline(
            java.nio.file.Path source,
            java.nio.file.Path out,
            java.nio.file.Path baseDir,
            java.nio.file.Path tmpDir,
            boolean force,
            java.nio.file.Path report,
            java.nio.file.Path cache,
            build.jumpkick.runtime.HostedEvents.NoteObserver notes)
            throws java.io.IOException, InterruptedException;

    /** {@code jk mvn}/{@code jk gradle}'s in-process provisioning (compat-bridge worker fork). */
    build.jumpkick.runtime.HostedEvents.Provision provision(
            java.nio.file.Path cache,
            java.nio.file.Path projectDir,
            java.nio.file.Path toolsRoot,
            boolean noDiscover,
            boolean gradle)
            throws java.io.IOException, InterruptedException;

    /** {@code jk clean --cache}'s in-process GC (test-only; hosted runs ride the prune vocabulary). */
    build.jumpkick.cli.engine.EngineClient.CacheMaintSummary cacheGc(java.nio.file.Path cache)
            throws java.io.IOException;

    /** A finished in-process tool resolution: the pipeline result plus the launch env (null on failure). */
    record ToolPipelineOutcome(build.jumpkick.run.PipelineResult result, build.jumpkick.tool.ToolEnv env) {}

    /**
     * {@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s in-process
     * resolution: the POM walk + jar fetches, returning the launch env (already rendered on failure).
     */
    ToolPipelineOutcome toolResolvePipeline(
            build.jumpkick.model.ToolCoordSpec spec,
            java.util.List<build.jumpkick.model.ToolCoordSpec> with,
            String bin,
            String mainClass,
            java.net.URI repoUrl,
            java.nio.file.Path cacheDir,
            String label,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk lock}'s in-process workspace cascade (test-only; mirrors the hosted request). */
    int lockInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            boolean live,
            java.util.List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl)
            throws Exception;

    /** {@code jk update}'s in-process re-resolve cascade (test-only; mirrors the hosted request). */
    int updateInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            boolean gitOnly,
            String gitTarget,
            java.util.List<String> features,
            boolean noDefaultFeatures,
            java.net.URI repoUrl,
            build.jumpkick.cli.GlobalOptions global)
            throws Exception;

    /** {@code jk outdated}'s in-process report (test-only; mirrors the hosted OUTDATED_REQUEST). */
    build.jumpkick.engine.protocol.OutdatedReport outdatedInProcess(
            java.nio.file.Path dir, java.nio.file.Path cache, java.net.URI repoUrl);

    /** {@code jk sync}'s in-process pipeline (test-only; mirrors the hosted request, prune spawn included). */
    int syncInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            String targetLabel);

    /**
     * {@code jk ide}'s in-process model twin (IDE_MODEL_REQUEST's counterpart) — keeps the
     * pre-Wave-4 in-line fetch of missing locked JARs, so the test path builds the same model
     * with no engine.
     */
    build.jumpkick.engine.protocol.IdeWireModel ideModel(
            java.nio.file.Path dir, java.nio.file.Path cache, java.nio.file.Path jdksDir);

    /** {@code jk run}'s in-process build half (test-only): the full declared pipeline, exec stays client-side. */
    PipelineOutcome runBuildPipeline(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            boolean skipTests,
            boolean verbose,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            build.jumpkick.cli.run.ConsoleSpec spec,
            String coord)
            throws java.io.IOException, InterruptedException;

    /** A whole workspace build, in-process ({@code BuildService.buildWorkspace}) — test-only. */
    build.jumpkick.runtime.WorkspaceResult buildWorkspace(
            build.jumpkick.runtime.WorkspaceRequest request, build.jumpkick.runtime.WorkspaceBuildListener listener);

    /**
     * {@code jk explain}'s in-process forecast + schedule-aware ETA (test-only). {@code etaOut[0]}
     * receives the estimate in millis ({@code 0} = unknown), mirroring the hosted {@code eta} event.
     */
    build.jumpkick.runtime.ExplainPlan explain(
            java.nio.file.Path startDir,
            build.jumpkick.model.JkBuild entry,
            java.nio.file.Path cache,
            int workers,
            java.nio.file.Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean serial,
            boolean parallelTests,
            long[] etaOut)
            throws java.io.IOException;

    /**
     * {@code jk cache prune}'s in-process run: the test-only bypass and the legacy {@code
     * --background} detached child (flock + log redirect + stamp write live in the implementation).
     */
    int pruneInProcess(
            java.nio.file.Path root,
            boolean defaultCacheDir,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            String maxSize,
            boolean background,
            build.jumpkick.cli.GlobalOptions global)
            throws java.io.IOException;

    /** {@code jk cache purge}'s in-process delete (test-only; the confirm/stats stay in the command). */
    int purgeInProcess(
            java.nio.file.Path root,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            build.jumpkick.cli.run.ConsoleSpec spec)
            throws java.io.IOException;

    /**
     * {@code jk cache clear}'s in-process invalidation (test-only; the jk.toml check + confirm stay
     * in the command). Invalidates the action-cache entries for {@code projectDir} and its workspace.
     */
    int clearInProcess(
            java.nio.file.Path root,
            java.nio.file.Path projectDir,
            boolean dryRun,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException;

    /** {@code jk native}'s in-process workspace cascade (test-only; identical pipelines via {@code NativePipelines}). */
    int nativeWorkspaceInProcess(
            java.nio.file.Path wsRoot,
            java.util.Map<java.nio.file.Path, build.jumpkick.model.JkBuild> modulesByDir,
            java.util.List<java.nio.file.Path> sorted,
            java.nio.file.Path cache,
            java.util.Map<java.nio.file.Path, java.nio.file.Path> graalHomes,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            long buildStart,
            long nativeCount,
            java.nio.file.Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            boolean skipTests,
            boolean verbose)
            throws Exception;

    /** {@code jk native}'s in-process single-project pipeline (test-only). */
    int nativeSingleInProcess(
            java.nio.file.Path projectDir,
            build.jumpkick.model.JkBuild build,
            java.nio.file.Path cache,
            java.nio.file.Path graalHome,
            String coord,
            build.jumpkick.cli.run.PipelineConsole.Mode mode,
            java.nio.file.Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            boolean skipTests,
            boolean verbose);

    /** {@code jk install <git-url>}'s in-process clone (test-only; git runs in-process). */
    build.jumpkick.cli.engine.EngineClient.GitFetchOutcome gitFetchPipeline(
            String url,
            String canonicalUrl,
            String ref,
            java.nio.file.Path cache,
            boolean refresh,
            boolean requireJkToml,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk install}'s in-process build + cache-install pipeline (test-only). */
    PipelineOutcome installProjectPipeline(
            java.nio.file.Path projectDir,
            java.nio.file.Path cacheDir,
            java.nio.file.Path m2Dir,
            boolean skipTests,
            boolean verbose,
            java.nio.file.Path graalHome,
            build.jumpkick.cli.run.PipelineConsole.Mode mode)
            throws java.io.IOException;

    /** {@code jk build}'s in-process pre-flight forecast (test-only; mirrors {@code forecast-request}). */
    build.jumpkick.runtime.BuildForecast forecast(
            java.nio.file.Path entryDir,
            build.jumpkick.model.JkBuild entryBuild,
            java.nio.file.Path cache,
            boolean skipTests)
            throws java.io.IOException;

    /**
     * {@code jk build}'s in-process single-project build (test-only): memory plan, pipeline assembly,
     * console run, fully-cached chip fast path, calibration refine + opportunistic prune — the
     * exact pre-Stage-5 in-process flow.
     */
    int buildProjectInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            int workers,
            String profileName,
            boolean skipTests,
            build.jumpkick.cli.GlobalOptions global,
            long startNanos)
            throws Exception;

    /**
     * {@code jk tool run <file>}'s in-process preparation (test-only): the same {@code ScriptPipelines}
     * pipeline the engine hosts, run on the console; the exec stays in the calling command.
     */
    build.jumpkick.cli.engine.EngineClient.ScriptPrepareOutcome scriptPrepare(
            String mode,
            java.nio.file.Path script,
            java.nio.file.Path cacheDir,
            java.nio.file.Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            java.util.List<String> with,
            build.jumpkick.cli.run.PipelineConsole.Mode consoleMode)
            throws java.io.IOException, InterruptedException;

    /** As above without extra deps. */
    default build.jumpkick.cli.engine.EngineClient.ScriptPrepareOutcome scriptPrepare(
            String mode,
            java.nio.file.Path script,
            java.nio.file.Path cacheDir,
            java.nio.file.Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            build.jumpkick.cli.run.PipelineConsole.Mode consoleMode)
            throws java.io.IOException, InterruptedException {
        return scriptPrepare(mode, script, cacheDir, stateDir, repoUrl, forceRecompile, java.util.List.of(),
                consoleMode);
    }

    /** The discovered implementation, or empty when {@code :cli-engine} is not on the classpath. */
    static Optional<InProcessEngine> find() {
        return Holder.INSTANCE;
    }

    /**
     * The implementation, or an {@link IllegalStateException} naming the missing module — for the
     * test-only in-process dispatch, where absence is a harness misconfiguration, not a user state.
     */
    static InProcessEngine require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "in-process engine requested but :cli-engine is not on the classpath "
                        + "(the slim client binary cannot host an engine)"));
    }

    /** Lazy one-shot ServiceLoader lookup. */
    final class Holder {
        private Holder() {}

        private static final Optional<InProcessEngine> INSTANCE =
                ServiceLoader.load(InProcessEngine.class).findFirst();
    }
}
