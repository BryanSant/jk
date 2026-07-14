// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.jdk.JdkEnsure;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.ExplainPlan;
import dev.jkbuild.runtime.WorkspaceBuildListener;
import dev.jkbuild.runtime.WorkspaceRequest;
import dev.jkbuild.runtime.WorkspaceResult;
import dev.jkbuild.task.CachePruneScheduler;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CLI-side counterpart to {@link dev.jkbuild.engine.EngineServer}: connects, spawns the engine lazily
 * when none is reachable, and handles version-skew by killing a stale engine and starting a fresh
 * one — all transparent to the caller. See {@code docs/engine.md}.
 */
public final class EngineClient {

    /** Per-read/connect socket timeout — a live engine replies in well under this. */
    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    /**
     * Ceiling for a normal (mapped-cache or no-cache) spawn to come up. A mapped-cache start is
     * sub-second; the pathological case is a <em>cold</em> boot (AOT ignored/disabled), which we
     * must tolerate rather than report as failure. Safe because {@link #awaitStartup} short-circuits
     * the instant the child process exits — a crashed engine still fails in well under a second, so
     * this ceiling is only ever approached by an engine that is genuinely still booting.
     */
    private static final Duration COLD_START_CEILING = Duration.ofSeconds(30);

    /**
     * The wait when the spawn is an AOT-cache <em>training</em> run (first start after the engine
     * jar / host JDK changed, {@code -XX:AOTCacheOutput}): recording makes that one boot much slower
     * than a mapped-cache start, and timing out mid-training reports a healthy engine as failed.
     */
    private static final Duration TRAINING_START_TIMEOUT = Duration.ofSeconds(90);

    /**
     * How long the eager training engine records before we clean-stop it to assemble the cache. A
     * sub-second run yields an empty recording the assembler rejects (see {@code install.sh}); this
     * matches the warm-up's {@code sleep 3} so the startup path is captured.
     */
    private static final long TRAINING_MIN_UPTIME_MS = 3_000;

    /** How long to wait for the {@code .aot} to be assembled by the child JVM after the clean stop. */
    private static final Duration ASSEMBLY_TIMEOUT = Duration.ofSeconds(30);

    private EngineClient() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the engine. */
    public record Handshake(String version, long pid, long startedAtMillis, boolean draining) {}

    /**
     * The {@code jk engine status} snapshot. Memory fields are best-effort: {@code -1} means the
     * engine couldn't observe that number (no OS RSS source, or an older engine that predates the
     * fields). The http fields mirror the wire ({@code docs/http.md}): {@code httpUrl} non-null
     * while the embedded HTTP server is serving, {@code httpError} non-null when {@code [http]} is
     * enabled but it failed to start, both null when the feature is disabled.
     */
    public record Status(
            String version,
            long pid,
            long startedAtMillis,
            int activeRequests,
            int activePipelines,
            boolean draining,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes,
            String httpUrl,
            String httpError) {

        /** {@code true} when the engine has an {@code [http]} table — serving or bind-failed. */
        public boolean httpEnabled() {
            return httpUrl != null || httpError != null;
        }
    }

    /**
     * Connect, ping, and get {@code pong} back — the engine-existence check per {@code docs/engine.md}
     * (never trust a pidfile alone). {@code false} for anything from "nothing is listening" to "it
     * answered something unexpected."
     */
    public static boolean ping(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String reply = exchange(ch, EngineProtocol.ping());
            return EngineProtocol.PONG.equals(EngineProtocol.typeOf(reply));
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and perform the {@code hello}/{@code hello-ack} handshake; empty if unreachable. */
    public static Optional<Handshake> handshake(Path socket, String clientVersion) {
        try (SocketChannel ch = connect(socket)) {
            String ack = exchange(ch, EngineProtocol.hello(clientVersion));
            if (!EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            // Protocol-zero's teeth: an engine speaking a NEWER protocol than this client is not
            // usable — treat it as unreachable so the ensure path elects/starts a matching one
            // (which the newer engine's takeover logic then arbitrates).
            if (Ndjson.intValue(ack, "proto", EngineProtocol.PROTOCOL) > EngineProtocol.PROTOCOL) {
                return Optional.empty();
            }
            return Optional.of(new Handshake(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1),
                    Ndjson.bool(ack, "draining", false)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Connect and request a status snapshot; empty if no engine is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            exchange(ch, EngineProtocol.hello(dev.jkbuild.cli.Jk.VERSION, "probe")); // handshake first, response discarded
            String ack = exchange(ch, EngineProtocol.statusRequest());
            if (!EngineProtocol.STATUS_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Status(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1),
                    Ndjson.intValue(ack, "activeRequests", -1),
                    Ndjson.intValue(ack, "activePipelines", 0),
                    Ndjson.bool(ack, "draining", false),
                    Ndjson.longValue(ack, "heapUsedBytes", -1),
                    Ndjson.longValue(ack, "heapCommittedBytes", -1),
                    Ndjson.longValue(ack, "heapMaxBytes", -1),
                    Ndjson.longValue(ack, "rssBytes", -1),
                    Ndjson.str(ack, "httpUrl"),
                    Ndjson.str(ack, "httpError")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Ask a reachable engine to shut down gracefully; {@code true} if one was reached and acknowledged
     * (or was already not running — stopping a non-running engine is not an error), {@code false} if
     * one was reachable but didn't acknowledge cleanly.
     */
    public static boolean stop(Path socket) {
        SocketChannel ch;
        try {
            ch = connect(socket);
        } catch (IOException e) {
            return true; // nothing reachable — a no-op "stop" is success
        }
        try (ch) {
            String bye = exchange(ch, EngineProtocol.shutdown());
            return EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
        } catch (IOException e) {
            return false; // reachable but didn't behave — a real problem, not "already stopped"
        }
    }

    /**
     * Schedule a graceful drain: the engine refuses new jobs and exits cleanly once in-flight jobs
     * finish. Returns the in-flight job count at the moment of the request (0 → the engine is exiting
     * now), or {@code -1} when nothing was reachable (a no-op stop).
     */
    public static int drain(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String bye = exchange(ch, EngineProtocol.shutdown(false));
            if (!EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye))) return -1;
            return Ndjson.intValue(bye, "pipelines", 0);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Force an immediate shutdown: the engine exits now via its clean-exit path (abandoning in-flight
     * job connections but still assembling the AOT cache). {@code true} if acknowledged or nothing was
     * running; {@code false} if reachable but unresponsive (caller may {@link #killStale} as fallback).
     */
    public static boolean forceStop(Path socket) {
        SocketChannel ch;
        try {
            ch = connect(socket);
        } catch (IOException e) {
            return true; // nothing reachable — already stopped
        }
        try (ch) {
            String bye = exchange(ch, EngineProtocol.shutdown(true));
            return EngineProtocol.BYE.equals(EngineProtocol.typeOf(bye));
        } catch (IOException e) {
            return false;
        }
    }

    /** Last-resort SIGTERM→SIGKILL when a clean {@link #forceStop} can't reach a wedged engine. */
    public static void hardKill(long pid) {
        killStale(pid, COLD_START_CEILING);
    }

    // ---- build history ({@code jk history}) — thin RPC over the engine's journal ----------------

    /** Newest-first {@code history-entry} lines (flat NDJSON), spawning the engine if none is running. */
    public static List<String> historyList(EnginePaths.Paths paths, int limit) throws IOException {
        return streamHistory(paths, EngineProtocol.historyListRequest(limit));
    }

    /** One entry's detail: a {@code history-record} header line plus module/phase/diag lines. */
    public static List<String> historyShow(EnginePaths.Paths paths, String id) throws IOException {
        return streamHistory(paths, EngineProtocol.historyShowRequest(id));
    }

    /** Delete one entry; {@code true} if it existed. */
    public static boolean historyDelete(EnginePaths.Paths paths, String id) throws IOException {
        for (String line : streamHistory(paths, EngineProtocol.historyDeleteRequest(id))) {
            if (EngineProtocol.HISTORY_DELETED.equals(EngineProtocol.typeOf(line))) {
                return Ndjson.bool(line, "deleted", false);
            }
        }
        return false;
    }

    /**
     * Running aggregate rows ({@code metrics-entry} flat NDJSON) for {@code dir}'s project tiers
     * plus the global tiers; {@code null} dir asks for every row. Spawns the engine if needed.
     */
    public static List<String> metrics(EnginePaths.Paths paths, String dir) throws IOException {
        return streamHistory(paths, EngineProtocol.metricsRequest(dir));
    }

    /** Send a history/metrics request, collect the flat reply lines up to (not including) the terminal. */
    private static List<String> streamHistory(EnginePaths.Paths paths, String request) throws IOException {
        ensureRunning(paths, dev.jkbuild.cli.Jk.VERSION);
        List<String> out = new java.util.ArrayList<>();
        try (SocketChannel ch = connect(EnginePaths.activeSocket(paths))) {
            BufferedWriter writer =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            writer.write(request);
            writer.write('\n');
            writer.flush();
            BufferedReader reader =
                    protocolReader(ch);
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.HISTORY_DONE.equals(type) || EngineProtocol.METRICS_DONE.equals(type)) break;
                out.add(line);
                if (EngineProtocol.HISTORY_DELETED.equals(type) || EngineProtocol.ERROR.equals(type)) break;
            }
        }
        return out;
    }

    /**
     * The one entry point real commands use: a live, version-matched engine is guaranteed to be
     * reachable at {@code paths.socket()} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) engine transparently. Throws with a
     * message pointing at the engine's log file if it still can't be reached after a fresh spawn —
     * per {@code docs/engine.md}, the engine is load-bearing and this is not silently swallowed.
     */
    public static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion) throws IOException {
        return doEnsure(paths, clientVersion, false).handshake();
    }

    /** Outcome of {@link #ensureReady}: the live engine, and whether an optimization wedge was shown. */
    public record EngineReady(Handshake handshake, boolean optimized) {}

    /**
     * Engine-readiness prewarm for the top of an engine-backed command, <em>before</em> it builds
     * its own goal console. Identical to {@link #ensureRunning(EnginePaths.Paths, String)} except
     * that a one-time AOT optimization renders an animated "Engine — Optimizing build engine…" chip
     * that settles into "✓ Engine  Build engine optimized and started (pid N)", so the user sees why
     * the first build after an install/upgrade pauses, and the command's own TUI then takes over
     * (sequential, never interleaved). {@code optimized} is true when that success chip was printed,
     * so the caller can suppress its own "started" line. A running engine → fast no-op, nothing shown.
     */
    public static EngineReady ensureReady(EnginePaths.Paths paths, String clientVersion) throws IOException {
        return doEnsure(paths, clientVersion, true);
    }

    /**
     * Run a workspace build against the engine at {@code paths} instead of in-process — the engine
     * equivalent of the engine's {@code BuildService.buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/engine.md} there is no in-process fallback.
     */
    public static WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Everything an engine-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean verbose,
            boolean offline,
            boolean force) {}

    /**
     * Run a single project's test goal against the engine (Phase 3) — see {@link
     * EngineBuildListenerAdapter#runTest} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runTest(
            EnginePaths.Paths paths,
            TestRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runTest(paths, req, listenerFactory, testResultOut);
    }

    /** Everything an engine-hosted single-project {@code jk build} needs — mirrors {@code BuildCommand}'s local fields. */
    public record SingleBuildRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            int workers,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean offline,
            boolean force,
            String variant,
            java.util.Map<String, String> clientEnv) {

        /** Back-compat: default variant, no client env. */
        public SingleBuildRequest(
                Path entryDir,
                Path cache,
                Path jdksDir,
                int workers,
                String profile,
                boolean skipTests,
                boolean verbose,
                boolean offline,
                boolean force) {
            this(entryDir, cache, jdksDir, workers, profile, skipTests, verbose, offline, force, "",
                    java.util.Map.of());
        }
    }

    /**
     * Run a single (non-workspace) project's build against the engine — the engine equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * EngineBuildListenerAdapter#runSingleBuild} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runSingleBuild(
            EnginePaths.Paths paths,
            SingleBuildRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /**
     * Everything an engine-hosted {@code jk explain} needs: the entry dir/cache for the plan, plus
     * the plan-affecting {@code jk build} options the engine-side ETA estimate feeds through the
     * shared goal assembly (Wave 3 — the estimate used to be computed client-side).
     */
    /** One engine-hosted jk.toml edit (EDIT_REQUEST): returns changed; throws on error. */
    public static boolean edit(
            dev.jkbuild.engine.EnginePaths.Paths paths, java.nio.file.Path file, String op,
            java.util.List<String> args) throws java.io.IOException {
        return EngineBuildListenerAdapter.edit(paths, file, op, args);
    }

    /**
     * Thin-client project summary (docs/thin-client-plan.md §2.1) — the replacement for every
     * client-side {@code JkBuildParser.parse} peek. In test/no-engine mode the in-process twin
     * answers; otherwise one synchronous PROJECT_INFO round trip.
     */
    public static dev.jkbuild.engine.protocol.ProjectInfo projectInfo(
            dev.jkbuild.engine.EnginePaths.Paths paths, java.nio.file.Path dir) throws java.io.IOException {
        return EngineBuildListenerAdapter.projectInfo(paths, dir);
    }

    /**
     * Thin-client deny check: the [deny] policy is user-authored jk.toml and therefore parses
     * engine-side only; one synchronous DENY_CHECK round trip returns the violations.
     */
    /** Thin-client IDE model: engine computes the workspace model, client generates the files. */
    public static dev.jkbuild.engine.protocol.IdeWireModel ideModel(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            java.nio.file.Path jdksDir)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.ideModel(paths, dir, cache, jdksDir);
    }

        /** A plugin-declared verb, worker-executed engine-side (found=false → normal help). */
    public static dev.jkbuild.engine.protocol.PluginVerbReport pluginVerb(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String verb,
            java.util.List<String> args)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.pluginVerb(paths, dir, cache, verb, args);
    }

