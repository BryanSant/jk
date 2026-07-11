// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

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
 *       BuildCommand.engineDisabledForTests}) can run every verb's goals in-process.
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
    dev.jkbuild.engine.protocol.ProjectInfo projectInfo(java.nio.file.Path dir);

    /** Thin-client execution plan — the in-process twin of EXEC_PLAN_REQUEST. */
    dev.jkbuild.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir, java.nio.file.Path cache, String kind, String mainOverride, String binName);

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    dev.jkbuild.engine.protocol.ExecPlan execPlan(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName,
            java.nio.file.Path binDir,
            java.nio.file.Path libDir);

    /** A single hosted goal's outcome: the goal result plus the test counts (null when no tests ran). */
    record GoalOutcome(dev.jkbuild.run.GoalResult result, dev.jkbuild.run.TestSummary testResult) {}

    /** {@code jk test}'s in-process goal: assemble the test-only core pipeline and run it on the console. */
    GoalOutcome testGoal(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path buildFile,
            java.nio.file.Path lockFile,
            int workerCount,
            String profileName,
            java.nio.file.Path jdksDir,
            dev.jkbuild.cli.GlobalOptions global)
            throws java.io.IOException, InterruptedException;

    /** {@code jk compile}'s in-process goal: lock → sync → compile, run on the console. */
    dev.jkbuild.run.GoalResult compileGoal(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String profileName,
            boolean verbose,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            dev.jkbuild.cli.run.ConsoleSpec spec,
            String target)
            throws java.io.IOException, InterruptedException;

    /** {@code jk audit}'s in-process goal: fork the auditor worker and stream findings. */
    dev.jkbuild.run.GoalResult auditGoal(
            java.nio.file.Path lockPath,
            java.nio.file.Path cache,
            String severity,
            java.net.URI osvBatchUrl,
            java.net.URI osvVulnsUrl,
            dev.jkbuild.runtime.HostedEvents.FindingObserver observer,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** A finished in-process {@code jk format} goal's summary counts (mirrors the hosted goal-finish). */
    record FormatGoalOutcome(
            dev.jkbuild.run.GoalResult result, int changed, int clean, int errors, int total, int workerExit) {}

    /** {@code jk format}'s in-process goal: fork the formatter worker and stream per-file results. */
    FormatGoalOutcome formatGoal(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            java.nio.file.Path rewriteConfig,
            dev.jkbuild.runtime.HostedEvents.FileObserver observer,
            dev.jkbuild.run.GoalListener listener)
            throws java.io.IOException;

    /** A finished in-process {@code jk publish} goal: the result plus the published-file count. */
    record PublishGoalOutcome(dev.jkbuild.run.GoalResult result, int files) {}

    /** {@code jk publish}'s in-process goal: fork the publisher worker with pre-resolved credentials. */
    PublishGoalOutcome publishGoal(
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
            dev.jkbuild.credential.RepoCredential credential,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk image}'s in-process goal: full pipeline + the image-builder worker fork. */
    GoalOutcome imageGoal(
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
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            String module)
            throws java.io.IOException, InterruptedException;

    /** A finished in-process {@code jk import} goal's summary (mirrors the hosted goal-finish). */
    record ImportGoalOutcome(dev.jkbuild.run.GoalResult result, int exitCode, int warnings, String error, String diag) {}

    /**
     * {@code jk import}'s in-process goal: fork the compat-bridge worker. A missing worker jar
     * throws {@code WorkerJarNotFoundException} here, which {@code CommandDispatch} renders with
     * side-load hints.
     */
    ImportGoalOutcome importGoal(
            java.nio.file.Path source,
            java.nio.file.Path out,
            java.nio.file.Path baseDir,
            java.nio.file.Path tmpDir,
            boolean force,
            java.nio.file.Path report,
            java.nio.file.Path cache,
            dev.jkbuild.runtime.HostedEvents.NoteObserver notes)
            throws java.io.IOException, InterruptedException;

    /** {@code jk mvn}/{@code jk gradle}'s in-process provisioning (compat-bridge worker fork). */
    dev.jkbuild.runtime.HostedEvents.Provision provision(
            java.nio.file.Path cache,
            java.nio.file.Path projectDir,
            java.nio.file.Path toolsRoot,
            boolean noDiscover,
            boolean gradle)
            throws java.io.IOException, InterruptedException;

    /** {@code jk clean --cache}'s in-process GC (test-only; hosted runs ride the prune vocabulary). */
    dev.jkbuild.cli.engine.EngineClient.CacheMaintSummary cacheGc(java.nio.file.Path cache)
            throws java.io.IOException;

    /** A finished in-process tool resolution: the goal result plus the launch env (null on failure). */
    record ToolGoalOutcome(dev.jkbuild.run.GoalResult result, dev.jkbuild.tool.ToolEnv env) {}

    /**
     * {@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s in-process
     * resolution: the POM walk + jar fetches, returning the launch env (already rendered on failure).
     */
    ToolGoalOutcome toolResolveGoal(
            dev.jkbuild.model.ToolCoordSpec spec,
            java.util.List<dev.jkbuild.model.ToolCoordSpec> with,
            String bin,
            String mainClass,
            java.net.URI repoUrl,
            java.nio.file.Path cacheDir,
            String label,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk lock}'s in-process workspace cascade (test-only; mirrors the hosted request). */
    int lockInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
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
            dev.jkbuild.cli.GlobalOptions global)
            throws Exception;

    /** {@code jk sync}'s in-process goal (test-only; mirrors the hosted request, prune spawn included). */
    int syncInProcess(
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            String targetLabel);

    /**
     * {@code jk ide}'s test-only in-line fetch of missing locked JARs (the pre-Wave-4 behavior;
     * a real invocation runs the hosted workspace sync instead). Best-effort — never throws.
     */
    void ideFetchMissing(dev.jkbuild.cache.Cas cas, dev.jkbuild.lock.Lockfile lock);

    /** {@code jk run}'s in-process build half (test-only): the full declared pipeline, exec stays client-side. */
    GoalOutcome runBuildGoal(
            java.nio.file.Path projectDir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir,
            boolean skipTests,
            boolean verbose,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            dev.jkbuild.cli.run.ConsoleSpec spec,
            String coord)
            throws java.io.IOException, InterruptedException;

    /** A whole workspace build, in-process ({@code BuildService.buildWorkspace}) — test-only. */
    dev.jkbuild.runtime.WorkspaceResult buildWorkspace(
            dev.jkbuild.runtime.WorkspaceRequest request, dev.jkbuild.runtime.WorkspaceBuildListener listener);

    /**
     * {@code jk explain}'s in-process forecast + schedule-aware ETA (test-only). {@code etaOut[0]}
     * receives the estimate in millis ({@code 0} = unknown), mirroring the hosted {@code eta} event.
     */
    dev.jkbuild.runtime.ExplainPlan explain(
            java.nio.file.Path startDir,
            dev.jkbuild.model.JkBuild entry,
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
            dev.jkbuild.cli.GlobalOptions global)
            throws java.io.IOException;

    /** {@code jk cache purge}'s in-process delete (test-only; the confirm/stats stay in the command). */
    int purgeInProcess(
            java.nio.file.Path root,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            dev.jkbuild.cli.run.ConsoleSpec spec)
            throws java.io.IOException;

    /**
     * {@code jk cache clear}'s in-process invalidation (test-only; the jk.toml check + confirm stay
     * in the command). Invalidates the action-cache entries for {@code projectDir} and its workspace.
     */
    int clearInProcess(
            java.nio.file.Path root,
            java.nio.file.Path projectDir,
            boolean dryRun,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException;

    /** {@code jk native}'s in-process workspace cascade (test-only; identical goals via {@code NativeGoals}). */
    int nativeWorkspaceInProcess(
            java.nio.file.Path wsRoot,
            java.util.Map<java.nio.file.Path, dev.jkbuild.model.JkBuild> modulesByDir,
            java.util.List<java.nio.file.Path> sorted,
            java.nio.file.Path cache,
            java.util.Map<java.nio.file.Path, java.nio.file.Path> graalHomes,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            long buildStart,
            long nativeCount,
            java.nio.file.Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            boolean skipTests,
            boolean verbose)
            throws Exception;

    /** {@code jk native}'s in-process single-project goal (test-only). */
    int nativeSingleInProcess(
            java.nio.file.Path projectDir,
            dev.jkbuild.model.JkBuild build,
            java.nio.file.Path cache,
            java.nio.file.Path graalHome,
            String coord,
            dev.jkbuild.cli.run.GoalConsole.Mode mode,
            java.nio.file.Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            boolean skipTests,
            boolean verbose);

    /** {@code jk install <git-url>}'s in-process clone (test-only; the git-client worker forks locally). */
    dev.jkbuild.cli.engine.EngineClient.GitFetchOutcome gitFetchGoal(
            String url,
            String canonicalUrl,
            String ref,
            java.nio.file.Path cache,
            boolean refresh,
            boolean requireJkToml,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException, InterruptedException;

    /** {@code jk install}'s in-process build + cache-install goal (test-only). */
    GoalOutcome installProjectGoal(
            java.nio.file.Path projectDir,
            java.nio.file.Path cacheDir,
            java.nio.file.Path m2Dir,
            boolean skipTests,
            boolean verbose,
            java.nio.file.Path graalHome,
            dev.jkbuild.cli.run.GoalConsole.Mode mode)
            throws java.io.IOException;

    /** {@code jk build}'s in-process pre-flight forecast (test-only; mirrors {@code forecast-request}). */
    dev.jkbuild.runtime.BuildForecast forecast(
            java.nio.file.Path entryDir,
            dev.jkbuild.model.JkBuild entryBuild,
            java.nio.file.Path cache,
            boolean skipTests)
            throws java.io.IOException;

    /**
     * {@code jk build}'s in-process single-project build (test-only): memory plan, goal assembly,
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
            dev.jkbuild.cli.GlobalOptions global,
            long startNanos)
            throws Exception;

    /**
     * {@code jk tool run <file>}'s in-process preparation (test-only): the same {@code ScriptGoals}
     * goal the engine hosts, run on the console; the exec stays in the calling command.
     */
    dev.jkbuild.cli.engine.EngineClient.ScriptPrepareOutcome scriptPrepare(
            String mode,
            java.nio.file.Path script,
            java.nio.file.Path cacheDir,
            java.nio.file.Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            java.util.List<String> with,
            dev.jkbuild.cli.run.GoalConsole.Mode consoleMode)
            throws java.io.IOException, InterruptedException;

    /** As above without extra deps. */
    default dev.jkbuild.cli.engine.EngineClient.ScriptPrepareOutcome scriptPrepare(
            String mode,
            java.nio.file.Path script,
            java.nio.file.Path cacheDir,
            java.nio.file.Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            dev.jkbuild.cli.run.GoalConsole.Mode consoleMode)
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
