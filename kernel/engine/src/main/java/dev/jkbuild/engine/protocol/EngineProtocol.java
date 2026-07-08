// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The engine wire protocol's message vocabulary: one newline-delimited JSON object per message, each
 * carrying a {@code "t"} type discriminator — the same canonical envelope shape {@link
 * dev.jkbuild.worker.WorkerClient} already uses for jk's worker-process protocol (see {@code
 * docs/engine.md}). Deliberately internal and unversioned: the client can only ever be a same-version
 * {@code jk} binary (enforced by the engine spawn path), so this vocabulary is free to change between
 * jk releases.
 *
 * <p>Phase 1: connection handshake ({@link #HELLO}/{@link #HELLO_ACK}), liveness ({@link
 * #PING}/{@link #PONG}), status reporting, and graceful shutdown.
 *
 * <p>Phase 2 adds {@code buildWorkspace} hosting: a {@link #BUILD_REQUEST}, one event type per {@code
 * WorkspaceBuildListener}/{@code GoalListener} callback, and a terminal {@link #WORKSPACE_FINISH} /
 * {@link #BUILD_ERROR}. This hand-rolled codec (mirroring {@code Ndjson}, which only reads flat
 * scalar fields and string arrays — no nested object arrays) deliberately avoids ever encoding an
 * array of objects: a variable-length collection (the module plan, a goal's accumulated diagnostics)
 * is sent as a burst of individually-typed repeated messages plus a terminal marker, not one message
 * with a nested JSON array. This is why {@link #PLAN_MODULE}/{@link #PLAN_DONE} and {@link
 * #GOAL_DIAGNOSTIC} exist as separate repeatable message types instead of array-valued fields on
 * {@link #PLAN_DONE}/{@link #GOAL_FINISH}.
 */
public final class EngineProtocol {

    private EngineProtocol() {}

    public static final String TYPE_FIELD = "t";

    /**
     * Client → server, once per connection, and only on the loopback-TCP transport (see {@code
     * EngineTransport}): the shared secret from {@code paths.token()}, required as the very first
     * line before anything else is processed. Never sent/expected on the Unix-domain-socket
     * transport, where the socket file's own filesystem permissions already gate access.
     */
    public static final String AUTH = "auth";

    /** Client → server, once per connection: announces the client's jk version. */
    public static final String HELLO = "hello";

    /** Server → client: the engine's own jk version, pid, and start time. */
    public static final String HELLO_ACK = "hello-ack";

    /** Client → server: liveness probe. */
    public static final String PING = "ping";

    /** Server → client: liveness reply. */
    public static final String PONG = "pong";

    /** Client → server: request a status snapshot. */
    public static final String STATUS = "status";

    /** Server → client: the status snapshot. */
    public static final String STATUS_ACK = "status-ack";

    /** Client → server: ask the engine to shut down gracefully. */
    public static final String SHUTDOWN = "shutdown";

    /** Server → client: acknowledges {@link #SHUTDOWN} just before closing the connection. */
    public static final String BYE = "bye";

    /** Client → server: start a workspace build (see {@link #buildRequest}). Owns the rest of the connection. */
    public static final String BUILD_REQUEST = "build-request";

    /** Client → server, on the same connection as an in-flight {@link #BUILD_REQUEST}: best-effort cancel. */
    public static final String BUILD_CANCEL = "build-cancel";

    /** Server → client, repeated once per module: {@code onPlan}'s per-module identity/sizing. */
    public static final String PLAN_MODULE = "plan-module";

    /** Server → client, repeated once per (module, phase): a plan-module's phase list entry. */
    public static final String PLAN_PHASE = "plan-phase";

    /** Server → client: the plan burst ({@link #PLAN_MODULE}/{@link #PLAN_PHASE}) is complete. */
    public static final String PLAN_DONE = "plan-done";

    /** Server → client: {@code onEtaEstimate}. */
    public static final String ETA = "eta";

    /** Server → client: a module's goal is about to run — {@code onModuleStart}. */
    public static final String MODULE_START = "module-start";

    /** Server → client: {@code GoalListener.goalStart}. */
    public static final String GOAL_START = "goal-start";

    /** Server → client: {@code GoalListener.phaseStart}. */
    public static final String PHASE_START = "phase-start";

    /** Server → client: {@code GoalListener.progress}. */
    public static final String PROGRESS = "progress";

    /** Server → client: {@code GoalListener.scopeUpdate}. */
    public static final String SCOPE_UPDATE = "scope-update";

    /** Server → client: {@code GoalListener.label}. */
    public static final String LABEL = "label";

    /** Server → client: {@code GoalListener.output}. */
    public static final String OUTPUT = "output";

    /** Server → client: {@code GoalListener.warn}. */
    public static final String WARN = "warn";

    /** Server → client: {@code GoalListener.error}. */
    public static final String ERROR = "error";

    /** Server → client, repeated, immediately before {@link #GOAL_FINISH}: one of its result's diagnostics. */
    public static final String GOAL_DIAGNOSTIC = "goal-diagnostic";

    /** Server → client: {@code GoalListener.phaseFinish}. */
    public static final String PHASE_FINISH = "phase-finish";

    /** Server → client: {@code GoalListener.goalFinish} (success flag only; see {@link #GOAL_DIAGNOSTIC}). */
    public static final String GOAL_FINISH = "goal-finish";

    /** Server → client: {@code onModuleFinish}. */
    public static final String MODULE_FINISH = "module-finish";

    /** Server → client, terminal: {@code onWorkspaceFinish}. */
    public static final String WORKSPACE_FINISH = "workspace-finish";

    /** Server → client, terminal: the request failed before/outside normal build-failure reporting. */
    public static final String BUILD_ERROR = "build-error";

    /**
     * Client → server: run a single project's test goal (Phase 3 — {@code jk test}). A single
     * {@code Goal} run, not a workspace: reuses {@link #PLAN_PHASE}/{@link #PLAN_DONE} (with the
     * fixed sentinel {@link #SINGLE_GOAL_DIR} in place of a module {@code dir}) and every {@link
     * dev.jkbuild.run.GoalListener} event type {@link #BUILD_REQUEST} already defined — a test run
     * is exactly one goal, so it needs no module-plan burst or per-module tagging.
     */
    public static final String TEST_REQUEST = "test-request";

    /**
     * Client → server: run a single (non-workspace) project's build goal — the engine-hosted
     * counterpart of {@code BuildCommand.runForDir}. Same single-goal shape as {@link #TEST_REQUEST}
     * (one goal, {@link #SINGLE_GOAL_DIR}-tagged events), but {@code testOnly=false} server-side and
     * {@link #GOAL_FINISH} additionally carries the build outcome (see {@link
     * dev.jkbuild.runtime.BuildPipeline#BUILD_OUTCOME}) client-side rendering needs.
     */
    public static final String SINGLE_BUILD_REQUEST = "single-build-request";

    /** The {@code dir} tag {@link #TEST_REQUEST}/{@link #SINGLE_BUILD_REQUEST}'s single goal events carry. */
    public static final String SINGLE_GOAL_DIR = "";

    /**
     * Client → server: forecast a build ({@code jk explain}, {@code BuildService.explain}) — a
     * synchronous, non-listener-driven read: no progress events, just a burst of {@link
     * #EXPLAIN_MODULE}/{@link #EXPLAIN_PHASE}/{@link #EXPLAIN_EDGE}/{@link #EXPLAIN_ERROR} messages
     * followed by a terminal {@link #EXPLAIN_DONE}. Doesn't fork any worker JVM, so unlike {@link
     * #BUILD_REQUEST}/{@link #TEST_REQUEST}/{@link #SINGLE_BUILD_REQUEST} it's handled inline on the
     * connection thread — no cancel/EOF-watching fork needed.
     */
    public static final String EXPLAIN_REQUEST = "explain-request";

    /** Server → client, repeated once per module: {@code BuildPlanForecast.Module}'s identity/sizing. */
    public static final String EXPLAIN_MODULE = "explain-module";

    /** Server → client, repeated once per (module, phase): a {@code BuildPlanForecast.Module}'s phase list entry. */
    public static final String EXPLAIN_PHASE = "explain-phase";

    /** Server → client, repeated once per dependency edge. */
    public static final String EXPLAIN_EDGE = "explain-edge";

    /** Server → client, repeated once per graph-resolution error (mirrors {@code ExplainPlan.errors()}). */
    public static final String EXPLAIN_ERROR = "explain-error";

    /** Server → client, terminal: the explain burst is complete. */
    public static final String EXPLAIN_DONE = "explain-done";

    /**
     * Client → server: resolve declared dependencies and write {@code jk.lock} ({@code jk lock},
     * Wave 1 of the slim-client migration). A workspace root cascades: the root plus each declared
     * module is locked in declaration order, each as its own single goal. Wire shape per module:
     * one {@link #LOCK_MODULE}, a {@link #PLAN_PHASE} burst + {@link #PLAN_DONE}, then the standard
     * {@code dir}-tagged {@link dev.jkbuild.run.GoalListener} event vocabulary ending in a {@link
     * #GOAL_FINISH} that additionally carries the module's lockfile counts. {@link #LOCK_PACKAGE}
     * events stream per resolved package so the client can render its per-package completion tail
     * (and colorize coordinates client-side — the engine never themes text). Terminal: {@link
     * #LOCK_FINISH}.
     */
    public static final String LOCK_REQUEST = "lock-request";

    /**
     * Client → server: re-resolve fresh and overwrite {@code jk.lock} ({@code jk update}). Rides
     * {@link #LOCK_REQUEST}'s exact event vocabulary (same cascade, same terminal); {@code gitOnly}
     * selects the {@code --git} splice mode, which streams no goal events at all — just the {@link
     * #LOCK_FINISH} terminal carrying {@code refreshed}.
     */
    public static final String UPDATE_REQUEST = "update-request";

    /**
     * Client → server: bring the CAS + toolchain in line with {@code jk.lock} ({@code jk sync}).
     * A single goal, so it reuses {@link #TEST_REQUEST}'s wire shape verbatim: {@link #PLAN_PHASE}
     * burst ({@link #SINGLE_GOAL_DIR}-tagged) + {@link #PLAN_DONE}, goal events, then a {@link
     * #GOAL_FINISH} additionally carrying the fetched/up-to-date counts the summary line needs.
     * The JDK <em>install</em> half of sync's ensure-jdk phase stays client-side (pre-flight): the
     * engine only ever resolves an already-installed JDK and reports a structured error when none
     * is — interactive/consent concerns (and {@code jk jdk install}) live in the client per {@code
     * docs/engine.md}.
     */
    public static final String SYNC_REQUEST = "sync-request";

    /** Server → client, repeated: opens one module's event scope in a lock/update cascade. */
    public static final String LOCK_MODULE = "lock-module";

    /** Server → client, repeated: one package was resolved and recorded ({@code ResolveObserver.onPackage}). */
    public static final String LOCK_PACKAGE = "lock-package";

    /** Server → client, terminal for {@link #LOCK_REQUEST}/{@link #UPDATE_REQUEST}: cascade outcome. */
    public static final String LOCK_FINISH = "lock-finish";

    /** The {@code "t"} discriminator of a decoded message, or {@code null} if absent/malformed. */
    public static String typeOf(String json) {
        return Ndjson.str(json, TYPE_FIELD);
    }

    public static String auth(String token) {
        return "{\"t\":\"" + AUTH + "\",\"token\":" + Ndjson.quote(token) + "}";
    }

    public static String hello(String version) {
        return "{\"t\":\"" + HELLO + "\",\"version\":" + Ndjson.quote(version) + "}";
    }

    public static String helloAck(String version, long pid, long startedAtMillis) {
        return "{\"t\":\""
                + HELLO_ACK
                + "\",\"version\":"
                + Ndjson.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + "}";
    }

    public static String ping() {
        return "{\"t\":\"" + PING + "\"}";
    }

    public static String pong() {
        return "{\"t\":\"" + PONG + "\"}";
    }

    public static String statusRequest() {
        return "{\"t\":\"" + STATUS + "\"}";
    }

    /**
     * The status snapshot. Memory fields are best-effort observations of the engine process itself:
     * heap from the runtime, {@code rssBytes} from the OS ({@code -1} where it exposes none).
     */
    public static String statusAck(
            String version,
            long pid,
            long startedAtMillis,
            int idleMinutes,
            int activeRequests,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes) {
        return "{\"t\":\""
                + STATUS_ACK
                + "\",\"version\":"
                + Ndjson.quote(version)
                + ",\"pid\":"
                + pid
                + ",\"startedAt\":"
                + startedAtMillis
                + ",\"idleMinutes\":"
                + idleMinutes
                + ",\"activeRequests\":"
                + activeRequests
                + ",\"heapUsedBytes\":"
                + heapUsedBytes
                + ",\"heapCommittedBytes\":"
                + heapCommittedBytes
                + ",\"heapMaxBytes\":"
                + heapMaxBytes
                + ",\"rssBytes\":"
                + rssBytes
                + "}";
    }

    public static String shutdown() {
        return "{\"t\":\"" + SHUTDOWN + "\"}";
    }

    public static String bye() {
        return "{\"t\":\"" + BYE + "\"}";
    }

    // ---- build-request (client → server) -------------------------------------------------------

    /**
     * Start a workspace build. {@code jdksDir}/{@code profile} may be {@code null}. Deliberately
     * omits {@code dirtyHint} — the engine always forecasts dirty modules itself
     * (see {@code docs/engine.md}); the CLI's own "fully cached, nothing to do" pre-check stays
     * client-side and cheap regardless.
     *
     * <p>{@code rerun} carries the session's rerun setting distinctly from {@code force}: rerun
     * alone bypasses the action cache / freshness stamps (a genuine recompile+repackage) while
     * still serving locked dependencies from the local CAS, whereas {@code force} additionally
     * implies {@code refresh} (re-download every locked artifact). {@code jk verify}'s scratch
     * rebuild needs exactly the former.
     *
     * <p>{@code freshenLock} asks the engine to auto-freshen a stale merged workspace lock before
     * building (the pre-build guard {@code jk build} used to run client-side); {@code jk verify}'s
     * scratch rebuild sends {@code false} — it must build against the pinned lock verbatim.
     */
    public static String buildRequest(
            String entryDir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency,
            boolean parallelTests,
            boolean offline,
            boolean force,
            boolean rerun,
            boolean freshenLock) {
        return "{\"t\":\""
                + BUILD_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Ndjson.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"maxModuleConcurrency\":"
                + maxModuleConcurrency
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"rerun\":"
                + rerun
                + ",\"freshenLock\":"
                + freshenLock
                + "}";
    }

    public static String buildCancel() {
        return "{\"t\":\"" + BUILD_CANCEL + "\"}";
    }

    /** Start a single-project test run (see {@link #TEST_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String testRequest(
            String entryDir, String cache, String jdksDir, int workers, String profile, boolean verbose) {
        return "{\"t\":\""
                + TEST_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Ndjson.quote(profile)
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /** Start a single-project build (see {@link #SINGLE_BUILD_REQUEST}). {@code jdksDir}/{@code profile} may be {@code null}. */
    public static String singleBuildRequest(
            String entryDir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force) {
        return "{\"t\":\""
                + SINGLE_BUILD_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"workers\":"
                + workers
                + ",\"profile\":"
                + Ndjson.quote(profile)
                + ",\"skipTests\":"
                + skipTests
                + ",\"verbose\":"
                + verbose
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + "}";
    }

    /**
     * Resolve + write {@code jk.lock} (see {@link #LOCK_REQUEST}). {@code repoUrl} may be {@code
     * null}. {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side
     * (the same fields {@link #buildRequest} carries).
     */
    public static String lockRequest(
            String entryDir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            String repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"t\":\""
                + LOCK_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"features\":"
                + quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"sources\":"
                + sources
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Re-resolve fresh and overwrite {@code jk.lock} (see {@link #UPDATE_REQUEST}). {@code gitTarget}
     * is the {@code --git <name>} argument ({@code null} = every git dep) and is only read when
     * {@code gitOnly} is set.
     */
    public static String updateRequest(
            String entryDir,
            String cache,
            List<String> features,
            boolean noDefaultFeatures,
            String repoUrl,
            boolean gitOnly,
            String gitTarget,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"t\":\""
                + UPDATE_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"features\":"
                + quoteArray(features)
                + ",\"noDefaultFeatures\":"
                + noDefaultFeatures
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"gitOnly\":"
                + gitOnly
                + ",\"gitTarget\":"
                + Ndjson.quote(gitTarget)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Sync the CAS + toolchain with {@code jk.lock} (see {@link #SYNC_REQUEST}). {@code jdksDir}/
     * {@code repoUrl} may be {@code null}. {@code refresh} rides separately from {@code force} for
     * the same reason {@code rerun} does on {@link #buildRequest}: it re-downloads locked artifacts
     * without implying the rest of {@code force}'s cache bypasses.
     */
    public static String syncRequest(
            String entryDir,
            String cache,
            String jdksDir,
            String repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {
        return "{\"t\":\""
                + SYNC_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"sources\":"
                + sources
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"refresh\":"
                + refresh
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /** Forecast a build (see {@link #EXPLAIN_REQUEST}). */
    public static String explainRequest(String entryDir, String cache) {
        return "{\"t\":\""
                + EXPLAIN_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + "}";
    }

    // ---- explain events (server → client) --------------------------------------------------------

    public static String explainModule(
            String dir, String coord, int sourceCount, int testCount, boolean producesJar, boolean producesImage) {
        return "{\"t\":\""
                + EXPLAIN_MODULE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"coord\":"
                + Ndjson.quote(coord)
                + ",\"sourceCount\":"
                + sourceCount
                + ",\"testCount\":"
                + testCount
                + ",\"producesJar\":"
                + producesJar
                + ",\"producesImage\":"
                + producesImage
                + "}";
    }

    public static String explainPhase(String dir, String name, String status, String text, String key) {
        return "{\"t\":\""
                + EXPLAIN_PHASE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"name\":"
                + Ndjson.quote(name)
                + ",\"status\":"
                + Ndjson.quote(status)
                + ",\"text\":"
                + Ndjson.quote(text)
                + ",\"key\":"
                + Ndjson.quote(key)
                + "}";
    }

    public static String explainEdge(String dir, String dependsOnDir) {
        return "{\"t\":\""
                + EXPLAIN_EDGE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"dependsOnDir\":"
                + Ndjson.quote(dependsOnDir)
                + "}";
    }

    public static String explainError(String message) {
        return "{\"t\":\"" + EXPLAIN_ERROR + "\",\"message\":" + Ndjson.quote(message) + "}";
    }

    public static String explainDone(int maxReadyWidth, int moduleCount) {
        return "{\"t\":\""
                + EXPLAIN_DONE
                + "\",\"maxReadyWidth\":"
                + maxReadyWidth
                + ",\"moduleCount\":"
                + moduleCount
                + "}";
    }

    // ---- build events (server → client) ----------------------------------------------------------

    public static String planModule(String dir, String coord, String goalName, int weight, boolean fullyCached) {
        return "{\"t\":\""
                + PLAN_MODULE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"coord\":"
                + Ndjson.quote(coord)
                + ",\"goalName\":"
                + Ndjson.quote(goalName)
                + ",\"weight\":"
                + weight
                + ",\"fullyCached\":"
                + fullyCached
                + "}";
    }

    public static String planPhase(String dir, String name, String label) {
        return "{\"t\":\""
                + PLAN_PHASE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"name\":"
                + Ndjson.quote(name)
                + ",\"label\":"
                + Ndjson.quote(label)
                + "}";
    }

    public static String planDone(int count) {
        return "{\"t\":\"" + PLAN_DONE + "\",\"count\":" + count + "}";
    }

    public static String eta(long millis) {
        return "{\"t\":\"" + ETA + "\",\"millis\":" + millis + "}";
    }

    public static String moduleStart(String dir) {
        return "{\"t\":\"" + MODULE_START + "\",\"dir\":" + Ndjson.quote(dir) + "}";
    }

    public static String goalStart(
            String dir,
            String goalName,
            long numerator,
            long denominator,
            int phasesTotal,
            int phasesComplete,
            boolean cancelled) {
        return "{\"t\":\""
                + GOAL_START
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"goalName\":"
                + Ndjson.quote(goalName)
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"phasesTotal\":"
                + phasesTotal
                + ",\"phasesComplete\":"
                + phasesComplete
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    public static String phaseStart(String dir, String phase, int scope) {
        return "{\"t\":\""
                + PHASE_START
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"scope\":"
                + scope
                + "}";
    }

    private static String progressLike(
            String type,
            String dir,
            String phase,
            int delta,
            long numerator,
            long denominator,
            int phasesTotal,
            int phasesComplete,
            boolean cancelled) {
        return "{\"t\":\""
                + type
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"delta\":"
                + delta
                + ",\"numerator\":"
                + numerator
                + ",\"denominator\":"
                + denominator
                + ",\"phasesTotal\":"
                + phasesTotal
                + ",\"phasesComplete\":"
                + phasesComplete
                + ",\"cancelled\":"
                + cancelled
                + "}";
    }

    public static String progress(
            String dir,
            String phase,
            int delta,
            long numerator,
            long denominator,
            int phasesTotal,
            int phasesComplete,
            boolean cancelled) {
        return progressLike(PROGRESS, dir, phase, delta, numerator, denominator, phasesTotal, phasesComplete, cancelled);
    }

    public static String scopeUpdate(
            String dir,
            String phase,
            int delta,
            long numerator,
            long denominator,
            int phasesTotal,
            int phasesComplete,
            boolean cancelled) {
        return progressLike(
                SCOPE_UPDATE, dir, phase, delta, numerator, denominator, phasesTotal, phasesComplete, cancelled);
    }

    public static String label(String dir, String phase, String label) {
        return "{\"t\":\""
                + LABEL
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"label\":"
                + Ndjson.quote(label)
                + "}";
    }

    public static String output(String dir, String phase, String line) {
        return "{\"t\":\""
                + OUTPUT
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"line\":"
                + Ndjson.quote(line)
                + "}";
    }

    private static String diagnosticLike(
            String type, String dir, String phase, String code, String message, String test, String exceptionClass) {
        return "{\"t\":\""
                + type
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"code\":"
                + Ndjson.quote(code)
                + ",\"message\":"
                + Ndjson.quote(message)
                + ",\"test\":"
                + Ndjson.quote(test)
                + ",\"exceptionClass\":"
                + Ndjson.quote(exceptionClass)
                + "}";
    }

    public static String warn(String dir, String phase, String code, String message) {
        return diagnosticLike(WARN, dir, phase, code, message, "", "");
    }

    public static String error(String dir, String phase, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(ERROR, dir, phase, code, message, test, exceptionClass);
    }

    public static String goalDiagnostic(
            String dir, String phase, String code, String message, String test, String exceptionClass) {
        return diagnosticLike(GOAL_DIAGNOSTIC, dir, phase, code, message, test, exceptionClass);
    }

    public static String phaseFinish(String dir, String phase, String status) {
        return "{\"t\":\""
                + PHASE_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"phase\":"
                + Ndjson.quote(phase)
                + ",\"status\":"
                + Ndjson.quote(status)
                + "}";
    }

    public static String goalFinish(String dir, boolean success) {
        return "{\"t\":\"" + GOAL_FINISH + "\",\"dir\":" + Ndjson.quote(dir) + ",\"success\":" + success + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk test} run's counts
     * (total/succeeded/failed/skipped) — absent (-1) for a plain {@code buildWorkspace} goal-finish.
     * Bundled into the same message rather than a separate terminal one because the client must know
     * these counts <em>before</em> dispatching this event to its console listener: the listener's own
     * {@code goalFinish} handler is what renders the "Passed N tests" summary line.
     */
    public static String goalFinish(String dir, boolean success, long total, long succeeded, long failed, long skipped) {
        return goalFinish(dir, success, null, total, succeeded, failed, skipped);
    }

    /**
     * As {@link #goalFinish(String, boolean, long, long, long, long)}, additionally carrying a {@code
     * jk build} run's outcome (see {@code BuildPipeline.BUILD_OUTCOME}, e.g. {@code "up-to-date"}/
     * {@code "no-sources"}) — {@code null} when not applicable (a workspace per-module goal, or a
     * test-only run). Like the test counts, this rides along on {@code goal-finish} because the
     * client's console listener renders its summary line from within its own {@code goalFinish}
     * handler, before any later message could arrive.
     */
    public static String goalFinish(
            String dir, boolean success, String buildOutcome, long total, long succeeded, long failed, long skipped) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"buildOutcome\":"
                + Ndjson.quote(buildOutcome)
                + ",\"testTotal\":"
                + total
                + ",\"testSucceeded\":"
                + succeeded
                + ",\"testFailed\":"
                + failed
                + ",\"testSkipped\":"
                + skipped
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk lock}/{@code jk
     * update} module's written-lockfile counts (packages / packages-with-sources / plugins) — the
     * structured ingredients of the client's summary lines, which its console listener renders from
     * within its own {@code goalFinish} handler.
     */
    public static String goalFinishLock(String dir, boolean success, long packages, long sources, long plugins) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"lockPackages\":"
                + packages
                + ",\"lockSources\":"
                + sources
                + ",\"lockPlugins\":"
                + plugins
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk sync} run's
     * fetched/up-to-date counts for the client's summary line ({@code "N fetched, M up-to-date"}).
     */
    public static String goalFinishSync(String dir, boolean success, long fetched, long upToDate) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"syncFetched\":"
                + fetched
                + ",\"syncUpToDate\":"
                + upToDate
                + "}";
    }

    /** Opens one module's event scope in a {@code jk lock}/{@code jk update} cascade (see {@link #LOCK_MODULE}). */
    public static String lockModule(String dir, String coord) {
        return "{\"t\":\"" + LOCK_MODULE + "\",\"dir\":" + Ndjson.quote(dir) + ",\"coord\":" + Ndjson.quote(coord) + "}";
    }

    /** One resolved package, streamed as it is recorded (see {@link #LOCK_PACKAGE}). */
    public static String lockPackage(String dir, String name, String version) {
        return "{\"t\":\""
                + LOCK_PACKAGE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"name\":"
                + Ndjson.quote(name)
                + ",\"version\":"
                + Ndjson.quote(version)
                + "}";
    }

    /**
     * Terminal for a lock/update request. {@code exitCode} is computed engine-side (the engine saw
     * the phase statuses: a failed {@code resolve} phase exits 6, other failures exit 2/CONFIG);
     * {@code errors} carries pre-goal failures (manifest parse, workspace module load) as plain
     * uncolored text for the client to render. {@code refreshed} is {@code jk update --git}'s
     * refreshed-dependency count, {@code -1} for every other request.
     */
    public static String lockFinish(boolean success, int exitCode, List<String> errors, int refreshed) {
        return "{\"t\":\""
                + LOCK_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + quoteArray(errors)
                + ",\"refreshed\":"
                + refreshed
                + "}";
    }

    public static String moduleFinish(String dir, String coord, boolean success, int exitCode, long millis) {
        return "{\"t\":\""
                + MODULE_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"coord\":"
                + Ndjson.quote(coord)
                + ",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"millis\":"
                + millis
                + "}";
    }

    public static String workspaceFinish(boolean success, int exitCode, List<String> errors) {
        return "{\"t\":\""
                + WORKSPACE_FINISH
                + "\",\"success\":"
                + success
                + ",\"exitCode\":"
                + exitCode
                + ",\"errors\":"
                + quoteArray(errors)
                + "}";
    }

    public static String buildError(String message) {
        return "{\"t\":\"" + BUILD_ERROR + "\",\"message\":" + Ndjson.quote(message) + "}";
    }

    /** {@code Ndjson} only reads string arrays; it has no writer half, so this is the encode side. */
    private static String quoteArray(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(Ndjson.quote(values.get(i)));
        }
        return b.append(']').toString();
    }
}