        /** Thin-client generator run: engine renders content, client guards/writes/prints. */
    public static dev.jkbuild.engine.protocol.GeneratedFiles generate(
            dev.jkbuild.engine.EnginePaths.Paths paths, java.nio.file.Path dir, String kind)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.generate(paths, dir, kind, java.util.Map.of());
    }

    /** As above with generator parameters (scaffold inputs etc.). */
    public static dev.jkbuild.engine.protocol.GeneratedFiles generate(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            String kind,
            java.util.Map<String, String> params)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.generate(paths, dir, kind, params);
    }

        /** Thin-client tree render: engine walks the graph, client substitutes its Theme into the tags. */
    public static String treeRender(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            int maxDepth,
            boolean flatten,
            boolean stack,
            java.util.List<String> scopes)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.treeRender(paths, dir, maxDepth, flatten, stack, scopes);
    }

    /** Thin-client why lookup: lock matching + provenance paths, engine-side. */
    public static dev.jkbuild.engine.protocol.WhyReport why(
            dev.jkbuild.engine.EnginePaths.Paths paths, java.nio.file.Path dir, String query)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.why(paths, dir, query);
    }

        public static dev.jkbuild.engine.protocol.DenyReport denyCheck(
            dev.jkbuild.engine.EnginePaths.Paths paths, java.nio.file.Path dir) throws java.io.IOException {
        return EngineBuildListenerAdapter.denyCheck(paths, dir);
    }

    /**
     * Thin-client execution plan (docs/thin-client-plan.md §2.2): the engine decides run/dev
     * argv, install layout, or aot-cache layout; the caller executes.
     */
    public static dev.jkbuild.engine.protocol.ExecPlan execPlan(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.execPlan(paths, dir, cache, kind, mainOverride, binName, null, null);
    }

    /** As above with install-destination overrides ({@code --bin-dir}/{@code --lib-dir}). */
    public static dev.jkbuild.engine.protocol.ExecPlan execPlan(
            dev.jkbuild.engine.EnginePaths.Paths paths,
            java.nio.file.Path dir,
            java.nio.file.Path cache,
            String kind,
            String mainOverride,
            String binName,
            java.nio.file.Path binDir,
            java.nio.file.Path libDir)
            throws java.io.IOException {
        return EngineBuildListenerAdapter.execPlan(paths, dir, cache, kind, mainOverride, binName, binDir, libDir);
    }

    public record ExplainRequest(
            Path entryDir,
            Path cache,
            int workers,
            boolean skipTests,
            String profile,
            Path jdksDir,
            boolean serial,
            boolean parallelTests,
            boolean verbose) {}

    /**
     * Pre-flight a build's dirty forecast against the engine — {@code jk build}'s fully-cached
     * shortcut and dirty hint (see {@link EngineBuildListenerAdapter#forecast}).
     */
    public static dev.jkbuild.runtime.BuildForecast forecast(
            EnginePaths.Paths paths, Path entryDir, Path cache, boolean skipTests) throws IOException {
        return EngineBuildListenerAdapter.forecast(paths, entryDir, cache, skipTests);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineBuildListenerAdapter#explain} for the exact contract. {@code etaOut} (a single-slot
     * holder, may be {@code null}) receives the engine-computed build-time estimate in millis
     * ({@code 0} = unknown).
     */
    public static ExplainPlan explain(EnginePaths.Paths paths, ExplainRequest req, long[] etaOut)
            throws IOException {
        return EngineBuildListenerAdapter.explain(paths, req, etaOut);
    }

    // ---- resolver family (jk lock / update / sync — Wave 1 of the slim client) ----------------

    /** Everything an engine-hosted {@code jk lock} needs — mirrors {@code LockCommand}'s local fields. */
    public record LockRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            boolean sources,
            java.net.URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /** Everything an engine-hosted {@code jk update} needs — mirrors {@code UpdateCommand}'s local fields. */
    public record UpdateRequest(
            Path entryDir,
            Path cache,
            List<String> features,
            boolean noDefaultFeatures,
            java.net.URI repoUrl,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /** Everything an engine-hosted {@code jk sync} needs — mirrors {@code SyncCommand}'s local fields. */
    public record SyncRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            java.net.URI repoUrl,
            boolean sources,
            boolean offline,
            boolean force,
            boolean refresh,
            boolean verbose) {}

    /**
     * A lock/update cascade's client-side renderer contract — see {@link EngineResolveAdapter} for
     * the wire mechanics. {@code onModuleStart} is invoked once per module (entry project first,
     * then workspace modules in declaration order), after its phase list has arrived, and returns
     * the {@link dev.jkbuild.run.GoalListener} the module's wire events should drive — the same
     * listener the in-process path would attach to the live goal. {@code onPackage} fires per
     * resolved package (plain, unthemed — the renderer colorizes); {@code onModuleFinish} fires
     * after that listener's own {@code goalFinish} has been dispatched.
     */
    public interface LockHandler {
        dev.jkbuild.run.GoalListener onModuleStart(String dir, String coord, List<dev.jkbuild.run.Phase> phases);

        default void onPackage(String dir, String name, String version) {}

        default void onModuleFinish(String dir, dev.jkbuild.run.GoalResult result, LockCounts counts) {}
    }

    /** A finished lock/update module's written-lockfile counts ({@code -1} when the goal failed before writing). */
    public record LockCounts(long packages, long sources, long plugins) {}

    /**
     * A lock/update request's terminal outcome. {@code errors} carries pre-goal failures (manifest
     * parse, workspace module load) as plain text; {@code refreshed} is {@code jk update --git}'s
     * refreshed count ({@code -1} otherwise). {@code exitCode} is authoritative — computed
     * engine-side from the phase statuses (resolve failure exits 6, config problems 2).
     */
    public record LockOutcome(boolean success, int exitCode, List<String> errors, int refreshed) {}

    /**
     * Run {@code jk lock}'s workspace cascade against the engine — see {@link
     * EngineResolveAdapter#runLock} for the exact contract. {@code handler} is the command's
     * renderer; the returned outcome's {@code exitCode} is authoritative (computed engine-side).
     */
    public static LockOutcome runLock(EnginePaths.Paths paths, LockRequest req, LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runLock(paths, req, handler);
    }

    /**
     * Run {@code jk update}'s full re-resolve cascade against the engine (rides {@code jk lock}'s
     * event vocabulary) — see {@link EngineResolveAdapter#runUpdate}.
     */
    public static LockOutcome runUpdate(EnginePaths.Paths paths, UpdateRequest req, LockHandler handler)
            throws IOException {
        return EngineResolveAdapter.runUpdate(paths, req, handler);
    }

    /**
     * Run {@code jk update --git [<name>]} against the engine ({@code gitTarget == null} refreshes
     * every git dependency) — see {@link EngineResolveAdapter#runUpdateGitOnly}.
     */
    public static LockOutcome runUpdateGitOnly(EnginePaths.Paths paths, UpdateRequest req, String gitTarget)
            throws IOException {
        return EngineResolveAdapter.runUpdateGitOnly(paths, req, gitTarget);
    }

    /**
     * Run {@code jk sync}'s single goal against the engine — see {@link
     * EngineResolveAdapter#runSync} for the exact contract (the {@code jk test} listener-factory
     * shape, plus fetched/up-to-date count holders for the summary line).
     */
    public static dev.jkbuild.run.GoalResult runSync(
            EnginePaths.Paths paths,
            SyncRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            long[] fetchedOut,
            long[] upToDateOut)
            throws IOException {
        return EngineResolveAdapter.runSync(paths, req, listenerFactory, fetchedOut, upToDateOut);
    }

    /** Everything an engine-hosted {@code jk outdated} needs — mirrors {@code OutdatedCommand}'s local fields. */
    public record OutdatedRequest(Path entryDir, Path cache, java.net.URI repoUrl, boolean offline, boolean force) {}

    /**
     * Report declared dependencies with newer versions available against the engine ({@code jk
     * outdated}) — one synchronous request, one {@link dev.jkbuild.engine.protocol.OutdatedReport}
     * back. Read-only: the engine enumerates versions and writes nothing.
     */
    public static dev.jkbuild.engine.protocol.OutdatedReport runOutdated(
            EnginePaths.Paths paths, OutdatedRequest req) throws IOException {
        return EngineResolveAdapter.runOutdated(paths, req);
    }

    // ---- hosted worker verbs (Wave 2 of the slim client) ---------------------------------------

    /** Everything an engine-hosted {@code jk audit} needs — mirrors {@code AuditCommand}'s local fields. */
    public record AuditRequest(
            Path entryDir, Path cache, String severity, java.net.URI osvBatchUrl, java.net.URI osvVulnsUrl) {}

    /**
     * Run {@code jk audit}'s goal against the engine (the worker forks engine-side). Findings
     * stream to {@code findings} as plain structured strings — the command assembles/renders the
     * report and applies the severity threshold itself.
     */
    public static dev.jkbuild.run.GoalResult runAudit(
            EnginePaths.Paths paths,
            AuditRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.FindingObserver findings)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.auditRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.severity(),
                                req.osvBatchUrl() != null ? req.osvBatchUrl().toString() : null,
                                req.osvVulnsUrl() != null ? req.osvVulnsUrl().toString() : null),
                        "audit",
                        listenerFactory,
                        (type, line) -> findings.onFinding(
                                Ndjson.str(line, "module"),
                                Ndjson.str(line, "version"),
                                Ndjson.str(line, "vulnId"),
                                Ndjson.str(line, "severity"),
                                Ndjson.str(line, "summary")))
                .result();
    }

    /** Everything an engine-hosted {@code jk format} needs — resolved styles, not raw flags. */
    public record FormatRequest(
            Path entryDir,
            Path cache,
            boolean check,
            String javaStyle,
            String kotlinStyle,
            boolean optimizeImports,
            Path rewriteConfig,
            boolean offline,
            boolean verbose) {}

    /** A hosted {@code jk format} run's summary, decoded from the terminal goal-finish. */
    public record FormatOutcome(
            dev.jkbuild.run.GoalResult result, int changed, int clean, int errors, int total, int workerExit) {}

    /**
     * Run {@code jk format}'s goal against the engine (source collection, formatter-jar resolution,
     * and the worker fork all engine-side). Per-file results stream to {@code files}; the counts
     * (and the worker's check-mode exit code) ride the returned outcome.
     */
    public static FormatOutcome runFormat(
            EnginePaths.Paths paths,
            FormatRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.FileObserver files)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.formatRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.check(),
                        req.javaStyle(),
                        req.kotlinStyle(),
                        req.optimizeImports(),
                        req.rewriteConfig() != null ? req.rewriteConfig().toString() : null,
                        req.offline(),
                        req.verbose()),
                "format",
                listenerFactory,
                (type, line) -> files.onFile(
                        Ndjson.str(line, "path"),
                        Ndjson.str(line, "status"),
                        Ndjson.str(line, "message"),
                        Ndjson.intValue(line, "index", 0),
                        Ndjson.intValue(line, "total", 0)));
        return new FormatOutcome(
                finish.result(),
                Ndjson.intValue(finish.finishLine(), "formatChanged", -1),
                Ndjson.intValue(finish.finishLine(), "formatClean", -1),
                Ndjson.intValue(finish.finishLine(), "formatErrors", -1),
                Ndjson.intValue(finish.finishLine(), "formatTotal", -1),
                Ndjson.intValue(finish.finishLine(), "formatWorkerExit", -1));
    }

    /**
     * Everything an engine-hosted {@code jk publish} needs. The credential and GPG passphrase were
     * resolved client-side (env/keychain live here, not in the engine's inherited environment); they
     * cross the user-owned socket and are never logged.
     */
    public record PublishRequest(
            Path entryDir,
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
            boolean verbose) {}

    /** A hosted {@code jk publish} run's summary, decoded from the terminal goal-finish. */
    public record PublishOutcome(dev.jkbuild.run.GoalResult result, int files) {}

    /** Run {@code jk publish}'s goal against the engine (the publisher worker forks engine-side). */
    public static PublishOutcome runPublish(
            EnginePaths.Paths paths,
            PublishRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        String authType;
        String user = null;
        String pass = null;
        String token = null;
        if (req.credential() instanceof dev.jkbuild.credential.RepoCredential.Basic b) {
            authType = "basic";
            user = b.username();
            pass = b.password();
        } else if (req.credential() instanceof dev.jkbuild.credential.RepoCredential.Bearer b) {
            authType = "bearer";
            token = b.token();
        } else {
            authType = "anonymous";
        }
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.publishRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.repoUrl().toString(),
                        req.region(),
                        req.endpoint(),
                        req.jarPath() != null ? req.jarPath().toString() : null,
                        req.allowSnapshot(),
                        req.dryRun(),
                        req.keyFile() != null ? req.keyFile().toString() : null,
                        req.gpgPassphrase(),
                        req.sigstore(),
                        req.slsa(),
                        req.sbom(),
                        authType,
                        user,
                        pass,
                        token,
                        req.verbose()),
                "publish",
                listenerFactory,
                (type, line) -> {});
        return new PublishOutcome(finish.result(), Ndjson.intValue(finish.finishLine(), "publishFiles", -1));
    }

    /** Everything an engine-hosted {@code jk image} needs — mirrors {@code ImageCommand}'s local fields. */
    public record ImageRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            String registry,
            String tag,
            String tarballArg,
            String dockerExecutable,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean rerun,
            boolean verbose) {}

    /**
     * A hosted {@code jk image} run's structured summary. Exactly one of {@code tarball} (tarball
     * mode) or {@code daemonExe} (daemon-load mode) is non-null, or neither (registry push — render
     * {@code ref}); {@code testResult} is non-null when the pipeline's run-tests phase reported
     * counts.
     */
    public record ImageSummary(
            dev.jkbuild.run.TestSummary testResult,
            String ref,
            String tarball,
            String name,
            String version,
            String daemonExe) {}

    /**
     * Run {@code jk image}'s goal against the engine (full pipeline + image tail engine-side).
     * {@code summaryOut} (a single-slot holder) is populated from the terminal goal-finish
     * <em>before</em> it reaches {@code listenerFactory}'s listener — whose own {@code goalFinish}
     * handler renders the success tail from those fields, exactly the {@code runTest} holder
     * pattern.
     */
    public static dev.jkbuild.run.GoalResult runImage(
            EnginePaths.Paths paths,
            ImageRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            ImageSummary[] summaryOut)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.imageRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.jdksDir() != null ? req.jdksDir().toString() : null,
                                req.mainClass(),
                                req.registry(),
                                req.tag(),
                                req.tarballArg(),
                                req.dockerExecutable(),
                                req.skipTests(),
                                req.offline(),
                                req.force(),
                                req.rerun(),
                                req.verbose()),
                        "image",
                        listenerFactory,
                        (type, line) -> {},
                        line -> {
                            long total = Ndjson.longValue(line, "testTotal", -1);
                            dev.jkbuild.run.TestSummary testResult = total < 0
                                    ? null
                                    : new dev.jkbuild.run.TestSummary(
                                            total,
                                            Ndjson.longValue(line, "testSucceeded", 0),
                                            Ndjson.longValue(line, "testFailed", 0),
                                            Ndjson.longValue(line, "testSkipped", 0),
                                            List.of());
                            summaryOut[0] = new ImageSummary(
                                    testResult,
                                    Ndjson.str(line, "imageRef"),
                                    Ndjson.str(line, "imageTarball"),
                                    Ndjson.str(line, "imageName"),
                                    Ndjson.str(line, "imageVersion"),
                                    Ndjson.str(line, "imageDaemonExe"));
                        })
                .result();
    }

    /** Everything an engine-hosted {@code jk import} needs — pre-flighted absolute paths. */
    public record ImportRequest(
            Path source, Path out, Path baseDir, Path tmpDir, boolean force, Path report, Path cache) {}

    /** A hosted {@code jk import} run's summary, decoded from the terminal goal-finish. */
    public record ImportOutcome(
            dev.jkbuild.run.GoalResult result, int exitCode, int warnings, String error, String diag) {}

    /** Run {@code jk import}'s goal against the engine, streaming progress notes to {@code notes}. */
    public static ImportOutcome runImport(
            EnginePaths.Paths paths,
            ImportRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.runtime.HostedEvents.NoteObserver notes)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.importRequest(
                        req.source().toString(),
                        req.out().toString(),
                        req.baseDir().toString(),
                        req.tmpDir().toString(),
                        req.force(),
                        req.report() != null ? req.report().toString() : null,
                        req.cache().toString()),
                "import",
                listenerFactory,
                (type, line) -> notes.onNote(Ndjson.str(line, "kind"), Ndjson.str(line, "text")));
        String line = finish.finishLine();
        return new ImportOutcome(
                finish.result(),
                Ndjson.intValue(line, "importExit", 1),
                Ndjson.intValue(line, "importWarnings", 0),
                Ndjson.str(line, "importError"),
                Ndjson.str(line, "importDiag"));
    }

    /**
     * Provision a Maven/Gradle distribution via the engine ({@code jk mvn}/{@code jk gradle}) — a
     * one-shot request; the exec of the provisioned tool stays in this client process (it inherits
     * this terminal's stdio, which the engine deliberately never touches).
     */
    public static dev.jkbuild.runtime.HostedEvents.Provision provision(
            EnginePaths.Paths paths, Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EngineWorkerAdapter.provision(
                paths,
                EngineProtocol.provisionRequest(
                        cache.toString(), projectDir.toString(), toolsRoot.toString(), noDiscover, gradle));
    }

    // ---- hosted pipeline verbs (Wave 3 of the slim client) --------------------------------------

    /** Everything an engine-hosted {@code jk compile} needs — mirrors {@code CompileCommand}'s local fields. */
    public record CompileRequest(
            Path entryDir, Path cache, String profile, boolean offline, boolean force, boolean verbose) {}

    /**
     * Run {@code jk compile}'s compile-only goal against the engine — {@code jk test}'s
     * listener-factory shape, plain terminal goal-finish.
     */
    public static dev.jkbuild.run.GoalResult runCompile(
            EnginePaths.Paths paths,
            CompileRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        return EngineWorkerAdapter.stream(
                        paths,
                        EngineProtocol.compileRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.profile(),
                                req.offline(),
                                req.force(),
                                req.verbose()),
                        "compile",
                        listenerFactory,
                        (type, line) -> {})
                .result();
    }

    /**
     * Everything an engine-hosted {@code jk native} needs. {@code graalByDir} maps each
     * native-eligible module dir to the GraalVM home the client resolved for it — resolution (and
     * any consent prompt / install) happens client-side <em>before</em> this request, because it
     * owns the terminal.
     */
    public record NativeRequest(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose,
            List<String> extraArgs,
            java.util.Map<Path, Path> graalByDir) {}

    /**
     * Run {@code jk native}'s hosted module cascade against the engine, driving {@code listener}
     * exactly as {@link #buildWorkspace} does (the cascade speaks the workspace event vocabulary; a
     * single project is a cascade of one). The returned result's {@code exitCode} is authoritative
     * — computed engine-side with {@code jk native}'s 64/4/1 mapping.
     */
    public static WorkspaceResult runNative(
            EnginePaths.Paths paths, NativeRequest req, WorkspaceBuildListener listener) throws IOException {
        return EngineBuildListenerAdapter.runNative(paths, req, listener);
    }

    /**
     * Everything an engine-hosted {@code jk install} (project mode) needs. {@code m2Dir} is the
     * resolved local Maven repo root; {@code graalHome} is non-null only for a native application
     * (resolved client-side, same pre-flight as {@link NativeRequest}).
     */
    public record InstallRequest(
            Path entryDir,
            Path cache,
            Path m2Dir,
            Path graalHome,
            boolean skipTests,
            boolean offline,
            boolean force,
            boolean verbose) {}

    /**
     * Run {@code jk install}'s build + cache-install goal against the engine — {@link #runTest}'s
     * exact contract ({@code testResultOut} settles before the terminal goal-finish reaches the
     * listener). The launcher-writing "make install" half stays in the calling command.
     */
    public static dev.jkbuild.run.GoalResult runInstall(
            EnginePaths.Paths paths,
            InstallRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runInstall(paths, req, listenerFactory, testResultOut);
    }

    /** Everything an engine-hosted {@code jk install <git-url>} fetch needs — pre-split/expanded client-side. */
    public record GitFetchRequest(
            String url, String canonicalUrl, String ref, Path cache, boolean refresh, boolean requireJkToml) {
        public GitFetchRequest(String url, String canonicalUrl, String ref, Path cache, boolean refresh) {
            this(url, canonicalUrl, ref, cache, refresh, true);
        }
    }

    /** A hosted git fetch's outcome: the goal result plus the materialized checkout + sha (null on failure). */
    public record GitFetchOutcome(dev.jkbuild.run.GoalResult result, Path checkout, String sha) {}

    /**
     * Materialize a git checkout via the engine ({@code jk install <git-url>}'s clone half; git
     * runs in-process in the engine). The checkout path + resolved sha ride the terminal
     * goal-finish and feed the follow-up {@link #runInstall}.
     */
    public static GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            GitFetchRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.gitFetchRequest(
                        req.url(),
                        req.canonicalUrl(),
                        req.ref(),
                        req.cache().toString(),
                        req.refresh(),
                        req.requireJkToml()),
                "install-git-fetch",
                listenerFactory,
                (type, line) -> {});
        String checkout = Ndjson.str(finish.finishLine(), "gitCheckout");
        return new GitFetchOutcome(
                finish.result(), checkout != null ? Path.of(checkout) : null, Ndjson.str(finish.finishLine(), "gitSha"));
    }

    // ---- hosted long-tail verbs (Wave 4 of the slim client) -------------------------------------

    /**
     * Everything an engine-hosted tool resolution needs ({@code jk tool install}/{@code jk tool
     * run}/{@code jk install <g:a:v>}). {@code mainClass} is the {@code --main} override (may be
     * {@code null}); {@code repoUrl} overrides Maven Central (may be {@code null}).
     */
    public record ToolResolveRequest(
            String coord, List<String> with, String bin, String mainClass, java.net.URI repoUrl, Path cache) {}

    /**
     * A hosted tool resolution's outcome: the goal result plus the pinned {@code g:a:v} the engine
     * landed on, the resolved main class, and the classpath (null/empty on failure) — the
     * ingredients of a client-side {@code ToolEnv}.
     */
    public record ToolResolveOutcome(
            dev.jkbuild.run.GoalResult result, String coord, String mainClass, List<Path> classpath) {}

    /**
     * Resolve a Maven-published CLI tool against the engine (the POM walk + jar fetches run
     * engine-side; see {@code ToolGoals}). The launcher write / inheritIO exec stays in the calling
     * command — it owns the user's {@code ~/.jk/bin} and terminal.
     */
    public static ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            ToolResolveRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.toolResolveRequest(
                        req.coord(),
                        req.with(),
                        req.bin(),
                        req.mainClass(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.cache().toString()),
                "tool-resolve",
                listenerFactory,
                (type, line) -> {});
        return new ToolResolveOutcome(
                finish.result(),
                Ndjson.str(finish.finishLine(), "toolCoord"),
                Ndjson.str(finish.finishLine(), "toolMainClass"),
                Ndjson.strArray(finish.finishLine(), "toolClasspath").stream()
                        .map(Path::of)
                        .toList());
    }

    /**
     * Everything an engine-hosted script/jar preparation needs ({@code jk tool run <file>}).
     * {@code mode} = {@code java}/{@code kt}/{@code kts}/{@code jar}; {@code stateDir}/{@code
     * repoUrl} may be {@code null} (defaults).
     */
    public record ScriptPrepareRequest(
            String mode,
            Path script,
            Path cache,
            Path stateDir,
            java.net.URI repoUrl,
            boolean forceRecompile,
            List<String> with) {
        public ScriptPrepareRequest(
                String mode, Path script, Path cache, Path stateDir, java.net.URI repoUrl, boolean forceRecompile) {
            this(mode, script, cache, stateDir, repoUrl, forceRecompile, List.of());
        }
    }

    /**
     * A hosted script preparation's outcome: the goal result plus the exec ingredients — fields not
     * applicable to the mode (and everything on failure) are {@code null}/empty.
     */
    public record ScriptPrepareOutcome(
            dev.jkbuild.run.GoalResult result,
            String mainClass,
            List<Path> classpath,
            Path classesDir,
            Path kotlincBin,
            Path stdlib) {}

    /**
     * Prepare a loose script/jar against the engine ({@code jk tool run <file>}: header parse, dep
     * resolution, compile / kotlinc provision / manifest inspection all engine-side — see {@code
     * ScriptGoals}). The exec of the prepared program stays in the calling command — it owns this
     * terminal.
     */
    public static ScriptPrepareOutcome runScriptPrepare(
            EnginePaths.Paths paths,
            ScriptPrepareRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory)
            throws IOException {
        EngineWorkerAdapter.HostedFinish finish = EngineWorkerAdapter.stream(
                paths,
                EngineProtocol.scriptPrepareRequest(
                        req.mode(),
                        req.script().toString(),
                        req.cache().toString(),
                        req.stateDir() != null ? req.stateDir().toString() : null,
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.forceRecompile(),
                        req.with()),
                "script-prepare",
                listenerFactory,
                (type, line) -> {});
        String line = finish.finishLine();
        String classesDir = Ndjson.str(line, "scriptClassesDir");
        String kotlincBin = Ndjson.str(line, "scriptKotlincBin");
        String stdlib = Ndjson.str(line, "scriptStdlib");
        return new ScriptPrepareOutcome(
                finish.result(),
                Ndjson.str(line, "scriptMainClass"),
                Ndjson.strArray(line, "scriptClasspath").stream().map(Path::of).toList(),
                classesDir != null ? Path.of(classesDir) : null,
                kotlincBin != null ? Path.of(kotlincBin) : null,
                stdlib != null ? Path.of(stdlib) : null);
    }

    /**
     * Everything an engine-hosted cache maintenance op needs ({@code op} = {@code prune}/{@code
     * purge}/{@code gc} — {@code jk cache prune}/{@code purge}, {@code jk clean --cache}). {@code
     * maxSize} may be {@code null}; the non-prune ops ignore the prune-only fields.
     */
    public record CacheMaintRequest(
            String op,
            Path cache,
            int olderThanDays,
            boolean dryRun,
            boolean sweep,
            String maxSize,
            boolean includeJkTmp,
            Path projectRoot) {

        /** Prune/purge/gc request — no project scope. */
        public CacheMaintRequest(
                String op, Path cache, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {
            this(op, cache, olderThanDays, dryRun, sweep, maxSize, includeJkTmp, null);
        }
    }

    /** A hosted cache maintenance op's summary, decoded from the terminal goal-finish ({@code -1} = n/a). */
    public record CacheMaintSummary(long files, long bytes, long reachableEvicted, long repoLinks) {}

    /**
     * Run a cache maintenance op against the engine, which executes it as an idle-boundary job: the
     * mutation waits until no pipeline is in flight (and blocks new ones while it runs), holding the
     * cross-process {@code .prune.lock} throughout. {@code onWait} fires when the engine reports the
     * job is queued — {@code pipelines} in-flight builds ({@code external=true}: another process's
     * prune) — so the command can explain the pause before the progress UI starts. {@code
     * summaryOut} (a single-slot holder) is populated from the terminal goal-finish <em>before</em>
     * it reaches {@code listenerFactory}'s listener, whose own {@code goalFinish} handler renders
     * the summary line from those fields — the {@code runImage} holder pattern.
     */
    public static dev.jkbuild.run.GoalResult runCacheMaintenance(
            EnginePaths.Paths paths,
            CacheMaintRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            java.util.function.ObjIntConsumer<Boolean> onWait,
            CacheMaintSummary[] summaryOut)
            throws IOException {
        String requestLine = "clear".equals(req.op())
                ? EngineProtocol.cacheClearRequest(
                        req.cache().toString(), req.projectRoot().toString(), req.dryRun())
                : EngineProtocol.cachePruneRequest(
                        req.op(),
                        req.cache().toString(),
                        req.olderThanDays(),
                        req.dryRun(),
                        req.sweep(),
                        req.maxSize(),
                        req.includeJkTmp());
        return EngineWorkerAdapter.stream(
                        paths,
                        requestLine,
                        "cache-" + req.op(),
                        listenerFactory,
                        (type, line) -> onWait.accept(
                                Ndjson.bool(line, "external", false), Ndjson.intValue(line, "pipelines", 0)),
                        line -> summaryOut[0] = new CacheMaintSummary(
                                Ndjson.longValue(line, "cacheFiles", -1),
                                Ndjson.longValue(line, "cacheBytes", -1),
                                Ndjson.longValue(line, "cacheReachableEvicted", -1),
                                Ndjson.longValue(line, "cacheRepoLinks", -1)))
                .result();
    }

    private static EngineReady doEnsure(EnginePaths.Paths paths, String clientVersion, boolean renderWedge)
            throws IOException {
        Optional<Handshake> existing = handshake(EnginePaths.activeSocket(paths), clientVersion);
        if (existing.isPresent()) {
            Handshake hs = existing.get();
            // A draining engine still owns the socket + file lock and is finishing in-flight jobs.
            // Fail fast — do NOT fall through to spawn a competing engine, and don't killStale it.
            if (hs.draining()) {
                throw new IOException(
                        "the build engine is shutting down — wait for it to stop, or run `jk engine stop --force`");
            }
            if (clientVersion.equals(hs.version())) return new EngineReady(hs, false);
            // Version skew → TAKEOVER, not a kill (engine-versioning-plan §2/§3): spawn this
            // client's engine; ITS startup atomically repoints the endpoint and gracefully
            // drains the displaced engine — in-flight jobs on the old one finish untouched.
        }
        return startWithSelfHeal(paths, clientVersion, renderWedge);
    }

    /**
     * Bring up a fresh engine, self-healing the AOT cache and never bricking:
     *
     * <ul>
     *   <li>pick an AOT mode — TRAIN a new cache, USE an existing one, or NONE when AOT can't apply
     *       (non-JAR engine, a GraalVM host JDK, or a sticky {@code .noaot} marker);
     *   <li>a spawn that EXITS before serving means the cache is unusable on this JVM → drop it, mark
     *       the key, and retry once with NONE (a cache-free start always boots);
     *   <li>a USE start that comes up but whose log shows the JVM ignored the cache → delete the
     *       cache so the next start retrains, without disturbing the now-working engine;
     *   <li>a still-booting (alive) child is waited on up to a generous ceiling, so a slow cold start
     *       is never misreported as "could not start".
     * </ul>
     */
    private static EngineReady startWithSelfHeal(EnginePaths.Paths paths, String clientVersion, boolean renderWedge)
            throws IOException {
        EngineTarget target = resolveEngineTarget(paths, clientVersion);
        boolean training = chooseAotMode(target) == AotMode.TRAIN;

        // Animated blue "Engine — Optimizing build engine…" chip, only for a real optimization on an
        // interactive terminal (before any command's own goal console → no interleave).
        java.io.PrintStream ws =
                (renderWedge && training && dev.jkbuild.cli.run.GoalConsole.isInteractiveTerminal())
                        ? dev.jkbuild.cli.CliOutput.stdout()
                        : null;
        dev.jkbuild.cli.tui.ChipSpinner wedge = dev.jkbuild.cli.tui.ChipSpinner.show(
                ws, "Engine", dev.jkbuild.config.GlobalConfig.nerdfont(), "Optimizing build engine...");
        long startNanos = System.nanoTime();
        try {
            if (training) {
                // Eager optimization: record a startup profile and ASSEMBLE the cache now (train →
                // clean-stop → assemble), so the engine we hand back genuinely maps it rather than
                // merely recording (JEP 514 writes the .aot only at the training JVM's clean exit).
                // Best-effort: if it produces no cache we fall through and start normally.
                trainAndAssemble(paths, clientVersion, target);
            }
            Handshake hs = startOnce(paths, clientVersion, target);
            boolean optimized = false;
            // Truthful "optimized": only when the cache now exists on disk and is being mapped.
            if (target.aotCache() != null && Files.exists(target.aotCache())) {
                String took = dev.jkbuild.cli.run.ConsoleSpec.took(
                        java.time.Duration.ofNanos(System.nanoTime() - startNanos));
                wedge.succeed("Build engine optimized and started (pid " + pidStyled(hs.pid()) + ") " + took);
                optimized = wedge.active();
            }
            return new EngineReady(hs, optimized);
        } finally {
            wedge.close();
        }
    }

    /** The engine pid, yellow on an ANSI terminal (matches the wedge's success chip), plain otherwise. */
    private static String pidStyled(long pid) {
        String s = Long.toString(pid);
        return dev.jkbuild.cli.theme.Theme.active().isAnsi()
                ? dev.jkbuild.cli.theme.Theme.colorize(s, dev.jkbuild.cli.theme.Theme.active().warning())
                : s;
    }

    /**
     * Spawn the engine and return once it's serving. The AOT mode is (re)chosen per attempt, so a
     * freshly assembled cache is mapped (USE). Rides out a single start/stop race: with {@code
     * AOTMode=auto} a bad cache can never make the JVM exit, so a child that exits before serving is
     * a crash or an election loss against a just-stopped engine's socket/assembly — back off and
     * retry once, never touching the cache.
     */
    private static Handshake startOnce(EnginePaths.Paths paths, String clientVersion, EngineTarget target)
            throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            AotMode mode = chooseAotMode(target);
            Duration wait = (mode == AotMode.TRAIN) ? TRAINING_START_TIMEOUT : COLD_START_CEILING;
            StartResult r = awaitStartup(paths, clientVersion, wait, spawn(paths, target, mode).process());
            switch (r.outcome()) {
                case UP -> {
                    if (mode == AotMode.USE && scanLogForAotError(paths.log())) {
                        deleteQuietly(target.aotCache());
                        writeNoAotMarker(target.aotCache());
                        logReason(paths, "AOT cache was ignored by the engine JVM; skipping it for this key");
                    }
                    return r.handshake();
                }
                case TIMED_OUT -> throw notStarted(paths); // alive but never served → genuine hang
                case CHILD_EXITED -> {
                    if (attempt == 0) {
                        logReason(paths, "engine exited before serving; retrying after backoff");
                        sleepQuietly(1_500);
                        continue;
                    }
                    throw notStarted(paths);
                }
            }
        }
        throw notStarted(paths); // unreachable
    }

    /**
     * One-time eager AOT training: spawn a training engine ({@code -XX:AOTCacheOutput}), let it run
     * long enough to record the startup path, then clean-stop it so the JVM assembles the {@code
     * .aot} at exit, and wait for that assembly to complete. Best-effort — any failure leaves no
     * cache and the caller starts normally (the cache would then build on a later clean exit).
     */
    private static void trainAndAssemble(EnginePaths.Paths paths, String clientVersion, EngineTarget target) {
        try {
            StartResult tr = awaitStartup(
                    paths, clientVersion, TRAINING_START_TIMEOUT, spawn(paths, target, AotMode.TRAIN).process());
            if (tr.outcome() != StartResult.Outcome.UP) {
                logReason(paths, "AOT training run did not come up; skipping eager optimization");
                return;
            }
            sleepQuietly(TRAINING_MIN_UPTIME_MS); // avoid the assembler's empty-recording rejection
            stop(EnginePaths.activeSocket(paths)); // clean shutdown → the JVM assembles the cache on exit
            if (!awaitAssembled(target.aotCache(), ASSEMBLY_TIMEOUT)) {
                logReason(paths, "AOT cache did not assemble in time; it will build on a later engine exit");
            }
        } catch (IOException e) {
            logReason(paths, "eager AOT training skipped: " + e.getMessage());
        }
    }

    /**
     * Wait until the training child has finished writing the cache: the assembler removes the {@code
     * .aot.config} recording only once the {@code .aot} is complete, so "{@code .aot} present AND
     * {@code .aot.config} gone" is the authoritative done signal (avoids mapping a half-written file).
     */
    private static boolean awaitAssembled(Path aotCache, Duration timeout) {
        if (aotCache == null) return false;
        Path config = aotCache.resolveSibling(aotCache.getFileName() + ".config");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(aotCache) && !Files.exists(config)) return true;
            sleepQuietly(100);
        }
        return Files.exists(aotCache) && !Files.exists(config);
    }

    private static IOException notStarted(EnginePaths.Paths paths) {
        return new IOException("could not start the build engine — see " + paths.log() + " for details");
    }

    /** How a spawn should treat the AOT cache. */
    enum AotMode {
        TRAIN,
        USE,
        NONE
    }

    /** What {@link #spawn} launched: the child (same pid — it setsid()s, never forks). */
    private record Spawned(Process process) {}

    /**
     * The resolved engine to spawn: which artifact, the host JDK (JAR only), whether that JDK is a
     * HotSpot/C2 JVM (AOT is only stable there), the AOT cache path, and whether a {@code .noaot}
     * marker already says AOT can't apply for this key.
     */
    record EngineTarget(
            EngineArtifact engine, Path javaHome, boolean hotspot, Path aotCache, boolean noAotMarker) {}

    /** A host JDK for the engine: home, vendor, and version (from its {@code release} file). */
    record EngineJdk(Path home, dev.jkbuild.jdk.JdkVendor vendor, String version) {}

    /** Resolve everything the spawn/mode decision needs, self-healing a missing/skewed engine jar. */
    private static EngineTarget resolveEngineTarget(EnginePaths.Paths paths, String clientVersion) throws IOException {
        String jkExe = CachePruneScheduler.resolveJkExe()
                .orElseThrow(() -> new IOException("could not resolve the running jk binary's path"));
        EngineArtifact engine = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), jkExe, clientVersion);
        // Self-heal a missing/version-skewed engine jar before falling back: the released native
        // client can't host the engine itself, but it can download the matching jar.
        if (engine.kind() == EngineArtifact.Kind.FALLBACK
                && EngineJarFetcher.applicable(
                        clientVersion, isNativeImage(), dev.jkbuild.config.SessionContext.current().offline())) {
            System.err.println("jk: downloading the build engine (jk-engine-" + clientVersion + ".jar) ...");
            EngineJarFetcher.fetch(EngineJarFetcher.releasesBase(), clientVersion);
            engine = resolveEngineArtifact(System.getenv("JK_ENGINE_EXE"), jkExe, clientVersion);
        }
        if (engine.kind() != EngineArtifact.Kind.JAR) {
            return new EngineTarget(engine, null, false, null, false);
        }
        EngineJdk jdk = resolveEngineJdk();
        Path aot = aotCachePath(paths, Path.of(engine.path()), jdk);
        boolean marker = Files.exists(noAotMarkerPath(aot));
        return new EngineTarget(engine, jdk.home(), isHotSpot(jdk.vendor()), aot, marker);
    }

    /** AOT mode for a target: only a JAR engine on a HotSpot JDK with no {@code .noaot} marker uses AOT. */
    static AotMode chooseAotMode(EngineTarget t) {
        if (t.engine().kind() != EngineArtifact.Kind.JAR) return AotMode.NONE;
        if (!t.hotspot()) return AotMode.NONE; // GraalVM host: its Graal JIT breaks the cache — skip cleanly
        if (t.noAotMarker()) return AotMode.NONE;
        if (t.aotCache() == null || !Files.exists(t.aotCache())) return AotMode.TRAIN;
        return AotMode.USE;
    }

    /**
     * The JDK that hosts the engine JVM, pinned by vendor+major so the AOT cache is stable. Honours
     * {@code [toolchain].jdk} (or {@code JK_ENGINE_JDK}); defaults to the LTS Temurin at the engine's
     * floor release. Prefers an already-installed match (no network), else installs exactly the pin.
     * A HotSpot JDK is what {@code docs/engine.md} wants (HotSpot's JIT + SHA-256 intrinsics) and is
     * required for a mappable AOT cache; a Graal pin is honoured but disables AOT (see {@link
     * #chooseAotMode}).
     */
    private static EngineJdk resolveEngineJdk() throws IOException {
        int floor = Runtime.version().feature();
        String pin = dev.jkbuild.config.GlobalConfig.engineJdkPin().orElse("temurin-" + floor);
        Optional<EngineJdk> installed = findInstalledEngineJdk(pin);
        if (installed.isPresent()) return installed.get();
        System.err.println("jk: installing the build engine's JDK (" + pin + ") ...");
        try {
            Path home = JdkEnsure.install(pin, System.err::println).home();
            return probeEngineJdk(home)
                    .orElseThrow(() -> new IOException("engine JDK installed at " + home + " is unreadable"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted installing the engine JDK " + pin, e);
        }
    }

    /** First already-installed JDK matching the pin's vendor+major, checked without any network. */
    private static Optional<EngineJdk> findInstalledEngineJdk(String pin) {
        Optional<Pin> want = parsePin(pin);
        if (want.isEmpty()) return Optional.empty(); // unparseable pin → force the install path
        List<Path> homes = new ArrayList<>();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        defaults.currentHome().ifPresent(homes::add);
        defaults.defaultHome().ifPresent(homes::add);
        try {
            homes.add(JavaHomes.runningJavaHome());
        } catch (RuntimeException ignored) {
            // No running JVM home (native client) — the registry scan below still covers installs.
        }
        try {
            for (dev.jkbuild.jdk.JdkHit hit : new dev.jkbuild.jdk.JdkRegistry().listHits()) homes.add(hit.home());
        } catch (RuntimeException ignored) {
            // Registry probe failure is non-fatal — fall through to install.
        }
        for (Path home : homes) {
            Optional<EngineJdk> ej = probeEngineJdk(home);
            if (ej.isPresent()
                    && ej.get().vendor() == want.get().vendor()
                    && majorOf(ej.get().version()) == want.get().major()) {
                return ej;
            }
        }
        return Optional.empty();
    }

    private static Optional<EngineJdk> probeEngineJdk(Path home) {
        return dev.jkbuild.discovery.ProbeSupport.discoverJdk(home, "engine-host")
                .map(h -> new EngineJdk(h.home(), h.vendor(), h.version()));
    }

    /** A parsed engine-JDK pin, e.g. {@code "temurin-25"} → (TEMURIN, 25). */
    private record Pin(dev.jkbuild.jdk.JdkVendor vendor, int major) {}

    private static Optional<Pin> parsePin(String spec) {
        int dash = spec.lastIndexOf('-');
        if (dash <= 0 || dash == spec.length() - 1) return Optional.empty();
        int major;
        try {
            major = Integer.parseInt(spec.substring(dash + 1).split("\\.")[0]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        dev.jkbuild.jdk.JdkVendor vendor = vendorFromToken(spec.substring(0, dash));
        return vendor == dev.jkbuild.jdk.JdkVendor.UNKNOWN ? Optional.empty() : Optional.of(new Pin(vendor, major));
    }

    /** Map a spec vendor token (a {@code jbPrefix} like {@code "temurin"}/{@code "graalvm"}) to a vendor. */
    private static dev.jkbuild.jdk.JdkVendor vendorFromToken(String token) {
        for (dev.jkbuild.jdk.JdkVendor v : dev.jkbuild.jdk.JdkVendor.values()) {
            if (v.jbPrefix().map(p -> p.equalsIgnoreCase(token)).orElse(false)) return v;
        }
        return dev.jkbuild.jdk.JdkVendor.UNKNOWN;
    }

    private static int majorOf(String version) {
        int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** HotSpot/C2 JVMs (everything except GraalVM) produce a stable, mappable AOT cache. */
    private static boolean isHotSpot(dev.jkbuild.jdk.JdkVendor vendor) {
        return vendor != dev.jkbuild.jdk.JdkVendor.ORACLE_GRAALVM && vendor != dev.jkbuild.jdk.JdkVendor.GRAALVM_CE;
    }

    private static void killStale(long pid, Duration timeout) {
        ProcessHandle.of(pid).ifPresent(h -> {
            h.destroy();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (h.isAlive() && System.nanoTime() < deadline) {
                sleepQuietly(20);
            }
            if (h.isAlive()) h.destroyForcibly();
        });
    }

    /**
     * Which engine artifact a spawn chose. {@code EXE}: {@code path} is an executable whose {@code
     * main()} IS the engine loop (no {@code --engine-server} flag). {@code JAR}: {@code path} is
     * the engine's fat jar ({@code ~/.jk/versions/<v>/lib/jk-engine.jar}), launched as {@code
     * <managed-jdk>/bin/java … -cp <path> dev.jkbuild.cli.EngineMain} — the engine is a plain JVM
     * app, never a native image. {@code FALLBACK}: {@code path} is the client binary itself,
     * re-invoked with the flag. {@code how} is the one-word provenance for the log header.
     */
    record EngineArtifact(Kind kind, String path, String how) {
        enum Kind {
            EXE,
            JAR,
            FALLBACK
        }
    }

    /**
     * Resolution order for the engine artifact (docs/engine.md lifecycle §2):
     * (a) the {@code JK_ENGINE_EXE} env override — always treated as a dedicated engine
     * executable; (b) the side-by-side layout, {@code ~/.jk/versions/<v>/lib/jk-engine.jar}
     * (engine-versioning-plan R2) — the ONLY installed layout, hosted on the jk-managed JDK;
     * (c) the client binary itself with {@code --engine-server} — the JVM dist (installDist
     * ships no second start script) and dev workflows.
     */
    static EngineArtifact resolveEngineArtifact(String envOverride, String jkExe, String version) {
        return resolveEngineArtifact(envOverride, jkExe, version, dev.jkbuild.cache.VersionStore.current());
    }

    /** Root-injected variant — the testable seam. */
    static EngineArtifact resolveEngineArtifact(
            String envOverride, String jkExe, String version, dev.jkbuild.cache.VersionStore store) {
        if (envOverride != null && !envOverride.isBlank()) {
            return new EngineArtifact(EngineArtifact.Kind.EXE, envOverride, "JK_ENGINE_EXE");
        }
        var materialized = store.resolve(version);
        if (materialized.isPresent()) {
            dev.jkbuild.task.AccessLedger.atDefaultPath()
                    .touch(dev.jkbuild.cache.VersionStore.ledgerKey(version)); // version-GC input
            return new EngineArtifact(
                    EngineArtifact.Kind.JAR, materialized.get().engineJar().toString(), "versions");
        }
        return new EngineArtifact(EngineArtifact.Kind.FALLBACK, jkExe, "fallback");
    }

    /**
     * The engine's AOT cache path, keyed to the engine jar (name:size:mtime) <em>and</em> the host
     * JDK identity (version + vendor). A mismatched cache is silently ignored by {@code
     * AOTMode=auto} and never retrained, so folding the JDK into the key means a jar upgrade, a JDK
     * build bump (Temurin 25.0.3→25.0.4), or a vendor swap all yield a fresh key that trains cleanly.
     * Stale {@code .aot}/{@code .noaot} files from previous keys are deleted best-effort here.
     */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk) {
        return aotCachePath(paths, engineJar, jdk, dev.jkbuild.cli.Jk.VERSION);
    }

    /**
     * As above, version-scoped: derived AOT state lives under {@code state/engine/<v>/}
     * (engine-versioning-plan R3) so side-by-side engines never sweep each other's caches.
     */
    static Path aotCachePath(EnginePaths.Paths paths, Path engineJar, EngineJdk jdk, String version) {
        StringBuilder signature = new StringBuilder();
        try {
            signature
                    .append(engineJar.getFileName())
                    .append(':')
                    .append(Files.size(engineJar))
                    .append(':')
                    .append(Files.getLastModifiedTime(engineJar).toMillis());
        } catch (IOException e) {
            signature.append("unreadable-jar");
        }
        signature.append(':').append(jdk == null ? "no-jdk" : jdk.version() + "|" + jdk.vendor().name());
        String hash = dev.jkbuild.util.Hashing.sha256Hex(signature.toString()).substring(0, 16);
        Path versionDir = paths.dir().resolve(version);
        try {
            Files.createDirectories(versionDir);
        } catch (IOException ignored) {
            // Falls through — a failed mkdir surfaces on the training write, with a real error.
        }
        Path cache = versionDir.resolve("engine-" + hash + ".aot");
        // Sweep every other key's artifacts — the cache, the JEP 514 ".aot.config" recording
        // intermediate (left behind whenever a training run is interrupted before assembly), and any
        // ".noaot" marker — keeping only the current key's files (name prefix "engine-<hash>"). The
        // socket/lock/pid/log live under a different, non-"engine-" prefix, so this never touches them.
        String stem = "engine-" + hash;
        try (var entries = Files.newDirectoryStream(versionDir, "engine-*")) {
            for (Path p : entries) {
                if (!p.getFileName().toString().startsWith(stem)) Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // Cleanup is opportunistic; a leftover cache costs disk, not correctness.
        }
        return cache;
    }

    /** The sibling "this key can't AOT here" marker for an {@code engine-<key>.aot} path. */
    private static Path noAotMarkerPath(Path aotCache) {
        String name = aotCache.getFileName().toString();
        return aotCache.resolveSibling(name.substring(0, name.length() - ".aot".length()) + ".noaot");
    }

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static Spawned spawn(EnginePaths.Paths paths, EngineTarget target, AotMode mode) throws IOException {
        EngineArtifact engine = target.engine();
        JkEngineConfig config = JkEngineConfig.resolve();
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        //
        // Sizing the engine's heap (docs/engine.md "Memory target") happens on the spawn line —
        // the spawner is the only place that can, since a process can't shrink its own -Xmx, and
        // the -Xms pre-sizing matters for a long-lived process (no growth churn). How the numbers
        // ride along differs per artifact form below; user config max-heap-mb stays authoritative
        // everywhere.
        switch (engine.kind()) {
            case JAR -> {
                // The installed engine: a plain JVM app on the jk-managed JDK, one fat jar on the
                // classpath. Tuning is ordinary JVM flags — SerialGC (lowest footprint/latency; a
                // ≤256 MiB heap is well inside its comfort zone) plus the JkEngineConfig heap
                // numbers. The long-lived engine is exactly what HotSpot's JIT and SHA-256
                // intrinsics want; there is no native engine image. --enable-native-access:
                // PosixDetach's setsid(2) FFM downcall without the JDK's restricted-method
                // warning.
                command.add(target.javaHome()
                        .resolve("bin")
                        .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                        .toString());
                command.add("-XX:+UseSerialGC");
                // AOT cache (JEP 514, JDK 25+): pre-parsed class metadata AND AOT-compiled code,
                // taming the cold engine's JIT-warmup tail. The mode is chosen by the self-heal
                // ladder (see startWithSelfHeal): TRAIN records a fresh cache (-XX:AOTCacheOutput,
                // assembled on the engine's clean exit), USE maps an existing one, and NONE omits
                // the cache entirely (non-HotSpot host JDK, or a key that already proved unmappable).
                // The cache is keyed to jar + host-JDK identity so an upgrade/JDK-swap retrains.
                switch (mode) {
                    case TRAIN -> command.add("-XX:AOTCacheOutput=" + target.aotCache());
                    case USE -> command.add("-XX:AOTCache=" + target.aotCache());
                    case NONE -> {
                        /* no AOT flag — a guaranteed cold-but-correct boot */
                    }
                }
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
                command.add("--enable-native-access=ALL-UNNAMED");
                command.add("-cp");
                command.add(engine.path());
                command.add("dev.jkbuild.cli.EngineMain");
            }
            case EXE -> {
                // A dedicated engine executable (JK_ENGINE_EXE): its main() IS the engine loop, no
                // flag. The -Xm* args land as argv; EngineMain ignores argv, so a wrapper that
                // doesn't consume them degrades to an unsized engine, never a dead one.
                command.add(engine.path());
                if (config.heapCapped()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
            }
            case FALLBACK -> {
                // This same binary re-invoked with --engine-server. On the JVM dist the flag finds
                // EngineMain through the InProcessEngine seam; on the slim native client (which
                // links no engine code) the child reports plainly that the engine jar is missing
                // and exits — the ensure-running timeout then surfaces that log line.
                command.add(engine.path());
                command.add("--engine-server");
                if (config.heapCapped() && isNativeImage()) {
                    command.add("-Xms" + config.minHeapMb() + "m");
                    command.add("-Xmx" + config.maxHeapMb() + "m");
                }
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // Anchor the detached daemon's working directory to its own state dir (created just above),
        // never the spawning client's CWD. A resident engine outlives the shell that started it, and
        // if it inherited an ephemeral CWD (a /tmp scratch dir, a git worktree, a since-deleted
        // checkout) every subprocess it later forks — javac, workers — inherits that dead CWD and
        // dies at JVM init with "Could not determine current working directory". The state dir is
        // stable for the engine's whole life and is never removed by cache maintenance.
        pb.directory(paths.dir().toFile());
        // On the JVM dist the sizing knob is the start script's JVM-options env var — appended
        // last so it wins over any blanket JK_OPTS the user exported. SerialGC for the same
        // reasons as the JAR spawn line.
        if (config.heapCapped() && engine.kind() == EngineArtifact.Kind.FALLBACK && !isNativeImage()) {
            String opts = System.getenv("JK_OPTS");
            String sizing = "-XX:+UseSerialGC -Xms" + config.minHeapMb() + "m -Xmx" + config.maxHeapMb() + "m";
            pb.environment().put("JK_OPTS", (opts == null || opts.isBlank() ? "" : opts + " ") + sizing);
        }
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/engine.md. The spawner writes the log's first line itself
        // (which artifact it chose — the one fact the engine can't know), then the child appends;
        // if that header can't be written, fall back to plain truncate-and-redirect.
        pb.redirectErrorStream(true);
        pb.redirectOutput(
                writeSpawnHeader(paths.log(), engine)
                        ? ProcessBuilder.Redirect.appendTo(paths.log().toFile())
                        : ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
        return new Spawned(p);
    }

    /**
     * Start the fresh log with the spawn decision, truncating whatever {@link #rotateLog} left
     * behind (it's best-effort). {@code false} — and no header — if the file isn't writable; the
     * caller then falls back to the truncating redirect so log semantics stay identical.
     */
    private static boolean writeSpawnHeader(Path log, EngineArtifact engine) {
        try {
            Files.writeString(
                    log,
                    "jk engine: spawning " + engine.path() + " (" + engine.how() + ")" + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** True when this client runs as a GraalVM native image (so the spawned engine will too). */
    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    /**
     * Keep exactly one historical log ({@code <key>.log} → {@code <key>.log.1}) before each fresh
     * engine start truncates {@code <key>.log}. Without this, a crash followed by the next lazy
     * respawn (which happens automatically, often before anyone looks) would silently destroy the
     * crashed engine's own log — the one file {@link #ensureRunning}'s error message and {@code jk
     * engine status} both point at for post-mortem. Best-effort: a failure here (e.g. permissions)
     * never blocks starting the engine.
     */
    private static void rotateLog(Path log) {
        if (!Files.exists(log)) return;
        try {
            Files.move(
                    log,
                    log.resolveSibling(log.getFileName() + ".1"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best-effort — the next start still truncates/overwrites `log` either way.
        }
    }

    /** Outcome of waiting for a freshly spawned engine — lets the ladder tell a crash from a slow boot. */
    private record StartResult(Outcome outcome, Handshake handshake) {
        enum Outcome {
            UP,
            CHILD_EXITED,
            TIMED_OUT
        }

        static StartResult up(Handshake h) {
            return new StartResult(Outcome.UP, h);
        }

        static StartResult exited() {
            return new StartResult(Outcome.CHILD_EXITED, null);
        }

        static StartResult timedOut() {
            return new StartResult(Outcome.TIMED_OUT, null);
        }
    }

    private static StartResult awaitStartup(
            EnginePaths.Paths paths, String clientVersion, Duration timeout, Process spawned) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Handshake> h = handshake(EnginePaths.activeSocket(paths), clientVersion);
            if (h.isPresent()) return StartResult.up(h.get());
            if (spawned != null && !spawned.isAlive()) {
                // The child died (setsid keeps the pid, so liveness is authoritative). One last
                // handshake: a concurrent spawn may have won the election and be serving already —
                // our child exiting is then the healthy loser, not a failure.
                return handshake(EnginePaths.activeSocket(paths), clientVersion).map(StartResult::up).orElseGet(StartResult::exited);
            }
            sleepQuietly(50);
        }
        return StartResult.timedOut();
    }

    /**
     * Did the JVM ignore the AOT cache on this start? {@code AOTMode=auto} logs and boots cold on a
     * mismatch instead of failing — scan the fresh per-start log for those markers so the caller can
     * drop the cache and retrain next time. Best-effort and bounded (AOT diagnostics appear at boot).
     */
    static boolean scanLogForAotError(Path log) {
        if (log == null) return false;
        try {
            if (!Files.exists(log)) return false;
            String head = Files.readString(log);
            if (head.length() > 8192) head = head.substring(0, 8192);
            return head.contains("[error][aot]")
                    || head.contains("Mismatched values for property")
                    || head.contains("Disabling optimized module handling");
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** Remember that AOT can't apply for this cache's key, so later starts skip straight to NONE. */
    private static void writeNoAotMarker(Path aotCache) {
        if (aotCache == null) return;
        try {
            Files.writeString(noAotMarkerPath(aotCache), "");
        } catch (IOException ignored) {
            // best-effort — worst case we retry AOT more often, never a failure
        }
    }

    /** Append a diagnostic to the engine log only — never the user's terminal. */
    private static void logReason(EnginePaths.Paths paths, String message) {
        try {
            Files.writeString(
                    paths.log(),
                    "jk engine: " + message + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Package-visible: {@link EngineBuildListenerAdapter} opens its own long-lived connection. On
     * the loopback-TCP transport (Windows — see {@link dev.jkbuild.engine.EngineTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also sends the required
     * {@link EngineProtocol#AUTH} line before returning, so every caller authenticates transparently
     * without needing its own knowledge of the transport.
     */
    /**
     * The client-side protocol reader: line-capped, and idle-timed so a dead engine surfaces as
     * an error instead of a forever-blocked {@code readLine()}. Default 60 minutes between
     * events; {@code JK_STREAM_IDLE_MINUTES} tunes it (0 disables).
     */
    static BufferedReader protocolReader(SocketChannel ch) {
        long minutes = 60;
        String env = System.getenv("JK_STREAM_IDLE_MINUTES");
        if (env != null && !env.isBlank()) {
            try {
                minutes = Long.parseLong(env.trim());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return new dev.jkbuild.plugin.protocol.BoundedLineReader(
                new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8),
                ch,
                minutes * 60_000L);
    }

    static SocketChannel connect(Path socket) throws IOException {
        if (dev.jkbuild.engine.EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            String token =
                    Files.readString(dev.jkbuild.engine.EnginePaths.tokenFor(socket)).trim();
            SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            BufferedWriter authWriter =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            authWriter.write(EngineProtocol.auth(token));
            authWriter.write('\n');
            authWriter.flush();
            return ch;
        }
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        ch.connect(UnixDomainSocketAddress.of(socket));
        return ch;
    }

    /**
     * Send one line, read one reply line, over an already-connected channel. {@link SocketChannel}
     * (a Unix-domain channel doesn't support the legacy {@code .socket()}/{@code setSoTimeout}
     * adapter) has no built-in read timeout, so a watchdog thread closes the channel if the engine
     * doesn't reply in time — an interruptible-channel read blocked on a closed channel throws
     * promptly, which this turns into a clear timeout error rather than hanging the CLI forever.
     */
    private static String exchange(SocketChannel ch, String line) throws IOException {
        BufferedWriter writer =
                new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
        writer.write(line);
        writer.write('\n');
        writer.flush();
        BufferedReader reader =
                protocolReader(ch);
        Thread watchdog = new Thread(
                () -> {
                    try {
                        Thread.sleep(SOCKET_TIMEOUT_MILLIS);
                        ch.close();
                    } catch (InterruptedException ignored) {
                        // exchange() finished in time — nothing to do
                    } catch (IOException ignored) {
                        // already closing
                    }
                },
                "jk-engine-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String reply = reader.readLine();
            if (reply == null) throw new IOException("engine closed the connection without replying");
            return reply;
        } catch (java.nio.channels.AsynchronousCloseException e) {
            throw new IOException("engine did not reply within " + SOCKET_TIMEOUT_MILLIS + "ms", e);
        } finally {
            watchdog.interrupt();
        }
    }
}
