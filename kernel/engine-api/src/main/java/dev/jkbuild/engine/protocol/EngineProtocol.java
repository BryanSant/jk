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

    /** Server → client, repeated once per module: {@code BuildPlan.Module}'s identity/sizing. */
    public static final String EXPLAIN_MODULE = "explain-module";

    /** Server → client, repeated once per (module, phase): a {@code BuildPlan.Module}'s phase list entry. */
    public static final String EXPLAIN_PHASE = "explain-phase";

    /** Server → client, repeated once per dependency edge. */
    public static final String EXPLAIN_EDGE = "explain-edge";

    /** Server → client, repeated once per graph-resolution error (mirrors {@code ExplainPlan.errors()}). */
    public static final String EXPLAIN_ERROR = "explain-error";

    /**
     * Client → server: forecast a build's dirty set without running it — the pre-flight behind
     * {@code jk build}'s fully-cached shortcut / dirty hint (slim-client Stage 5: the client no
     * longer links the forecaster). Carries {@code entryDir}/{@code cache}/{@code skipTests} plus
     * the cache-bypass flags ({@code force}/{@code rerun}) the forecast must honor. Synchronous:
     * answered inline with one {@link #FORECAST_ACK}.
     */
    public static final String FORECAST_REQUEST = "forecast-request";

    /**
     * Server → client: the forecast — {@code dirtyDirs} (module dirs predicted to do real work),
     * {@code lockStale} (the merged workspace lock no longer reflects its manifests), {@code empty}
     * (the workspace declares no modules), and {@code errors} (graph resolution; non-empty ⇒ no
     * forecast).
     */
    public static final String FORECAST_ACK = "forecast-ack";

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

    // ---- hosted worker verbs (Wave 2 of the slim-client migration) ------------------------------
    //
    // The six verbs that used to fork plugin workers directly from the CLI (audit, format, publish,
    // image, import, and mvn/gradle provisioning) now run engine-side: the engine assembles the same
    // goal the in-process test path uses (dev.jkbuild.runtime.*Goals), forks the worker JVM itself
    // (sized by the engine's shared HeapPlan), and streams the single-goal wire shape TEST_REQUEST
    // already defined — a SINGLE_GOAL_DIR-tagged PLAN_PHASE burst + PLAN_DONE, the standard
    // GoalListener events, then a terminal GOAL_FINISH variant carrying the verb's structured
    // summary fields. Variable-length structured results (audit findings, per-file format results,
    // import notes) stream as their own repeated message types, mirroring LOCK_PACKAGE — never as
    // nested JSON arrays (see the class javadoc).

    /**
     * Client → server: scan {@code jk.lock} against OSV ({@code jk audit}). Single goal; findings
     * stream as repeated {@link #AUDIT_FINDING} events (plain structured fields — the client builds
     * and renders the report, and applies the severity threshold to compute its own exit code).
     */
    public static final String AUDIT_REQUEST = "audit-request";

    /** Server → client, repeated: one OSV finding, streamed as the audit worker reports it. */
    public static final String AUDIT_FINDING = "audit-finding";

    /**
     * Client → server: format Java/Kotlin sources ({@code jk format}). The engine collects the
     * source list, resolves the formatter implementation jars through jk's own resolver, and forks
     * the formatter worker; per-file results stream as repeated {@link #FORMAT_FILE} events and the
     * terminal {@code goal-finish} carries the counts ({@code formatTotal} of 0 = no sources found).
     */
    public static final String FORMAT_REQUEST = "format-request";

    /** Server → client, repeated: one file's format result ({@code changed}/{@code clean}/{@code error}). */
    public static final String FORMAT_FILE = "format-file";

    /**
     * Client → server: assemble/sign/upload publish artifacts ({@code jk publish}). Interactivity
     * audit: everything env- or keychain-shaped is resolved <em>client-side</em> and rides the
     * request — the repository credential ({@code PUBLISH_USER}/{@code PUBLISH_PASSWORD}, keychain,
     * inline repo credential) and the GPG key passphrase ({@code --key-passphrase} /
     * {@code JK_GPG_PASSPHRASE}) — because the engine's environment is whatever its first spawner
     * had, not this invocation's. The socket is user-owned/permission-gated (see {@code
     * docs/engine.md}); secrets are never logged and reach the worker via the same 0600 spec file
     * as before.
     */
    public static final String PUBLISH_REQUEST = "publish-request";

    /**
     * Client → server: build an OCI image ({@code jk image}) — the full build pipeline plus the
     * image tail (Jib worker, or a {@code docker build} child process in Dockerfile mode), all
     * engine-side. The terminal {@code goal-finish} carries the structured success-tail fields
     * (ref / tarball path / daemon executable) plus the test counts the exit code needs.
     */
    public static final String IMAGE_REQUEST = "image-request";

    /**
     * Client → server: convert a Maven/Gradle build to {@code jk.toml} ({@code jk import}) via the
     * compat-bridge worker. Progress notes stream as repeated {@link #IMPORT_NOTE} events; the
     * terminal {@code goal-finish} carries the worker's exit code, warning count, and error text.
     */
    public static final String IMPORT_REQUEST = "import-request";

    /** Server → client, repeated: one import progress note ({@code kind} = {@code wrote}/{@code note}). */
    public static final String IMPORT_NOTE = "import-note";

    /**
     * Client → server: provision a Maven/Gradle distribution via the compat-bridge worker ({@code
     * jk mvn} / {@code jk gradle}). One-shot: no goal events, just the terminal {@link
     * #PROVISION_RESULT} — the passthrough <em>exec</em> of the provisioned tool stays client-side
     * (it inherits the client's terminal/stdio, which the engine deliberately never touches).
     */
    public static final String PROVISION_REQUEST = "provision-request";

    /** Server → client, terminal for {@link #PROVISION_REQUEST}: the provisioned tool's bin path. */
    public static final String PROVISION_RESULT = "provision-result";

    // ---- hosted pipeline verbs (Wave 3 of the slim-client migration) -----------------------------
    //
    // The in-process BuildPipeline stragglers move engine-side: jk compile, jk native, and jk
    // install's build/clone halves (jk run's build half rides SINGLE_BUILD_REQUEST as-is — only the
    // exec of the user's program stays client-side, same reasoning as jk mvn/gradle's exec).
    // Terminal-owning pre-flights stay in the client: jk native / jk install resolve (and, with
    // consent, install) the GraalVM in the client and send its home on the request; the launcher
    // write into ~/.jk/bin (jk install's "make install") happens client-side after the hosted goal.

    /**
     * Client → server: type-check the project ({@code jk compile}) — the shared pipeline in
     * compile-only mode. Single goal; {@link #TEST_REQUEST}'s exact wire shape with a plain
     * {@code goal-finish} terminal.
     */
    public static final String COMPILE_REQUEST = "compile-request";

    /**
     * Client → server: build native artifacts ({@code jk native}) — the full pipeline plus the
     * native-image tail for every native-eligible module, the {@code native-image} child process
     * forked engine-side like a worker. Speaks {@link #BUILD_REQUEST}'s workspace event vocabulary
     * verbatim (a single project is a cascade of one): a {@link #PLAN_MODULE}/{@link #PLAN_PHASE}
     * burst with per-module goal weights, then each module serially — {@link #MODULE_START}, goal
     * events, a test-count-carrying {@link #GOAL_FINISH}, {@link #MODULE_FINISH} with an
     * engine-computed exit code ({@code jk native}'s 64/4/1 mapping) — ending in {@link
     * #WORKSPACE_FINISH}. The GraalVM was resolved client-side (a prompt/install owns the terminal)
     * and rides the request as parallel {@code graalDirs}/{@code graalHomes} arrays.
     */
    public static final String NATIVE_REQUEST = "native-request";

    /**
     * Client → server: build the current project and install it into the caches ({@code jk
     * install}'s build + cache-install halves — jar/pom into {@code ~/.m2} and the local repo
     * index). Single goal ({@link #TEST_REQUEST}'s wire shape); the terminal {@code goal-finish}
     * carries the test counts the exit-code logic needs. The "make install" half (launcher/binary
     * into {@code ~/.jk/bin} + {@code ~/.jk/lib}) stays client-side, after this goal succeeds.
     */
    public static final String INSTALL_REQUEST = "install-request";

    /**
     * Client → server: materialize a git checkout ({@code jk install <git-url>}'s fetch half) via
     * the engine-forked git-client worker. Single goal; the terminal {@code goal-finish} carries
     * the checkout path + resolved sha (see {@link #goalFinishGitFetch}), which the client then
     * feeds into a follow-up {@link #INSTALL_REQUEST}.
     */
    public static final String GIT_FETCH_REQUEST = "git-fetch-request";

    // ---- hosted long-tail verbs (Wave 4 of the slim-client migration) ----------------------------
    //
    // The last in-process resolution path leaves the client: jk tool install / jk tool run (and jk
    // install's Maven-coord mode) host their ToolResolver run — transitive POM walk + jar fetches —
    // as a single goal, returning the resolved main class + classpath on the terminal goal-finish;
    // the launcher write / inheritIO exec stays client-side (Wave 2/3 exec reasoning). jk ide rides
    // Wave 1's SYNC_REQUEST verbatim (no new vocabulary; file generation stays client-side). jk
    // cache prune/purge (and jk clean --cache's GC) become engine-hosted idle-boundary jobs: the
    // engine runs the mutation only when no pipeline is in flight (and blocks new pipelines while it
    // runs), emitting PRUNE_WAIT first when the client would otherwise stare at silence. jk export
    // gradle/maven turned out to be pure local transforms and stay client-only (reclassified).

    /**
     * Client → server: resolve a Maven-published CLI tool ({@code jk tool install} / {@code jk tool
     * run} / {@code jk install <g:a:v>}). Single goal ({@link #TEST_REQUEST}'s wire shape); the
     * terminal {@code goal-finish} carries the resolved main class + classpath (see {@link
     * #goalFinishTool}), which the client feeds into its launcher write or inheritIO exec — both of
     * which deliberately stay client-side.
     */
    public static final String TOOL_RESOLVE_REQUEST = "tool-resolve-request";

    /**
     * Client → server: run a cache maintenance operation ({@code op} = {@code prune} / {@code purge}
     * / {@code gc}) as an idle-boundary job: the engine waits until no pipeline is in flight (new
     * pipelines queue behind it), takes the cross-process {@code .prune.lock}, and only then mutates
     * the caches — the Wave-3 correctness finding's fix. Single goal ({@link #TEST_REQUEST}'s wire
     * shape) with a {@link #goalFinishCache} terminal; while the engine is waiting it emits {@link
     * #PRUNE_WAIT} (before the plan burst) so the client can explain the pause.
     */
    public static final String CACHE_PRUNE_REQUEST = "cache-prune-request";

    /**
     * Client → server: prepare a loose script/jar for execution ({@code jk tool run <file>}) —
     * parse its JBang-style header, resolve declared deps, compile it (or provision {@code
     * kotlinc} / read a jar's manifest + embedded POMs) engine-side. {@code mode} is {@code java} /
     * {@code kt} / {@code kts} / {@code jar}. Single goal ({@link #TEST_REQUEST}'s wire shape) with
     * a {@link #goalFinishScript} terminal carrying the exec ingredients; the exec itself stays
     * client-side (it owns the terminal).
     */
    public static final String SCRIPT_PREPARE_REQUEST = "script-prepare-request";

    /**
     * Server → client, before the plan burst of a {@link #CACHE_PRUNE_REQUEST}: the operation is
     * queued behind in-flight work — {@code pipelines} in-engine pipelines ({@code 0} with {@code
     * external=true} means another process's prune holds {@code .prune.lock}).
     */
    public static final String PRUNE_WAIT = "prune-wait";

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
            String entryDir,
            String cache,
            String jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force) {
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
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
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

    /**
     * Scan the lockfile against OSV (see {@link #AUDIT_REQUEST}). {@code severity} is the client's
     * threshold, carried only for the evaluate phase's label (the client applies the threshold
     * itself); {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides and may be
     * {@code null}.
     */
    public static String auditRequest(
            String entryDir, String cache, String severity, String osvBatchUrl, String osvVulnsUrl) {
        return "{\"t\":\""
                + AUDIT_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"severity\":"
                + Ndjson.quote(severity)
                + ",\"osvBatchUrl\":"
                + Ndjson.quote(osvBatchUrl)
                + ",\"osvVulnsUrl\":"
                + Ndjson.quote(osvVulnsUrl)
                + "}";
    }

    /**
     * Format sources (see {@link #FORMAT_REQUEST}). Style names arrive already resolved (flags +
     * env + the {@code [format]} block are client-side concerns); {@code rewriteConfig} may be
     * {@code null}.
     */
    public static String formatRequest(
            String entryDir,
            String cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            String rewriteConfig,
            boolean offline,
            boolean verbose) {
        return "{\"t\":\""
                + FORMAT_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"check\":"
                + check
                + ",\"javaStyle\":"
                + Ndjson.quote(javaStyle)
                + ",\"kotlinStyle\":"
                + Ndjson.quote(kotlinStyle)
                + ",\"optimizeImports\":"
                + optimizeImports
                + ",\"rewriteConfig\":"
                + Ndjson.quote(rewriteConfig)
                + ",\"offline\":"
                + offline
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Publish artifacts (see {@link #PUBLISH_REQUEST}). The credential fields ({@code authType} =
     * {@code basic}/{@code bearer}/{@code anonymous} + {@code user}/{@code pass}/{@code token}) and
     * {@code gpgPassphrase} were resolved client-side; nullable string fields may be {@code null}.
     */
    public static String publishRequest(
            String entryDir,
            String cache,
            String repoUrl,
            String region,
            String endpoint,
            String jar,
            boolean allowSnapshot,
            boolean dryRun,
            String keyFile,
            String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            String authType,
            String user,
            String pass,
            String token,
            boolean verbose) {
        return "{\"t\":\""
                + PUBLISH_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"region\":"
                + Ndjson.quote(region)
                + ",\"endpoint\":"
                + Ndjson.quote(endpoint)
                + ",\"jar\":"
                + Ndjson.quote(jar)
                + ",\"allowSnapshot\":"
                + allowSnapshot
                + ",\"dryRun\":"
                + dryRun
                + ",\"keyFile\":"
                + Ndjson.quote(keyFile)
                + ",\"gpgPassphrase\":"
                + Ndjson.quote(gpgPassphrase)
                + ",\"sigstore\":"
                + sigstore
                + ",\"slsa\":"
                + slsa
                + ",\"sbom\":"
                + sbom
                + ",\"authType\":"
                + Ndjson.quote(authType)
                + ",\"user\":"
                + Ndjson.quote(user)
                + ",\"pass\":"
                + Ndjson.quote(pass)
                + ",\"token\":"
                + Ndjson.quote(token)
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build an OCI image (see {@link #IMAGE_REQUEST}). {@code tarball} is tri-state: {@code null}
     * (no tarball — daemon/push mode), {@code ""} (default layout path), or an explicit path — the
     * same tri-state {@code --tarball}'s optional value has. {@code offline}/{@code force}/{@code
     * rerun}/{@code verbose} reconstruct the session config engine-side, as on {@link
     * #buildRequest}.
     */
    public static String imageRequest(
            String entryDir,
            String cache,
            String jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarball,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean rerun,
            boolean verbose) {
        return "{\"t\":\""
                + IMAGE_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"mainClass\":"
                + Ndjson.quote(mainClass)
                + ",\"registry\":"
                + Ndjson.quote(registry)
                + ",\"tag\":"
                + Ndjson.quote(tag)
                + ",\"tarball\":"
                + Ndjson.quote(tarball)
                + ",\"dockerExecutable\":"
                + Ndjson.quote(dockerExecutable)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"rerun\":"
                + rerun
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Convert a foreign build to {@code jk.toml} (see {@link #IMPORT_REQUEST}). All paths are
     * absolute (the client pre-flighted detection/overwrite checks); {@code report} may be {@code
     * null}.
     */
    public static String importRequest(
            String source, String out, String baseDir, String tmpDir, boolean force, String report, String cache) {
        return "{\"t\":\""
                + IMPORT_REQUEST
                + "\",\"source\":"
                + Ndjson.quote(source)
                + ",\"out\":"
                + Ndjson.quote(out)
                + ",\"baseDir\":"
                + Ndjson.quote(baseDir)
                + ",\"tmpDir\":"
                + Ndjson.quote(tmpDir)
                + ",\"force\":"
                + force
                + ",\"report\":"
                + Ndjson.quote(report)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + "}";
    }

    /** Provision a Maven/Gradle distribution (see {@link #PROVISION_REQUEST}). */
    public static String provisionRequest(
            String cache, String projectDir, String toolsRoot, boolean noDiscover, boolean gradle) {
        return "{\"t\":\""
                + PROVISION_REQUEST
                + "\",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"projectDir\":"
                + Ndjson.quote(projectDir)
                + ",\"toolsRoot\":"
                + Ndjson.quote(toolsRoot)
                + ",\"noDiscover\":"
                + noDiscover
                + ",\"gradle\":"
                + gradle
                + "}";
    }

    /**
     * Type-check the project (see {@link #COMPILE_REQUEST}). {@code profile} may be {@code null};
     * {@code offline}/{@code force}/{@code verbose} reconstruct the session config engine-side.
     */
    public static String compileRequest(
            String entryDir, String cache, String profile, boolean offline, boolean force, boolean verbose) {
        return "{\"t\":\""
                + COMPILE_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"profile\":"
                + Ndjson.quote(profile)
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Build native artifacts (see {@link #NATIVE_REQUEST}). {@code mainClass} is the {@code --main}
     * override (may be {@code null} — the engine resolves {@code [native].main-class}/{@code
     * [image].main}/{@code [project].main} itself); {@code extraArgs} are forwarded to {@code
     * native-image}; {@code graalDirs}/{@code graalHomes} are parallel arrays mapping each
     * native-eligible module dir to the GraalVM home the client resolved for it (the codec reads no
     * nested objects, so a map travels as two aligned string arrays).
     */
    public static String nativeRequest(
            String entryDir,
            String cache,
            String jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            List<String> graalDirs,
            List<String> graalHomes) {
        return "{\"t\":\""
                + NATIVE_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"mainClass\":"
                + Ndjson.quote(mainClass)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + ",\"extraArgs\":"
                + quoteArray(extraArgs)
                + ",\"graalDirs\":"
                + quoteArray(graalDirs)
                + ",\"graalHomes\":"
                + quoteArray(graalHomes)
                + "}";
    }

    /**
     * Build + cache-install the project (see {@link #INSTALL_REQUEST}). {@code m2Dir} is the
     * resolved local Maven repo root ({@code ~/.m2} or {@code --m2-dir}); {@code graalHome} is
     * non-null only for a native application (resolved client-side).
     */
    public static String installRequest(
            String entryDir,
            String cache,
            String m2Dir,
            String graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {
        return "{\"t\":\""
                + INSTALL_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"m2Dir\":"
                + Ndjson.quote(m2Dir)
                + ",\"graalHome\":"
                + Ndjson.quote(graalHome)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"verbose\":"
                + verbose
                + "}";
    }

    /**
     * Materialize a git checkout (see {@link #GIT_FETCH_REQUEST}). {@code url} is the expanded
     * fetch URL, {@code canonicalUrl} its canonical identity, {@code ref} the tag-or-branch name;
     * {@code refresh} forces a re-fetch of an already-materialized ref.
     */
    public static String gitFetchRequest(String url, String canonicalUrl, String ref, String cache, boolean refresh) {
        return "{\"t\":\""
                + GIT_FETCH_REQUEST
                + "\",\"url\":"
                + Ndjson.quote(url)
                + ",\"canonicalUrl\":"
                + Ndjson.quote(canonicalUrl)
                + ",\"ref\":"
                + Ndjson.quote(ref)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"refresh\":"
                + refresh
                + "}";
    }

    /**
     * Forecast a build (see {@link #EXPLAIN_REQUEST}). Beyond the plan itself, the fields carry the
     * plan-affecting {@code jk build} options the engine-side ETA estimate needs ({@code jdksDir}/
     * {@code profile} may be {@code null}); the computed estimate rides back as an {@link #ETA}
     * event inside the explain burst.
     */
    public static String explainRequest(
            String entryDir,
            String cache,
            int workers,
            boolean skipTests,
            String profile,
            String jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose) {
        return "{\"t\":\""
                + EXPLAIN_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"workers\":"
                + workers
                + ",\"skipTests\":"
                + skipTests
                + ",\"profile\":"
                + Ndjson.quote(profile)
                + ",\"jdksDir\":"
                + Ndjson.quote(jdksDir)
                + ",\"serial\":"
                + serial
                + ",\"parallelTests\":"
                + parallelTests
                + ",\"verbose\":"
                + verbose
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

    public static String forecastRequest(
            String entryDir, String cache, boolean skipTests, boolean offline, boolean force, boolean rerun) {
        return "{\"t\":\""
                + FORECAST_REQUEST
                + "\",\"entryDir\":"
                + Ndjson.quote(entryDir)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"skipTests\":"
                + skipTests
                + ",\"offline\":"
                + offline
                + ",\"force\":"
                + force
                + ",\"rerun\":"
                + rerun
                + "}";
    }

    public static String forecastAck(
            java.util.List<String> dirtyDirs, boolean lockStale, boolean empty, java.util.List<String> errors) {
        return "{\"t\":\""
                + FORECAST_ACK
                + "\",\"dirtyDirs\":"
                + quoteArray(dirtyDirs)
                + ",\"lockStale\":"
                + lockStale
                + ",\"empty\":"
                + empty
                + ",\"errors\":"
                + quoteArray(errors)
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

    // ---- hosted worker-verb events (server → client) --------------------------------------------

    /** One OSV finding (see {@link #AUDIT_FINDING}) — plain structured fields, no theming. */
    public static String auditFinding(
            String dir, String module, String version, String vulnId, String severity, String summary) {
        return "{\"t\":\""
                + AUDIT_FINDING
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"module\":"
                + Ndjson.quote(module)
                + ",\"version\":"
                + Ndjson.quote(version)
                + ",\"vulnId\":"
                + Ndjson.quote(vulnId)
                + ",\"severity\":"
                + Ndjson.quote(severity)
                + ",\"summary\":"
                + Ndjson.quote(summary)
                + "}";
    }

    /**
     * One file's format result (see {@link #FORMAT_FILE}). {@code index}/{@code total} drive the
     * client's per-file progress bar ({@code total} is known engine-side before the worker forks).
     */
    public static String formatFile(String dir, String path, String status, String message, int index, int total) {
        return "{\"t\":\""
                + FORMAT_FILE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"path\":"
                + Ndjson.quote(path)
                + ",\"status\":"
                + Ndjson.quote(status)
                + ",\"message\":"
                + Ndjson.quote(message)
                + ",\"index\":"
                + index
                + ",\"total\":"
                + total
                + "}";
    }

    /** One import progress note (see {@link #IMPORT_NOTE}). */
    public static String importNote(String dir, String kind, String text) {
        return "{\"t\":\""
                + IMPORT_NOTE
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"kind\":"
                + Ndjson.quote(kind)
                + ",\"text\":"
                + Ndjson.quote(text)
                + "}";
    }

    /**
     * Terminal for {@link #PROVISION_REQUEST}. {@code bin} is the provisioned tool's launcher path
     * ({@code null} on failure); {@code source}/{@code version} feed the client's one-line
     * "Maven X downloaded" note; {@code diag} is the worker's passthrough chatter, carried only when
     * {@code exit != 0}.
     */
    public static String provisionResult(String bin, String version, String source, String error, int exit, String diag) {
        return "{\"t\":\""
                + PROVISION_RESULT
                + "\",\"bin\":"
                + Ndjson.quote(bin)
                + ",\"version\":"
                + Ndjson.quote(version)
                + ",\"source\":"
                + Ndjson.quote(source)
                + ",\"error\":"
                + Ndjson.quote(error)
                + ",\"exit\":"
                + exit
                + ",\"diag\":"
                + Ndjson.quote(diag)
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk format} run's
     * counts and the formatter worker's exit code ({@code jk format --check} exits non-zero when
     * files need formatting — a legitimate outcome, not a goal failure, so it rides here rather
     * than failing the goal). {@code total} of 0 means no sources were found.
     */
    public static String goalFinishFormat(
            String dir, boolean success, int changed, int clean, int errors, int total, int workerExit) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"formatChanged\":"
                + changed
                + ",\"formatClean\":"
                + clean
                + ",\"formatErrors\":"
                + errors
                + ",\"formatTotal\":"
                + total
                + ",\"formatWorkerExit\":"
                + workerExit
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@link #GIT_FETCH_REQUEST}'s
     * materialized checkout path and resolved commit sha ({@code null} when the fetch failed).
     */
    public static String goalFinishGitFetch(String dir, boolean success, String checkout, String sha) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"gitCheckout\":"
                + Ndjson.quote(checkout)
                + ",\"gitSha\":"
                + Ndjson.quote(sha)
                + "}";
    }

    /** As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk publish} run's uploaded-file count. */
    public static String goalFinishPublish(String dir, boolean success, int files) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"publishFiles\":"
                + files
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@code jk import} run's
     * worker exit code, warning count, and error/diagnostic text (all plain — the client prefixes
     * and renders).
     */
    public static String goalFinishImport(
            String dir, boolean success, int exitCode, int warnings, String error, String diag) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"importExit\":"
                + exitCode
                + ",\"importWarnings\":"
                + warnings
                + ",\"importError\":"
                + Ndjson.quote(error)
                + ",\"importDiag\":"
                + Ndjson.quote(diag)
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean, long, long, long, long)} (the image goal runs the full
     * pipeline, so test counts ride along for the exit-code logic), additionally carrying the
     * structured ingredients of the Image chip's success tail: exactly one of {@code imageTarball}
     * (tarball mode), {@code imageDaemonExe} (local-daemon load), or neither (registry push, render
     * {@code imageRef}) is non-null; {@code imageName}/{@code imageVersion} name the image in
     * daemon mode.
     */
    public static String goalFinishImage(
            String dir,
            boolean success,
            long testTotal,
            long testSucceeded,
            long testFailed,
            long testSkipped,
            String ref,
            String tarball,
            String name,
            String version,
            String daemonExe) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"testTotal\":"
                + testTotal
                + ",\"testSucceeded\":"
                + testSucceeded
                + ",\"testFailed\":"
                + testFailed
                + ",\"testSkipped\":"
                + testSkipped
                + ",\"imageRef\":"
                + Ndjson.quote(ref)
                + ",\"imageTarball\":"
                + Ndjson.quote(tarball)
                + ",\"imageName\":"
                + Ndjson.quote(name)
                + ",\"imageVersion\":"
                + Ndjson.quote(version)
                + ",\"imageDaemonExe\":"
                + Ndjson.quote(daemonExe)
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

    // ---- hosted long-tail verbs (Wave 4) ---------------------------------------------------------

    /**
     * Resolve a Maven-published CLI tool (see {@link #TOOL_RESOLVE_REQUEST}). {@code coord} is a
     * {@code ToolCoordSpec} string — pinned {@code g:a:v} or floating {@code g:a[@selector]},
     * pinned engine-side against maven-metadata. {@code with} carries {@code --with} extras (same
     * grammar, may be empty). {@code mainClass} is the {@code --main} override ({@code null} =
     * read the primary jar's manifest engine-side); {@code repoUrl} overrides Maven Central
     * ({@code null} = Central).
     */
    public static String toolResolveRequest(
            String coord, List<String> with, String bin, String mainClass, String repoUrl, String cache) {
        return "{\"t\":\""
                + TOOL_RESOLVE_REQUEST
                + "\",\"coord\":"
                + Ndjson.quote(coord)
                + ",\"with\":"
                + quoteArray(with)
                + ",\"bin\":"
                + Ndjson.quote(bin)
                + ",\"mainClass\":"
                + Ndjson.quote(mainClass)
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@link #TOOL_RESOLVE_REQUEST}
     * result: the pinned {@code g:a:v} the resolve landed on (a floating spec's concrete version is
     * decided engine-side against maven-metadata), the resolved {@code Main-Class}, and the
     * transitive classpath in resolution order (absolute CAS paths — a flat string array, per the
     * codec's no-nested-objects rule). All {@code null}/empty when the resolve failed.
     */
    public static String goalFinishTool(
            String dir, boolean success, String coord, String mainClass, List<String> classpath) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"toolCoord\":"
                + Ndjson.quote(coord)
                + ",\"toolMainClass\":"
                + Ndjson.quote(mainClass)
                + ",\"toolClasspath\":"
                + quoteArray(classpath)
                + "}";
    }

    /**
     * Prepare a loose script/jar for execution (see {@link #SCRIPT_PREPARE_REQUEST}). {@code
     * stateDir}/{@code repoUrl} may be {@code null} (defaults).
     */
    public static String scriptPrepareRequest(
            String mode, String script, String cache, String stateDir, String repoUrl, boolean forceRecompile) {
        return "{\"t\":\""
                + SCRIPT_PREPARE_REQUEST
                + "\",\"mode\":"
                + Ndjson.quote(mode)
                + ",\"script\":"
                + Ndjson.quote(script)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"stateDir\":"
                + Ndjson.quote(stateDir)
                + ",\"repoUrl\":"
                + Ndjson.quote(repoUrl)
                + ",\"forceRecompile\":"
                + forceRecompile
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@link
     * #SCRIPT_PREPARE_REQUEST} result: the exec ingredients the client-side launch needs. Fields
     * not applicable to the prepared mode (and everything on failure) are {@code null}/empty.
     */
    public static String goalFinishScript(
            String dir,
            boolean success,
            String mainClass,
            List<String> classpath,
            String classesDir,
            String kotlincBin,
            String stdlib) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"scriptMainClass\":"
                + Ndjson.quote(mainClass)
                + ",\"scriptClasspath\":"
                + quoteArray(classpath)
                + ",\"scriptClassesDir\":"
                + Ndjson.quote(classesDir)
                + ",\"scriptKotlincBin\":"
                + Ndjson.quote(kotlincBin)
                + ",\"scriptStdlib\":"
                + Ndjson.quote(stdlib)
                + "}";
    }

    /**
     * Run a cache maintenance operation (see {@link #CACHE_PRUNE_REQUEST}). {@code op} is {@code
     * prune}/{@code purge}/{@code gc}; {@code olderThanDays}/{@code sweep}/{@code maxSize} apply to
     * {@code prune} only ({@code maxSize} may be {@code null}); {@code includeJkTmp} asks the prune
     * to also sweep {@code ~/.jk/tmp} (only when the default cache dir is in use, mirroring the
     * in-process command's behavior).
     */
    public static String cachePruneRequest(
            String op, String cache, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {
        return "{\"t\":\""
                + CACHE_PRUNE_REQUEST
                + "\",\"op\":"
                + Ndjson.quote(op)
                + ",\"cache\":"
                + Ndjson.quote(cache)
                + ",\"olderThanDays\":"
                + olderThanDays
                + ",\"dryRun\":"
                + dryRun
                + ",\"sweep\":"
                + sweep
                + ",\"maxSize\":"
                + Ndjson.quote(maxSize)
                + ",\"includeJkTmp\":"
                + includeJkTmp
                + "}";
    }

    /** The maintenance job is waiting for the cache to quiesce (see {@link #PRUNE_WAIT}). */
    public static String pruneWait(int pipelines, boolean external) {
        return "{\"t\":\""
                + PRUNE_WAIT
                + "\",\"pipelines\":"
                + pipelines
                + ",\"external\":"
                + external
                + "}";
    }

    /**
     * As {@link #goalFinish(String, boolean)}, additionally carrying a {@link #CACHE_PRUNE_REQUEST}
     * summary: files removed + bytes freed (what would be removed, on a dry run), the LRU evictor's
     * reachable-eviction count ({@code prune --max-size} only), and the repo-mirror links removed
     * ({@code gc} only). {@code -1} = not applicable to the op.
     */
    public static String goalFinishCache(
            String dir, boolean success, long files, long bytes, long reachableEvicted, long repoLinks) {
        return "{\"t\":\""
                + GOAL_FINISH
                + "\",\"dir\":"
                + Ndjson.quote(dir)
                + ",\"success\":"
                + success
                + ",\"cacheFiles\":"
                + files
                + ",\"cacheBytes\":"
                + bytes
                + ",\"cacheReachableEvicted\":"
                + reachableEvicted
                + ",\"cacheRepoLinks\":"
                + repoLinks
                + "}";
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
