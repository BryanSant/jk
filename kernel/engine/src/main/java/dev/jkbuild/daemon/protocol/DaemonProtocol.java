// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon.protocol;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;

/**
 * The daemon wire protocol's message vocabulary: one newline-delimited JSON object per message, each
 * carrying a {@code "t"} type discriminator — the same canonical envelope shape {@link
 * dev.jkbuild.worker.WorkerClient} already uses for jk's worker-process protocol (see {@code
 * docs/daemon.md}). Deliberately internal and unversioned: the client can only ever be a same-version
 * {@code jk} binary (enforced by the daemon spawn path), so this vocabulary is free to change between
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
public final class DaemonProtocol {

    private DaemonProtocol() {}

    public static final String TYPE_FIELD = "t";

    /** Client → server, once per connection: announces the client's jk version. */
    public static final String HELLO = "hello";

    /** Server → client: the daemon's own jk version, pid, and start time. */
    public static final String HELLO_ACK = "hello-ack";

    /** Client → server: liveness probe. */
    public static final String PING = "ping";

    /** Server → client: liveness reply. */
    public static final String PONG = "pong";

    /** Client → server: request a status snapshot. */
    public static final String STATUS = "status";

    /** Server → client: the status snapshot. */
    public static final String STATUS_ACK = "status-ack";

    /** Client → server: ask the daemon to shut down gracefully. */
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

    /** The {@code dir} tag {@link #TEST_REQUEST}'s single goal events carry (there is no module dir). */
    public static final String SINGLE_GOAL_DIR = "";

    /** The {@code "t"} discriminator of a decoded message, or {@code null} if absent/malformed. */
    public static String typeOf(String json) {
        return Ndjson.str(json, TYPE_FIELD);
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

    public static String statusAck(
            String version, long pid, long startedAtMillis, int idleMinutes, int activeRequests) {
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
     * omits {@code dirtyHint} — the daemon always lets the engine forecast dirty modules itself
     * (see {@code docs/daemon.md}); the CLI's own "fully cached, nothing to do" pre-check stays
     * client-side and cheap regardless.
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
            boolean force) {
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
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
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
