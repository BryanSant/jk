// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.config.JkHistoryConfig;
import dev.jkbuild.config.JkHttpConfig;
import dev.jkbuild.config.Session;
import dev.jkbuild.config.SessionContext;
import dev.jkbuild.engine.http.HttpEngineServer;
import dev.jkbuild.engine.http.JsonOut;
import dev.jkbuild.engine.journal.BuildJournal;
import dev.jkbuild.engine.journal.BuildRecord;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.TestSummary;
import dev.jkbuild.runtime.BuildPlan;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.runtime.ExplainPlan;
import dev.jkbuild.runtime.ModuleOutcome;
import dev.jkbuild.runtime.ModulePlan;
import dev.jkbuild.runtime.WorkspaceBuildListener;
import dev.jkbuild.runtime.WorkspaceRequest;
import dev.jkbuild.runtime.WorkspaceResult;
import dev.jkbuild.worker.HeapPlan;
import dev.jkbuild.worker.JvmOptions;
import dev.jkbuild.worker.MemoryProbe;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The engine's server loop: single-instance election, socket bind, an accept loop that serves the
 * handshake/liveness/status/shutdown protocol on a virtual thread per connection, and the
 * idle-timeout policy. See {@code docs/engine.md}.
 *
 * <p>Hosts the real engine operations: workspace builds ({@code buildWorkspace} dispatch), single
 * project builds and tests, and explain forecasts — each request served on its own connection in
 * its own {@link Session}, with goal events streamed back over the wire protocol.
 */
public final class EngineServer implements AutoCloseable {

    /** How often the idle-timeout check runs, in real (non-test) operation. */
    private static final long DEFAULT_TICK_MILLIS = 30_000;

    private final EnginePaths.Paths paths;
    private final JkEngineConfig config;

    /**
     * The {@code [http]} table when present, else {@code null} — the embedded HTTP server's enable
     * switch. Enabled also means this engine never self-terminates (see {@code docs/http.md} and
     * {@link #startIdleTickerIfNeeded}): the dashboard it serves must not vanish out from under an
     * open browser tab.
     */
    private final JkHttpConfig httpConfig;

    private final String version;
    private final Consumer<String> log;
    private final long tickMillis;
    private final LongSupplier clockMillis;
    private final long pid;
    private final long startedAtMillis;

    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicInteger activePipelines = new AtomicInteger();

    /**
     * Dashboard event plumbing ({@code docs/http.md}) — non-null exactly when {@link #httpConfig}
     * is. Every hosted request publishes coarse lifecycle events through {@link #publishEvent};
     * with no SSE subscriber connected that's one {@code hasSubscribers} check and nothing else.
     */
    private final dev.jkbuild.engine.http.HttpEvents httpEvents;

    /** Ids for {@code request-start}/{@code request-finish} events and {@code POST /api/build} acks. */
    private final java.util.concurrent.atomic.AtomicLong requestIds = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Build-history capture: per-request outcome accumulators keyed by the event-request id, folded
     * from the same listener callbacks that publish dashboard events but written unconditionally (a
     * build is journaled whether or not a dashboard is watching). Persisted at request-finish to
     * {@link #journal}; retention is enforced at the idle boundary per {@link #historyConfig}.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, BuildAccumulator> accumulators =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final JkHistoryConfig historyConfig = JkHistoryConfig.resolve();

    private final BuildJournal journal = BuildJournal.current();

    /**
     * The event-request id of the hosted operation this thread is running, set around {@code
     * runner.run} by {@link #handleAsyncGoalRequest} — how {@link #wireListener}/{@link
     * #wireGoalListener} tag their module/goal events with the right request without threading an
     * id through every runner signature. Captured at listener <em>creation</em> (which happens on
     * the runner's thread); callbacks may later fire on any worker thread.
     */
    private final ThreadLocal<Long> currentEventRequestId = new ThreadLocal<>();

    /**
     * Serializes cache mutation against cache consumption (Wave 4): every pipeline holds the read
     * side for its whole run; a cache maintenance job (prune/purge/gc — see {@link
     * #runCacheMaintenance} and the idle-boundary drain in {@link #maybeIdleBoundaryGc}) holds the
     * write side, so a sweep can never delete blobs from under an in-flight pipeline in this engine
     * — the Wave-3 correctness finding. Fair, so a waiting prune isn't starved by a stream of new
     * builds (and vice versa). Cross-<em>process</em> safety (two engines with different state dirs
     * can share one cache dir — {@code JK_CACHE_DIR} is independent of {@code JK_STATE_DIR}) still
     * rides the on-disk {@code .prune.lock}, which maintenance additionally takes.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock cacheGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock(true);

    /**
     * The cache root a deferred opportunistic prune should run against, or {@code null} when none
     * is queued. Set by a successful build/sync when the auto-prune cadence is due (the enqueue that
     * replaced the detached {@code jk cache prune --background} spawn); drained at the idle boundary.
     */
    private final java.util.concurrent.atomic.AtomicReference<Path> pendingPruneCache =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean shuttingDown;
    private volatile boolean hadActivity;
    private volatile long lastActivityAtMillis;

    private FileChannel lockChannel;
    private FileLock lock;
    private ServerSocketChannel serverChannel;
    private ExecutorService connectionExecutor;
    private ScheduledExecutorService idleTicker;

    /** Non-null once the embedded HTTP server is up; stays null when disabled or bind failed. */
    private HttpEngineServer httpServer;

    /** Non-null when {@code [http]} is enabled but the server failed to start — surfaced in status. */
    private volatile String httpError;

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link EngineTransport}. */
    private String expectedToken;

    public EngineServer(EnginePaths.Paths paths, JkEngineConfig config, String version, Consumer<String> log) {
        this(paths, config, null, version, log, DEFAULT_TICK_MILLIS, System::currentTimeMillis);
    }

    /** As above plus the optional {@code [http]} table ({@code null} = feature off). */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            Consumer<String> log) {
        this(paths, config, httpConfig, version, log, DEFAULT_TICK_MILLIS, System::currentTimeMillis);
    }

    /** Test seam: a short tick interval and an injectable clock, so idle-timeout tests don't sleep for real minutes. */
    EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            String version,
            Consumer<String> log,
            long tickMillis,
            LongSupplier clockMillis) {
        this(paths, config, null, version, log, tickMillis, clockMillis);
    }

    /** The full test seam, plus the optional {@code [http]} table ({@code null} = feature off). */
    EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            Consumer<String> log,
            long tickMillis,
            LongSupplier clockMillis) {
        this.paths = paths;
        this.config = config;
        this.httpConfig = httpConfig;
        this.httpEvents = httpConfig != null ? new dev.jkbuild.engine.http.HttpEvents() : null;
        this.version = version;
        this.log = log != null ? log : s -> {};
        this.tickMillis = tickMillis;
        this.clockMillis = clockMillis;
        this.pid = ProcessHandle.current().pid();
        this.startedAtMillis = clockMillis.getAsLong();
    }

    /**
     * Try to become the engine and serve until shutdown. Returns {@code false} immediately, having
     * touched nothing but the lock file, if another engine already holds {@link
     * EnginePaths.Paths#lock()} — the caller (a losing spawn-race participant) should treat that as
     * success-by-proxy, not an error. Blocks until the server stops (idle timeout, an explicit {@link
     * EngineProtocol#SHUTDOWN}, or {@link #close()}), then returns {@code true}.
     */
    public boolean run() throws IOException {
        Files.createDirectories(paths.dir());
        lockChannel = FileChannel.open(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return false; // another engine already won the election
        }

        // A stale socket file (left by a killed process) makes bind() fail with "address already in
        // use" even though nothing is listening — safe to remove now: winning the lock proves no live
        // engine owns it.
        Files.deleteIfExists(paths.socket());
        Files.deleteIfExists(paths.token());

        if (EngineTransport.useLoopbackTcp()) {
            // Windows: no dependable Unix-domain-socket support — bind an ephemeral loopback TCP
            // port instead, and gate every connection on a shared secret (see EngineTransport),
            // since a TCP port (unlike a socket file) isn't filesystem-permission-gated by default.
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int port = ((java.net.InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            expectedToken = EngineTransport.newToken();
            Files.writeString(paths.token(), expectedToken);
            Files.writeString(paths.socket(), Integer.toString(port));
        } else {
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(paths.socket()));
        }
        writePidFile();

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-conn-", 0).factory());
        lastActivityAtMillis = clockMillis.getAsLong();
        startIdleTickerIfNeeded();
        startHttpIfEnabled();
        planSharedWorkerMemoryOnce();

        log.accept("jk engine: listening on " + paths.socket() + " (pid " + pid + ")");
        acceptLoop();
        cleanup();
        log.accept("jk engine: stopped");
        return true;
    }

    private void acceptLoop() {
        while (!shuttingDown) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (ClosedChannelException e) {
                break; // close() / idle-timeout / shutdown message closed the listener
            } catch (IOException e) {
                if (shuttingDown) break;
                log.accept("jk engine: accept failed: " + e.getMessage());
                continue;
            }
            synchronized (lifecycleLock) {
                if (shuttingDown) {
                    closeQuietly(ch);
                    continue;
                }
                activeConnections.incrementAndGet();
                hadActivity = true;
            }
            connectionExecutor.execute(() -> handleConnection(ch));
        }
    }

    /** Loopback-TCP transport only: the connection's first line must be a matching {@link EngineProtocol#AUTH}. */
    private boolean authenticate(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line != null
                && EngineProtocol.AUTH.equals(EngineProtocol.typeOf(line))
                && expectedToken.equals(Ndjson.str(line, "token"));
    }

    private void handleConnection(SocketChannel ch) {
        try (ch;
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter writer =
                        new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            if (expectedToken != null && !authenticate(reader)) {
                return; // loopback-TCP transport only: wrong/missing token — close without a reply
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) continue; // ignore malformed/blank lines
                switch (type) {
                    case EngineProtocol.HELLO -> send(writer, EngineProtocol.helloAck(version, pid, startedAtMillis));
                    case EngineProtocol.PING -> send(writer, EngineProtocol.pong());
                    case EngineProtocol.STATUS -> {
                        dev.jkbuild.engine.http.StatusSnapshot s = statusSnapshot();
                        send(
                                writer,
                                EngineProtocol.statusAck(
                                        s.version(),
                                        s.pid(),
                                        s.startedAtMillis(),
                                        s.idleMinutes(),
                                        s.activeRequests(),
                                        s.heapUsedBytes(),
                                        s.heapCommittedBytes(),
                                        s.heapMaxBytes(),
                                        s.rssBytes(),
                                        httpServer != null ? httpServer.url() : null,
                                        httpError));
                    }
                    case EngineProtocol.SHUTDOWN -> {
                        send(writer, EngineProtocol.bye());
                        synchronized (lifecycleLock) {
                            shuttingDown = true;
                            closeServerChannelQuietly();
                        }
                        return;
                    }
                    case EngineProtocol.HISTORY_LIST_REQUEST -> handleHistoryList(line, writer);
                    case EngineProtocol.HISTORY_SHOW_REQUEST -> handleHistoryShow(line, writer);
                    case EngineProtocol.HISTORY_DELETE_REQUEST -> handleHistoryDelete(line, writer);
                    case EngineProtocol.BUILD_REQUEST -> {
                        // Owns the rest of this connection's lifecycle: forks the build onto its own
                        // thread and keeps reading this loop for a build-cancel/EOF while it runs.
                        handleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.TEST_REQUEST -> {
                        // Same shape as BUILD_REQUEST but for a single project's test goal (Phase 3).
                        handleTestRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.SINGLE_BUILD_REQUEST -> {
                        // Same shape as TEST_REQUEST but a real (non-testOnly) build goal.
                        handleSingleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.LOCK_REQUEST -> {
                        // Same fork-and-watch shape as BUILD_REQUEST, hosting jk lock's cascade.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-lock-", this::runLock);
                        return;
                    }
                    case EngineProtocol.UPDATE_REQUEST -> {
                        // jk update rides jk lock's event vocabulary (plus the --git splice mode).
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-update-", this::runUpdate);
                        return;
                    }
                    case EngineProtocol.SYNC_REQUEST -> {
                        // jk sync is a single goal — TEST_REQUEST's wire shape.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-sync-", this::runSync);
                        return;
                    }
                    case EngineProtocol.AUDIT_REQUEST -> {
                        // Wave 2 (hosted worker verbs): single goal, worker forked engine-side.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-audit-", this::runAudit);
                        return;
                    }
                    case EngineProtocol.FORMAT_REQUEST -> {
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-format-", this::runFormat);
                        return;
                    }
                    case EngineProtocol.PUBLISH_REQUEST -> {
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-publish-", this::runPublish);
                        return;
                    }
                    case EngineProtocol.IMAGE_REQUEST -> {
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-image-", this::runImage);
                        return;
                    }
                    case EngineProtocol.IMPORT_REQUEST -> {
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-import-", this::runImport);
                        return;
                    }
                    case EngineProtocol.PROVISION_REQUEST -> {
                        // One-shot (no goal events), but the worker may download a whole Maven/Gradle
                        // distribution — same fork-and-watch shape so an EOF still cancels.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-provision-", this::runProvision);
                        return;
                    }
                    case EngineProtocol.COMPILE_REQUEST -> {
                        // Wave 3 (hosted pipeline verbs): jk compile is a single goal — TEST_REQUEST's shape.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-compile-", this::runCompile);
                        return;
                    }
                    case EngineProtocol.NATIVE_REQUEST -> {
                        // jk native's serial module cascade, speaking BUILD_REQUEST's workspace vocabulary.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-native-", this::runNative);
                        return;
                    }
                    case EngineProtocol.INSTALL_REQUEST -> {
                        // jk install's build + cache-install halves; make-install stays client-side.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-install-", this::runInstall);
                        return;
                    }
                    case EngineProtocol.GIT_FETCH_REQUEST -> {
                        // jk install <git-url>'s clone half (the git-client worker forks engine-side).
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-gitfetch-", this::runGitFetch);
                        return;
                    }
                    case EngineProtocol.SCRIPT_PREPARE_REQUEST -> {
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-script-", this::runScriptPrepare);
                        return;
                    }
                    case EngineProtocol.TOOL_RESOLVE_REQUEST -> {
                        // Wave 4 (hosted long-tail verbs): jk tool install/run's Maven resolve+fetch.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-tool-", this::runToolResolve);
                        return;
                    }
                    case EngineProtocol.CACHE_PRUNE_REQUEST -> {
                        // Cache maintenance is an idle-boundary job, not a pipeline: it waits for
                        // activePipelines to drain (and blocks new ones) instead of joining them.
                        handleAsyncGoalRequest(line, reader, writer, "jk-engine-cache-", this::runCacheMaintenance, false);
                        return;
                    }
                    case EngineProtocol.EXPLAIN_REQUEST -> {
                        // Synchronous read, no worker JVM forked — handled inline, connection continues.
                        handleExplainRequest(line, writer);
                    }
                    case EngineProtocol.FORECAST_REQUEST -> {
                        // Synchronous read-only pre-flight (jk build's fully-cached shortcut) —
                        // handled inline, connection continues.
                        handleForecastRequest(line, writer);
                    }
                    case EngineProtocol.PROJECT_INFO_REQUEST -> handleProjectInfoRequest(line, writer);
                    case EngineProtocol.EXEC_PLAN_REQUEST -> handleExecPlanRequest(line, writer);
                    case EngineProtocol.EDIT_REQUEST -> handleEditRequest(line, writer);
                    case EngineProtocol.DENY_CHECK_REQUEST -> handleDenyCheckRequest(line, writer);
                    case EngineProtocol.TREE_REQUEST -> handleTreeRequest(line, writer);
                    case EngineProtocol.WHY_REQUEST -> handleWhyRequest(line, writer);
                    default -> {
                        /* unknown type — forward-compatible no-op */
                    }
                }
            }
        } catch (IOException ignored) {
            // client disconnected / socket error mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    /**
     * Run a workspace build on its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and stream every {@link
     * WorkspaceBuildListener}/{@link GoalListener} callback back as a wire event. Returns once the
     * build finishes and its terminal message has been sent, or the connection drops.
     */
    private void handleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-engine-build-", this::runBuild);
    }

    /** An engine-hosted operation's body: decode the request, run it, stream events to {@code writer}. */
    @FunctionalInterface
    private interface GoalRunner {
        void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer);
    }

    /**
     * Fork {@code runner} onto its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and wait for it to finish. Shared by every
     * request type that owns the rest of its connection's lifecycle ({@link #handleBuildRequest},
     * {@link #handleTestRequest}, {@link #handleSingleBuildRequest}) — they differ only in what
     * {@code runner} actually builds and runs.
     */
    private void handleAsyncGoalRequest(
            String requestLine, BufferedReader reader, BufferedWriter writer, String threadPrefix, GoalRunner runner) {
        handleAsyncGoalRequest(requestLine, reader, writer, threadPrefix, runner, true);
    }

    /**
     * As above; {@code pipeline=false} for a cache maintenance job, which is deliberately <em>not</em>
     * a pipeline: it doesn't join {@link #activePipelines} or hold {@link #cacheGate}'s read side —
     * its runner takes the write side itself (see {@link #runCacheMaintenance}).
     */
    private void handleAsyncGoalRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            GoalRunner runner,
            boolean pipeline) {
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        long eventRequestId = requestIds.incrementAndGet();
        // A single-project build ("1build" thread prefix) is still a "build" to the dashboard — the
        // internal distinction (workspace scheduler vs one goal) is not a user-facing kind. Normalize
        // once here so the request-start/finish events, the journal, and the rebuild-button gate all
        // agree on "build".
        String eventKind = requestKind(threadPrefix);
        if (eventKind.equals("1build")) eventKind = "build";
        String eventDir = String.valueOf(Ndjson.str(requestLine, "entryDir"));
        long eventStartMillis = clockMillis.getAsLong();
        publishRequestStart(eventRequestId, eventKind, eventDir);
        registerAccumulator(eventRequestId, eventKind, eventDir);
        if (pipeline) activePipelines.incrementAndGet();
        try {
            Thread.ofVirtual().name(threadPrefix, 0).start(() -> {
                if (pipeline) cacheGate.readLock().lock();
                currentEventRequestId.set(eventRequestId);
                try {
                    runner.run(requestLine, cancelToken, writer);
                } finally {
                    currentEventRequestId.remove();
                    if (pipeline) cacheGate.readLock().unlock();
                    done.countDown();
                }
            });
            try {
                String line;
                while (done.getCount() > 0 && (line = reader.readLine()) != null) {
                    if (EngineProtocol.BUILD_CANCEL.equals(EngineProtocol.typeOf(line))) {
                        cancelToken.cancel();
                    }
                }
                if (done.getCount() > 0) {
                    cancelToken.cancel(); // EOF: the client disconnected — best-effort cancel
                }
            } catch (IOException ignored) {
                cancelToken.cancel();
            }
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (pipeline) maybeIdleBoundaryGc();
            long elapsedMillis = clockMillis.getAsLong() - eventStartMillis;
            // cancelToken.cancelled() also trips on the benign end-of-request EOF, so a successful
            // build can look cancelled. Correct it once here for both the dashboard event and the
            // journal (a build that succeeded was not cancelled).
            boolean cancelled = effectiveCancelled(eventRequestId, cancelToken.cancelled());
            publishEvent(
                    "request-finish",
                    dev.jkbuild.engine.http.JsonOut.object()
                            .put("requestId", eventRequestId)
                            .put("kind", eventKind)
                            .put("dir", eventDir)
                            .put("cancelled", cancelled)
                            .put("millis", elapsedMillis));
            writeJournal(eventRequestId, cancelled, elapsedMillis);
        }
    }

    /**
     * Whether the build was genuinely cancelled. {@code cancelToken.cancelled()} is unreliable — it
     * also trips on the benign end-of-request EOF (the client closing the socket the instant it reads
     * the terminal message), which would mislabel a plain success or a real test failure as
     * "cancelled". For a build we trust the runner's own {@link GoalResult#userCancelled()} (captured
     * in the accumulator); only non-build requests (no accumulator) fall back to the raw token.
     */
    private boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        BuildAccumulator a = accumulators.get(requestId);
        return a != null ? a.wasCancelled() : rawCancelled;
    }

    /** {@code "jk-engine-build-"} → {@code "build"} — the event vocabulary's request kind. */
    private static String requestKind(String threadPrefix) {
        String kind = threadPrefix.startsWith("jk-engine-") ? threadPrefix.substring("jk-engine-".length()) : threadPrefix;
        return kind.endsWith("-") ? kind.substring(0, kind.length() - 1) : kind;
    }

    /** Publish to the dashboard event hub — free (one subscriber check) when no dashboard is open. */
    private void publishEvent(String type, dev.jkbuild.engine.http.JsonOut payload) {
        if (httpEvents != null && httpEvents.hasSubscribers()) httpEvents.publish(type, payload);
    }

    /**
     * {@code request-start}, enriched with the project's {@code group:name} coordinate when the
     * dir's {@code jk.toml} parses — the dashboard renders coordinates, not paths, when it can
     * (the design's coord coloring). Best-effort and only attempted with a subscriber connected.
     */
    private void publishRequestStart(long requestId, String kind, String dir) {
        if (!eventsWanted()) return;
        String coord = null;
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve("jk.toml")).project();
            coord = project.group() + ":" + project.name();
        } catch (Exception e) {
            // unparseable/missing jk.toml — the dashboard falls back to showing the dir
        }
        publishEvent(
                "request-start",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("kind", kind)
                        .put("dir", dir)
                        .put("coord", coord));
    }

    private void publishPhaseStart(long requestId, String dir, String phase) {
        if (!eventsWanted()) return;
        publishEvent(
                "phase-start",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("phase", phase));
    }

    private void publishPhaseFinish(long requestId, String dir, String phase, String status) {
        if (!eventsWanted()) return;
        publishEvent(
                "phase-finish",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("phase", phase)
                        .put("status", status));
    }

    private void publishOutput(long requestId, String dir, String phase, String line) {
        if (!eventsWanted()) return;
        publishEvent(
                "output",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("phase", phase)
                        .put("line", line));
    }

    /** Failure detail is bounded on the wire: a compile explosion must not flood the event stream. */
    private static final int MAX_DIAGNOSTIC_EVENTS = 8;

    /**
     * The failure context behind a failed card ({@code docs/webclient.md}): the same structured
     * {@link GoalResult.Diagnostic}s the CLI renders — test failures carry test/exceptionClass,
     * and lock/sync/parse/worker failures arrive as plain phase diagnostics through the same seam.
     */
    private void publishDiagnostics(long requestId, String dir, java.util.List<GoalResult.Diagnostic> errors) {
        if (!eventsWanted() || errors.isEmpty()) return;
        int shown = Math.min(errors.size(), MAX_DIAGNOSTIC_EVENTS);
        for (int i = 0; i < shown; i++) {
            GoalResult.Diagnostic d = errors.get(i);
            publishEvent(
                    "diagnostic",
                    dev.jkbuild.engine.http.JsonOut.object()
                            .put("requestId", requestId)
                            .put("dir", dir)
                            .put("phase", d.phase())
                            .put("code", d.code())
                            .put("message", d.message())
                            .put("test", d.test())
                            .put("exceptionClass", d.exceptionClass()));
        }
        if (errors.size() > shown) {
            publishRequestError(requestId, dir, "+ " + (errors.size() - shown) + " more errors — see the CLI output");
        }
    }

    /** A single request-level failure line (bad jk.toml, workspace orchestration error, …). */
    private void publishRequestError(long requestId, String dir, String message) {
        if (!eventsWanted() || message == null || message.isBlank()) return;
        publishEvent(
                "diagnostic",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("phase", "request")
                        .put("code", "error")
                        .put("message", message)
                        .put("test", "")
                        .put("exceptionClass", ""));
    }

    /**
     * The build's total weight (Σ module weights) — seeds the dashboard bar's denominator up front so
     * the aggregate never jumps backward as later modules start. Mirrors what {@code onPlan} already
     * sends the CLI as per-module {@code plan-module} weights. Weight is the same abstract unit the
     * CLI bar uses ({@code EffortWeights}, ≈150 ms/unit); the dashboard only needs the ratio.
     */
    private void publishPlan(long requestId, long totalWeight) {
        if (!eventsWanted()) return;
        publishEvent(
                "plan",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("weight", totalWeight));
    }

    /**
     * Weight-based progress for one module goal — the identical {@code numerator}/{@code denominator}
     * the CLI progress bar renders (from {@link GoalView}). The dashboard sums these across modules
     * for the request-level bar. Emitted on goal-start / progress / scope-update, exactly where the
     * socket path sends {@code EngineProtocol.goalStart/progress/scopeUpdate}.
     */
    private void publishGoalProgress(long requestId, String dir, GoalView view) {
        if (!eventsWanted()) return;
        publishEvent(
                "goal-progress",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("numerator", view.numerator())
                        .put("denominator", view.denominator()));
    }

    /**
     * The calibrated ETA in millis — the same value {@code jk build}'s countdown and {@code jk
     * explain}'s estimate show ({@code BuildService.seedEta} + live re-projections). Emitted for the
     * seed and every re-projection, exactly where the socket path sends {@code EngineProtocol.eta}.
     */
    private void publishEta(long requestId, long millis) {
        if (!eventsWanted()) return;
        publishEvent(
                "eta",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("millis", millis));
    }

    /**
     * Guard for event publishers: build the payload only when someone is listening. Split from
     * {@link #publishEvent} so hot listener callbacks (per-goal, per-module) pay one boolean check,
     * not a {@code JsonOut} allocation, when no dashboard is open.
     */
    private boolean eventsWanted() {
        return httpEvents != null && httpEvents.hasSubscribers();
    }

    /**
     * After the last in-flight pipeline finishes (its terminal message already sent), run one full
     * collection. SerialGC honors {@code System.gc()} with a compacting full GC that shrinks the
     * committed heap back toward {@code -Xms}, so the resident engine's footprint between builds
     * matches its idle target rather than its last build's peak — and the pause lands on an ideal
     * boundary: no pipeline is running and no client is waiting on anything. The same boundary
     * drains any queued opportunistic prune (Wave 4) — cache mutation happens exactly where nothing
     * is reading the cache.
     */
    private void maybeIdleBoundaryGc() {
        if (activePipelines.decrementAndGet() == 0) {
            System.gc();
            drainPendingPrune();
            pruneJournal();
        }
    }

    /**
     * Enforce build-history retention at the idle boundary, off the hot path: drop entries past the
     * configured age, then oldest-first past the disk budget (reclaiming the copied snapshots). The
     * journal is its own dir tree — no {@link #cacheGate} needed. Best-effort; a failure is logged.
     */
    private void pruneJournal() {
        if (!historyConfig.enabled()) return;
        try {
            long now = clockMillis.getAsLong();
            BuildJournal.PruneResult r = journal.prune(historyConfig.maxAgeMillis(), historyConfig.maxDiskBytes(), now);
            if (r.removedEntries() > 0) {
                log.accept("jk engine: build journal prune removed " + r.removedEntries()
                        + " entries (" + r.removedBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal prune failed: " + e.getMessage());
        }
    }

    /**
     * Queue an opportunistic prune of {@code cache} for the next idle boundary if the auto-prune
     * cadence is due — the engine-internal replacement for the detached {@code jk cache prune
     * --background} self-spawn (the engine is the process that did the work, and the idle boundary
     * is the only safe time to mutate the caches it serves).
     */
    private void maybeEnqueuePrune(Path cache) {
        try {
            var config = dev.jkbuild.config.JkCacheConfig.resolve();
            if (config.autoPrune() && dev.jkbuild.task.CachePruneScheduler.shouldRun(config, cache)) {
                pendingPruneCache.compareAndSet(null, cache);
            }
        } catch (IOException ignored) {
            // Best-effort — the opportunistic prune is hygiene, never load-bearing.
        }
    }

    /**
     * Run the queued opportunistic prune, if any, now that no pipeline is in flight. Runs on the
     * finishing request's connection thread (keeping the engine visibly busy, so idle-exit can't
     * race it); a pipeline that starts concurrently wins the {@link #cacheGate} race and the prune
     * stays queued for the next boundary. Mirrors the legacy {@code --background} flags: sweep on,
     * TTL/budget from {@code [cache]} config, {@code .prune.lock} held, {@code .last-pruned} stamped.
     */
    private void drainPendingPrune() {
        Path cache = pendingPruneCache.getAndSet(null);
        if (cache == null) return;
        if (!cacheGate.writeLock().tryLock()) {
            pendingPruneCache.compareAndSet(null, cache); // a new pipeline raced in — retry next boundary
            return;
        }
        try (FileChannel lockChan = FileChannel.open(
                cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock pruneLock = lockChan.tryLock();
            if (pruneLock == null) return; // another process's prune is running — it'll stamp .last-pruned
            try {
                var config = dev.jkbuild.config.JkCacheConfig.resolve();
                dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.CacheGoals.pruneGoal(
                        cache,
                        config.recordTtlDays(),
                        false,
                        true,
                        config.maxSizeGb().map(gb -> gb + "G").orElse(null),
                        false);
                dev.jkbuild.run.GoalResult result = goal.run();
                if (result.success()) {
                    Files.writeString(
                            cache.resolve(dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE),
                            Long.toString(clockMillis.getAsLong()),
                            StandardCharsets.UTF_8);
                    log.accept("jk engine: idle-boundary cache prune removed "
                            + goal.get(dev.jkbuild.runtime.CacheGoals.FILES).orElse(0L)
                            + " files ("
                            + goal.get(dev.jkbuild.runtime.CacheGoals.BYTES).orElse(0L)
                            + " bytes)");
                } else {
                    log.accept("jk engine: idle-boundary cache prune failed");
                }
            } finally {
                pruneLock.release();
            }
        } catch (Exception e) {
            log.accept("jk engine: idle-boundary cache prune failed: " + e.getMessage());
        } finally {
            cacheGate.writeLock().unlock();
        }
    }

    /** Decode the request, reconstruct a {@link Session}/{@link WorkspaceRequest}, and run it. */
    private void runBuild(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "entryDir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            int workers = Ndjson.intValue(requestLine, "workers", 1);
            String profile = Ndjson.str(requestLine, "profile");
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            int maxModuleConcurrency = Ndjson.intValue(requestLine, "maxModuleConcurrency", 0);
            boolean parallelTests = Ndjson.bool(requestLine, "parallelTests", false);
            boolean offline = Ndjson.bool(requestLine, "offline", false);
            boolean force = Ndjson.bool(requestLine, "force", false);
            // Distinct from force: rerun bypasses the action cache / freshness stamps without
            // implying refresh, so locked dependencies still come from the local CAS (jk verify).
            boolean rerun = Ndjson.bool(requestLine, "rerun", false);
            // jk build sends true (auto-freshen a stale workspace lock engine-side before building);
            // jk verify's scratch rebuild sends false (pinned lock used verbatim).
            boolean freshenLock = Ndjson.bool(requestLine, "freshenLock", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;

            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            WorkspaceRequest req = new WorkspaceRequest(
                    entryDir,
                    entryBuild,
                    cache,
                    jdksDir,
                    workers,
                    profile,
                    skipTests,
                    verbose,
                    maxModuleConcurrency,
                    null, // let the engine forecast dirty modules itself — see docs/engine.md
                    false, // this engine plans memory once at startup, not per request
                    freshenLock);

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(rerun),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withParallelTests(parallelTests)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));

            WorkspaceBuildListener listener = wireListener(writer);
            WorkspaceResult result =
                    SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            accOutcome(eventRequestId(), result.success(), result.exitCode());
            send(writer, EngineProtocol.workspaceFinish(result.success(), result.exitCode(), result.errors()));
            if (!result.success()) {
                for (String error : result.errors().stream().limit(5).toList()) {
                    publishRequestError(eventRequestId(), entryDirStr, error);
                }
            }
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), Ndjson.str(requestLine, "entryDir"), String.valueOf(e.getMessage()));
        }
    }

    /**
     * As {@link #handleBuildRequest}, but for a single project's test goal (Phase 3): forks the run
     * onto its own thread and keeps reading the connection for a cancel/EOF meanwhile.
     */
    private void handleTestRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-engine-test-", this::runTest);
    }

    /**
     * As {@link #handleTestRequest}, but for a single (non-workspace) project's real build goal — the
     * engine-hosted counterpart of {@code BuildCommand.runForDir}.
     */
    private void handleSingleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-engine-1build-", this::runSingleBuild);
    }

    /**
     * Answer {@code jk build}'s pre-flight ({@link EngineProtocol#FORECAST_REQUEST}): resolve the
     * graph, run the dirty forecast, and stat the workspace lock — one synchronous {@link
     * EngineProtocol#FORECAST_ACK} back. Read-only (the same calls the client used to make
     * in-process before the slim-client Stage 5 cut); honors the request's force/rerun flags via a
     * request-scoped session, exactly as the in-process path honored the CLI's.
     */
    /**
     * Answer {@link EngineProtocol#PROJECT_INFO_REQUEST}: the thin client's parsed-project
     * summary. Synchronous, read-only, inline — errors ride the ack's {@code error} field.
     */
    private void handleProjectInfoRequest(String requestLine, BufferedWriter writer) {
        dev.jkbuild.engine.protocol.ProjectInfo info;
        try {
            info = dev.jkbuild.runtime.ExecPlans.projectInfo(Path.of(Ndjson.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            info = dev.jkbuild.engine.protocol.ProjectInfo.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, info.encode());
    }

    /**
     * Answer {@link EngineProtocol#EXEC_PLAN_REQUEST}: a complete execution plan (run/dev argv,
     * install layout, aot-cache layout) — the engine decides, the client executes. Synchronous,
     * read-only, inline.
     */
    /** Answer {@link EngineProtocol#TREE_REQUEST}: the marker-tagged dependency tree, engine-side. */
    private void handleTreeRequest(String requestLine, BufferedWriter writer) {
        String error = null;
        String rendered = null;
        try {
            rendered = dev.jkbuild.runtime.GraphOps.treeRender(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Ndjson.intValue(requestLine, "maxDepth", Integer.MAX_VALUE),
                    Ndjson.bool(requestLine, "flatten", false),
                    Ndjson.bool(requestLine, "stack", false),
                    Ndjson.strArray(requestLine, "scopes"));
        } catch (java.io.IOException | RuntimeException e) {
            error = String.valueOf(e.getMessage());
        }
        sendQuiet(writer, EngineProtocol.treeAck(error, rendered));
    }

    /** Answer {@link EngineProtocol#WHY_REQUEST}: lock matches + provenance paths, engine-side. */
    private void handleWhyRequest(String requestLine, BufferedWriter writer) {
        dev.jkbuild.engine.protocol.WhyReport report;
        try {
            report = dev.jkbuild.runtime.GraphOps.why(
                    Path.of(Ndjson.str(requestLine, "dir")), Ndjson.str(requestLine, "query"));
        } catch (RuntimeException e) {
            report = dev.jkbuild.engine.protocol.WhyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#DENY_CHECK_REQUEST}: policy parse + lock read + check, engine-side. */
    private void handleDenyCheckRequest(String requestLine, BufferedWriter writer) {
        dev.jkbuild.engine.protocol.DenyReport report;
        try {
            report = dev.jkbuild.runtime.PolicyOps.denyCheck(Path.of(Ndjson.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            report = dev.jkbuild.engine.protocol.DenyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#EDIT_REQUEST}: one named jk.toml edit, engine-side. */
    private void handleEditRequest(String requestLine, BufferedWriter writer) {
        dev.jkbuild.runtime.EditOps.Result result;
        try {
            result = dev.jkbuild.runtime.EditOps.apply(
                    Path.of(Ndjson.str(requestLine, "file")),
                    Ndjson.str(requestLine, "op"),
                    Ndjson.strArray(requestLine, "args"));
        } catch (RuntimeException e) {
            result = new dev.jkbuild.runtime.EditOps.Result(false, String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, EngineProtocol.editAck(result.changed(), result.error()));
    }

    private void handleExecPlanRequest(String requestLine, BufferedWriter writer) {
        dev.jkbuild.engine.protocol.ExecPlan plan;
        try {
            String binDir = Ndjson.str(requestLine, "binDir");
            String libDir = Ndjson.str(requestLine, "libDir");
            plan = dev.jkbuild.runtime.ExecPlans.execPlan(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Path.of(Ndjson.str(requestLine, "cache")),
                    Ndjson.str(requestLine, "kind"),
                    Ndjson.str(requestLine, "mainOverride"),
                    Ndjson.str(requestLine, "binName"),
                    binDir == null ? null : Path.of(binDir),
                    libDir == null ? null : Path.of(libDir));
        } catch (RuntimeException e) {
            plan = dev.jkbuild.engine.protocol.ExecPlan.error("unknown", String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, plan.encode());
    }

    private void handleForecastRequest(String requestLine, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "entryDir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "offline", false)),
                    Optional.of(Ndjson.bool(requestLine, "rerun", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "force", false)),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache);
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            SessionContext.where(session, () -> {
                BuildService.ResolvedGraph graph;
                try {
                    graph = BuildService.resolveGraph(entryDir, entryBuild);
                } catch (java.io.IOException e) {
                    sendQuiet(
                            writer,
                            EngineProtocol.forecastAck(
                                    List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
                    return null;
                }
                if (graph.hasErrors()) {
                    sendQuiet(writer, EngineProtocol.forecastAck(List.of(), false, false, graph.errors()));
                    return null;
                }
                List<String> dirty = new java.util.ArrayList<>();
                for (Path d : BuildService.forecastDirtyDirs(graph, cache, skipTests)) dirty.add(d.toString());
                boolean lockStale =
                        BuildService.workspaceLockStale(entryDir, entryBuild, entryDir.resolve("jk.lock"));
                sendQuiet(writer, EngineProtocol.forecastAck(dirty, lockStale, graph.isEmpty(), List.of()));
                return null;
            });
        } catch (Exception e) {
            sendQuiet(
                    writer,
                    EngineProtocol.forecastAck(List.of(), false, false, List.of(String.valueOf(e.getMessage()))));
        }
    }

    /**
     * Forecast a build via {@link BuildService#explain} and stream the plan as a burst of
     * module/phase/edge messages — the engine-hosted counterpart of {@code ExplainCommand}'s call
     * into the same facade. Synchronous and inline (no worker JVM forked, no cancel/EOF fork needed
     * unlike {@link #handleBuildRequest}/{@link #handleTestRequest}/{@link #handleSingleBuildRequest}).
     */
    private void handleExplainRequest(String requestLine, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "entryDir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            ExplainPlan plan = BuildService.explain(entryDir, entryBuild, cache);
            if (plan.hasErrors()) {
                for (String err : plan.errors()) {
                    sendQuiet(writer, EngineProtocol.explainError(err));
                }
                sendQuiet(writer, EngineProtocol.explainDone(1, 0));
                return;
            }
            for (dev.jkbuild.runtime.BuildPlan.Module m : plan.modules()) {
                String dir = m.dir().toString();
                sendQuiet(
                        writer,
                        EngineProtocol.explainModule(
                                dir, m.coord(), m.sourceCount(), m.testCount(), m.producesJar(), m.producesImage()));
                for (dev.jkbuild.runtime.BuildPlan.Phase p : m.phases()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.explainPhase(
                                    dir, p.name(), p.status().name(), p.text(), p.key()));
                }
            }
            for (var e : plan.edges().entrySet()) {
                for (Path dep : e.getValue()) {
                    sendQuiet(writer, EngineProtocol.explainEdge(e.getKey().toString(), dep.toString()));
                }
            }
            // The schedule-aware build-time estimate (Wave 3: previously computed client-side
            // against BuildPipeline/EffortWeights/Calibration — the known docs/engine.md gap).
            // 0 = unknown; the client renders "Build time unknown" for that.
            String etaJdksDirStr = Ndjson.str(requestLine, "jdksDir");
            long etaMillis = BuildService.estimateEtaMillis(
                    plan,
                    cache,
                    Ndjson.intValue(requestLine, "workers", 1),
                    etaJdksDirStr != null ? Path.of(etaJdksDirStr) : null,
                    Ndjson.str(requestLine, "profile"),
                    Ndjson.bool(requestLine, "skipTests", false),
                    Ndjson.bool(requestLine, "verbose", false),
                    Ndjson.bool(requestLine, "serial", false),
                    Ndjson.bool(requestLine, "parallelTests", false));
            sendQuiet(writer, EngineProtocol.eta(etaMillis));
            sendQuiet(writer, EngineProtocol.explainDone(plan.maxReadyWidth(), plan.modules().size()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.explainError(String.valueOf(e.getMessage())));
            sendQuiet(writer, EngineProtocol.explainDone(0, 0));
        }
    }

    /**
     * Build and run the test-only {@code Goal} exactly as {@code TestCommand} does in-process, but
     * streaming its {@link GoalListener} events over the wire via {@link #wireGoalListener} — the
     * same single-goal event vocabulary {@link #runBuild} already speaks per module, here tagged with
     * the fixed {@link EngineProtocol#SINGLE_GOAL_DIR} sentinel since there's only one goal.
     */
    private void runTest(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "entryDir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            int workers = Ndjson.intValue(requestLine, "workers", 1);
            String profile = Ndjson.str(requestLine, "profile");
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            boolean offline = Ndjson.bool(requestLine, "offline", false);
            boolean force = Ndjson.bool(requestLine, "force", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = entryDir.resolve("jk.lock");
            int workerCount = Math.max(1, workers);

            int estimatedTestCount = dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/java"))
                    + dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/kotlin"));

            // The request's cache-relevant flags ride the session config exactly as
            // runSingleBuild's do — without this, `jk test --force` was silently
            // dropped on the hosted path (the run-tests stamp skip guards on rerunOr,
            // which reads the force flag).
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));

            // The session rides Inputs EXPLICITLY (canonical constructor): the delegating
            // constructors capture SessionContext.current() at construction time, which here —
            // outside SessionContext.where — is the engine's ambient default, not this request.
            // Phases read in.session() for the force/rerun guards, so the ambient capture was
            // exactly how `--force` got dropped.
            dev.jkbuild.runtime.BuildPipeline.Inputs inputs = new dev.jkbuild.runtime.BuildPipeline.Inputs(
                    entryDir,
                    cache,
                    buildFile,
                    lockFile,
                    lockFile.getParent(),
                    workerCount,
                    estimatedTestCount,
                    profile,
                    jdksDir,
                    /* skipTests */ false,
                    verbose,
                    /* testOnly */ true,
                    /* compileOnly */ false,
                    java.util.Set.of(),
                    session);
            dev.jkbuild.run.Goal goal =
                    dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs).build();

            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            for (Phase p : goal.phases()) {
                sendQuiet(writer, EngineProtocol.planPhase(dir, p.name(), p.label()));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            goal.addListener(wireGoalListener(dir, writer, goal));

            dev.jkbuild.run.GoalResult result = SessionContext.where(session, goal::run);
            accTests(eventRequestId(), goal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            // goalFinish (with test counts, if any) was already sent by wireGoalListener's own
            // goalFinish handling — nothing further to send here; the connection close signals "done".
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Build and run a single (non-workspace) project's real build goal — the engine-hosted
     * counterpart of {@code BuildCommand.runForDir}/{@code prepareModule}/{@code runPrepared}'s
     * {@code agg == null} branch. Same single-goal wire shape as {@link #runTest}, {@code
     * testOnly=false}. On success, folds the measured throughput into the host calibration and
     * queues the opportunistic cache prune for the next idle boundary (Wave 4: an engine-internal
     * enqueue, not a detached self-spawn) — exactly {@code runPrepared}'s post-success logic, moved
     * here since the engine (not the CLI) is the process that actually ran the goal.
     */
    private void runSingleBuild(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "entryDir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            int workers = Ndjson.intValue(requestLine, "workers", 1);
            String profile = Ndjson.str(requestLine, "profile");
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            boolean offline = Ndjson.bool(requestLine, "offline", false);
            boolean force = Ndjson.bool(requestLine, "force", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = entryDir.resolve("jk.lock");
            int workerCount = Math.max(1, workers);

            int estimatedTestCount = skipTests
                    ? 0
                    : dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/java"))
                            + dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/kotlin"));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(force),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));

            // Session threaded explicitly — see runTest: the delegating Inputs constructors
            // capture the engine's ambient session at construction, dropping --force/--offline.
            dev.jkbuild.runtime.BuildPipeline.Inputs inputs = new dev.jkbuild.runtime.BuildPipeline.Inputs(
                    entryDir,
                    cache,
                    buildFile,
                    lockFile,
                    lockFile.getParent(),
                    workerCount,
                    estimatedTestCount,
                    profile,
                    jdksDir,
                    skipTests,
                    verbose,
                    /* testOnly */ false,
                    /* compileOnly */ false,
                    java.util.Set.of(),
                    session);
            dev.jkbuild.run.Goal.Builder builder = dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs, false);
            dev.jkbuild.runtime.BuildPipeline.appendDeclaredTails(builder, inputs);
            dev.jkbuild.run.Goal goal = builder.build();
            long barWeight = goal.estimatedTotalWeight();

            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            for (Phase p : goal.phases()) {
                sendQuiet(writer, EngineProtocol.planPhase(dir, p.name(), p.label()));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            goal.addListener(wireGoalListener(dir, writer, goal));

            long startNanos = System.nanoTime();
            dev.jkbuild.run.GoalResult result = SessionContext.where(session, goal::run);
            accTests(eventRequestId(), goal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    dev.jkbuild.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                maybeEnqueuePrune(cache);
            }
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#LOCK_REQUEST} and run {@code jk lock}'s cascade in-session:
     * the entry project, then (for a workspace root) each declared module in declaration order —
     * each module a {@link EngineProtocol#LOCK_MODULE} + plan-phase burst + the standard goal
     * events, ending in a {@link EngineProtocol#LOCK_FINISH} terminal. Per-package resolution
     * streams as {@link EngineProtocol#LOCK_PACKAGE} (plain structured text; the client formats and
     * colorizes). Forge tokens for git-source materialization resolve exactly as in the CLI — the
     * same {@code ~/.jk} token store and environment, which this engine process inherits from its
     * spawner.
     */
    private void runLock(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            java.util.List<String> features = Ndjson.strArray(requestLine, "features");
            boolean withDefaults = !Ndjson.bool(requestLine, "noDefaultFeatures", false);
            boolean sources = Ndjson.bool(requestLine, "sources", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                lockCascade(session.workingDir(), session.cacheDir(), repoUrl, features, withDefaults, sources, false, writer);
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#UPDATE_REQUEST}: either the full re-resolve cascade (riding
     * {@link #runLock}'s exact event vocabulary, with {@code jk update}'s always-fresh goal) or the
     * {@code --git} splice mode, which runs no goal at all — just the {@link
     * EngineProtocol#LOCK_FINISH} terminal carrying the refreshed count.
     */
    private void runUpdate(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            java.util.List<String> features = Ndjson.strArray(requestLine, "features");
            boolean withDefaults = !Ndjson.bool(requestLine, "noDefaultFeatures", false);
            boolean gitOnly = Ndjson.bool(requestLine, "gitOnly", false);
            String gitTarget = Ndjson.str(requestLine, "gitTarget");
            Session session = resolveSession(requestLine, cancelToken, false);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                if (gitOnly) {
                    java.nio.file.Files.createDirectories(cache);
                    JkBuild root;
                    try {
                        root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
                    } catch (RuntimeException e) {
                        sendQuiet(writer, EngineProtocol.lockFinish(
                                false, dev.jkbuild.model.command.Exit.CONFIG,
                                java.util.List.of(String.valueOf(e.getMessage())), -1));
                        return null;
                    }
                    var outcome = dev.jkbuild.runtime.LockGoals.updateGitOnly(
                            entryDir, root, cache, repoUrl, features, withDefaults, gitTarget);
                    sendQuiet(writer, EngineProtocol.lockFinish(
                            outcome.exitCode() == 0,
                            outcome.exitCode(),
                            outcome.error() != null ? java.util.List.of(outcome.error()) : java.util.List.of(),
                            outcome.refreshed()));
                } else {
                    lockCascade(entryDir, cache, repoUrl, features, withDefaults, false, true, writer);
                }
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#SYNC_REQUEST} and run {@code jk sync}'s single goal in-session
     * — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape, with the fetched/up-to-date counts
     * riding the terminal goal-finish. The goal is built with {@code allowJdkInstall = false}: JDK
     * installs never happen inside the engine (the client pre-flights them — see {@link
     * dev.jkbuild.runtime.SyncGoals}). On success, queues the opportunistic cache prune for the
     * next idle boundary — the post-success step the CLI used to run, moved here since the engine
     * did the work.
     */
    private void runSync(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean sources = Ndjson.bool(requestLine, "sources", false);
            boolean refresh = Ndjson.bool(requestLine, "refresh", false);
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Session session = resolveSession(requestLine, cancelToken, refresh).withJdksDir(jdksDir);
            java.net.URI repoUrl = repoUrlOf(requestLine);
            SessionContext.where(session, () -> {
                Path entryDir = session.workingDir();
                Path cache = session.cacheDir();
                java.nio.file.Files.createDirectories(cache);
                java.util.concurrent.atomic.AtomicInteger fetched = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger upToDate = new java.util.concurrent.atomic.AtomicInteger();
                dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.SyncGoals.syncGoal(
                        entryDir, cache, jdksDir, repoUrl, sources, fetched, upToDate, null, false);
                String dir = EngineProtocol.SINGLE_GOAL_DIR;
                for (Phase p : goal.phases()) {
                    sendQuiet(writer, EngineProtocol.planPhase(dir, p.name(), p.label()));
                }
                sendQuiet(writer, EngineProtocol.planDone(1));
                goal.addListener(wireGoalListener(
                        dir,
                        writer,
                        (java.util.function.Function<GoalResult, String>) result ->
                                EngineProtocol.goalFinishSync(dir, result.success(), fetched.get(), upToDate.get())));
                GoalResult result = goal.run();
                if (result.success()) {
                    maybeEnqueuePrune(cache);
                }
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted worker verbs (Wave 2 of the slim-client migration) -------------------------------

    /**
     * Stream one single-goal verb over the wire — the shared tail of every Wave-2 handler: the
     * {@link EngineProtocol#SINGLE_GOAL_DIR}-tagged plan-phase burst, the standard goal events via
     * {@link #wireGoalListener}, and {@code finishEncoder}'s terminal {@code goal-finish} variant.
     */
    private void streamSingleGoal(
            dev.jkbuild.run.Goal goal,
            Session session,
            BufferedWriter writer,
            java.util.function.Function<GoalResult, String> finishEncoder)
            throws Exception {
        String dir = EngineProtocol.SINGLE_GOAL_DIR;
        for (Phase p : goal.phases()) {
            sendQuiet(writer, EngineProtocol.planPhase(dir, p.name(), p.label()));
        }
        sendQuiet(writer, EngineProtocol.planDone(1));
        goal.addListener(wireGoalListener(dir, writer, finishEncoder));
        SessionContext.where(session, goal::run);
    }

    /**
     * Decode an {@link EngineProtocol#AUDIT_REQUEST} and run {@code jk audit}'s goal in-session,
     * forking the auditor worker engine-side and streaming each finding as a structured {@link
     * EngineProtocol#AUDIT_FINDING} event (the client assembles/renders the report and applies the
     * severity threshold itself).
     */
    private void runAudit(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "entryDir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String severity = Ndjson.str(requestLine, "severity");
            String batch = Ndjson.str(requestLine, "osvBatchUrl");
            String vulns = Ndjson.str(requestLine, "osvVulnsUrl");
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.AuditGoals.auditGoal(
                    entryDir.resolve("jk.lock"),
                    cache,
                    severity,
                    batch != null ? java.net.URI.create(batch) : null,
                    vulns != null ? java.net.URI.create(vulns) : null,
                    (module, version, vulnId, sev, summary) -> sendQuiet(
                            writer, EngineProtocol.auditFinding(dir, module, version, vulnId, sev, summary)));
            streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#FORMAT_REQUEST} and run {@code jk format}'s goal in-session:
     * source collection, formatter-jar resolution (through jk's own resolver — previously done in
     * the client process), and the formatter worker fork, with per-file results streaming as {@link
     * EngineProtocol#FORMAT_FILE} events and the counts riding the terminal goal-finish.
     */
    private void runFormat(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean check = Ndjson.bool(requestLine, "check", false);
            String javaStyle = Ndjson.str(requestLine, "javaStyle");
            String kotlinStyle = Ndjson.str(requestLine, "kotlinStyle");
            boolean optimizeImports = Ndjson.bool(requestLine, "optimizeImports", true);
            String rewriteConfig = Ndjson.str(requestLine, "rewriteConfig");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.FormatGoals.formatGoal(
                    session.workingDir(),
                    session.cacheDir(),
                    check,
                    javaStyle,
                    kotlinStyle,
                    optimizeImports,
                    rewriteConfig != null ? Path.of(rewriteConfig) : null,
                    (path, status, message, index, total) ->
                            sendQuiet(writer, EngineProtocol.formatFile(dir, path, status, message, index, total)));
            streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinishFormat(
                    dir,
                    result.success(),
                    goal.get(dev.jkbuild.runtime.FormatGoals.CHANGED).orElse(-1),
                    goal.get(dev.jkbuild.runtime.FormatGoals.CLEAN).orElse(-1),
                    goal.get(dev.jkbuild.runtime.FormatGoals.ERRORS).orElse(-1),
                    goal.get(dev.jkbuild.runtime.FormatGoals.TOTAL).orElse(-1),
                    goal.get(dev.jkbuild.runtime.FormatGoals.WORKER_EXIT).orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PUBLISH_REQUEST} and run {@code jk publish}'s goal in-session.
     * The credential/passphrase fields were resolved client-side (env/keychain live there); they
     * pass straight through to the worker's 0600 spec file and are never logged.
     */
    private void runPublish(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "entryDir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String jar = Ndjson.str(requestLine, "jar");
            String keyFile = Ndjson.str(requestLine, "keyFile");
            dev.jkbuild.credential.RepoCredential credential =
                    switch (String.valueOf(Ndjson.str(requestLine, "authType"))) {
                        case "basic" -> new dev.jkbuild.credential.RepoCredential.Basic(
                                Ndjson.str(requestLine, "user"),
                                Ndjson.str(requestLine, "pass") != null ? Ndjson.str(requestLine, "pass") : "");
                        case "bearer" -> new dev.jkbuild.credential.RepoCredential.Bearer(
                                Ndjson.str(requestLine, "token"));
                        default -> dev.jkbuild.credential.RepoCredential.ANONYMOUS;
                    };
            dev.jkbuild.runtime.PublishGoals.Request req = new dev.jkbuild.runtime.PublishGoals.Request(
                    java.net.URI.create(Ndjson.str(requestLine, "repoUrl")),
                    Ndjson.str(requestLine, "region"),
                    Ndjson.str(requestLine, "endpoint"),
                    jar != null ? Path.of(jar) : null,
                    Ndjson.bool(requestLine, "allowSnapshot", false),
                    Ndjson.bool(requestLine, "dryRun", false),
                    keyFile != null ? Path.of(keyFile) : null,
                    Ndjson.str(requestLine, "gpgPassphrase"),
                    Ndjson.bool(requestLine, "sigstore", false),
                    Ndjson.bool(requestLine, "slsa", false),
                    Ndjson.bool(requestLine, "sbom", false),
                    credential);
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.PublishGoals.publishGoal(entryDir, cache, req);
            streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinishPublish(
                    dir,
                    result.success(),
                    goal.get(dev.jkbuild.runtime.PublishGoals.FILES).orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMAGE_REQUEST} and run {@code jk image}'s goal in-session —
     * the full build pipeline plus the image tail (Jib worker or Dockerfile child process), all
     * engine-side. The terminal goal-finish carries the structured success-tail fields alongside
     * the test counts the client's exit-code logic needs.
     */
    private void runImage(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "entryDir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "offline", false)),
                    Optional.of(Ndjson.bool(requestLine, "rerun", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(verbose),
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "force", false)),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            // Constructed in-session: the goal factory's BuildPipeline.Inputs captures the
            // ambient SessionContext at construction, so building it outside where() would
            // silently pin this request to the engine's default config (dropping --force et al).
            dev.jkbuild.run.Goal goal = SessionContext.where(session, () -> dev.jkbuild.runtime.ImageGoals.imageGoal(
                    entryDir,
                    cache,
                    jdksDir,
                    skipTests,
                    verbose,
                    Ndjson.str(requestLine, "mainClass"),
                    Ndjson.str(requestLine, "registry"),
                    Ndjson.str(requestLine, "tag"),
                    Ndjson.str(requestLine, "tarball"),
                    Ndjson.str(requestLine, "dockerExecutable")));
            streamSingleGoal(goal, session, writer, result -> {
                dev.jkbuild.run.TestSummary testResult =
                        goal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null);
                dev.jkbuild.image.ImageConfig cfg =
                        goal.get(dev.jkbuild.runtime.ImageGoals.CONFIG).orElse(null);
                Path tarball = goal.get(dev.jkbuild.runtime.ImageGoals.TARBALL_PATH)
                        .orElse(null);
                JkBuild project =
                        goal.get(dev.jkbuild.runtime.BuildPipeline.PROJECT).orElse(null);
                boolean daemonMode =
                        tarball == null && (cfg == null || cfg.registry() == null || cfg.registry().isBlank());
                String daemonExe = !daemonMode
                        ? null
                        : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
                return EngineProtocol.goalFinishImage(
                        dir,
                        result.success(),
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1,
                        goal.get(dev.jkbuild.runtime.ImageGoals.IMAGE_REF).orElse(null),
                        tarball != null ? tarball.toString() : null,
                        project != null ? project.project().name() : null,
                        project != null ? project.project().version() : null,
                        daemonExe);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMPORT_REQUEST} and run {@code jk import}'s single-phase goal
     * in-session, streaming the worker's progress notes as {@link EngineProtocol#IMPORT_NOTE}
     * events. The worker's exit code/warnings/error ride the terminal goal-finish (a non-zero
     * worker exit is a result the client renders, not a goal failure).
     */
    private void runImport(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path baseDir = Path.of(Ndjson.str(requestLine, "baseDir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String report = Ndjson.str(requestLine, "report");
            Session session = Session.defaults()
                    .withWorkingDir(baseDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.CompatGoals.importGoal(
                    Path.of(Ndjson.str(requestLine, "source")),
                    Path.of(Ndjson.str(requestLine, "out")),
                    baseDir,
                    Path.of(Ndjson.str(requestLine, "tmpDir")),
                    Ndjson.bool(requestLine, "force", false),
                    report != null ? Path.of(report) : null,
                    cache,
                    (kind, text) -> sendQuiet(writer, EngineProtocol.importNote(dir, kind, text)));
            streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinishImport(
                    dir,
                    result.success(),
                    goal.get(dev.jkbuild.runtime.CompatGoals.EXIT).orElse(1),
                    goal.get(dev.jkbuild.runtime.CompatGoals.WARNINGS).orElse(0),
                    goal.get(dev.jkbuild.runtime.CompatGoals.ERROR).orElse(null),
                    goal.get(dev.jkbuild.runtime.CompatGoals.DIAG).orElse(null)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PROVISION_REQUEST}, provision the Maven/Gradle distribution
     * via the compat-bridge worker, and reply with the one-shot {@link
     * EngineProtocol#PROVISION_RESULT} terminal. The exec of the provisioned tool stays client-side.
     */
    private void runProvision(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            var outcome = dev.jkbuild.runtime.CompatGoals.provision(
                    Path.of(Ndjson.str(requestLine, "cache")),
                    Path.of(Ndjson.str(requestLine, "projectDir")),
                    Path.of(Ndjson.str(requestLine, "toolsRoot")),
                    Ndjson.bool(requestLine, "noDiscover", false),
                    Ndjson.bool(requestLine, "gradle", false));
            sendQuiet(writer, EngineProtocol.provisionResult(
                    outcome.bin(),
                    outcome.version(),
                    outcome.source(),
                    outcome.error(),
                    outcome.exit(),
                    outcome.diag()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted pipeline verbs (Wave 3 of the slim-client migration) ------------------------------

    /**
     * Decode a {@link EngineProtocol#COMPILE_REQUEST} and run {@code jk compile}'s single
     * compile-only goal in-session — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape with a
     * plain terminal goal-finish (the verb has no structured summary beyond success).
     */
    private void runCompile(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String profile = Ndjson.str(requestLine, "profile");
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            dev.jkbuild.run.Goal goal = SessionContext.where(
                    session,
                    () -> dev.jkbuild.runtime.CompileGoals.compileGoal(
                            session.workingDir(), session.cacheDir(), profile, verbose));
            streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#INSTALL_REQUEST} and run {@code jk install}'s build +
     * cache-install goal in-session (see {@link dev.jkbuild.runtime.InstallGoals}). The terminal
     * goal-finish carries the test counts for the client's exit-code logic; the launcher-writing
     * "make install" half runs client-side after this succeeds.
     */
    private void runInstall(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            String m2DirStr = Ndjson.str(requestLine, "m2Dir");
            String graalHomeStr = Ndjson.str(requestLine, "graalHome");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            dev.jkbuild.run.Goal goal = SessionContext.where(
                    session,
                    () -> dev.jkbuild.runtime.InstallGoals.projectInstallGoal(
                            session.workingDir(),
                            session.cacheDir(),
                            Path.of(m2DirStr),
                            skipTests,
                            verbose,
                            graalHomeStr != null ? Path.of(graalHomeStr) : null));
            streamSingleGoal(goal, session, writer, result -> {
                dev.jkbuild.run.TestSummary testResult =
                        goal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null);
                return testResult == null
                        ? EngineProtocol.goalFinish(dir, result.success())
                        : EngineProtocol.goalFinish(
                                dir,
                                result.success(),
                                testResult.total(),
                                testResult.succeeded(),
                                testResult.failed(),
                                testResult.skipped());
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#GIT_FETCH_REQUEST} and materialize the checkout in-session
     * (the git-client worker forks engine-side); the terminal goal-finish carries the checkout
     * path + sha the client's follow-up {@link EngineProtocol#INSTALL_REQUEST} needs.
     */
    private void runGitFetch(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            boolean refresh = Ndjson.bool(requestLine, "refresh", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(refresh),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.InstallGoals.gitFetchGoal(
                    Ndjson.str(requestLine, "url"),
                    Ndjson.str(requestLine, "canonicalUrl"),
                    Ndjson.str(requestLine, "ref"),
                    cache,
                    refresh,
                    Ndjson.bool(requestLine, "requireJkToml", true));
            streamSingleGoal(goal, session, writer, result -> {
                Path checkout = goal.get(dev.jkbuild.runtime.InstallGoals.CHECKOUT).orElse(null);
                String sha = goal.get(dev.jkbuild.runtime.InstallGoals.FETCHED_SHA).orElse(null);
                return EngineProtocol.goalFinishGitFetch(
                        dir, result.success(), checkout != null ? checkout.toString() : null, sha);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted long-tail verbs (Wave 4 of the slim-client migration) -----------------------------

    /**
     * Decode a {@link EngineProtocol#SCRIPT_PREPARE_REQUEST} and run the shared script-preparation
     * goal ({@code jk tool run <file>}'s parse/resolve/compile half — see {@link
     * dev.jkbuild.runtime.ScriptGoals}). The terminal goal-finish carries the exec ingredients; the
     * exec stays client-side.
     */
    private void runScriptPrepare(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String mode = String.valueOf(Ndjson.str(requestLine, "mode"));
            Path script = Path.of(Ndjson.str(requestLine, "script"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String stateDirStr = Ndjson.str(requestLine, "stateDir");
            Path stateDir = stateDirStr != null ? Path.of(stateDirStr) : dev.jkbuild.util.JkDirs.state();
            java.net.URI repoUrl = repoUrlOf(requestLine);
            boolean forceRecompile = Ndjson.bool(requestLine, "forceRecompile", false);
            java.nio.file.Files.createDirectories(cache);
            Session session = Session.defaults()
                    .withWorkingDir(script.toAbsolutePath().getParent())
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            java.util.List<dev.jkbuild.model.Dependency> extraDeps = Ndjson.strArray(requestLine, "with").stream()
                    .map(dev.jkbuild.script.ScriptHeaderParser::parseDependency)
                    .toList();
            dev.jkbuild.run.Goal goal =
                    switch (mode) {
                        case "java" -> dev.jkbuild.runtime.ScriptGoals.javaScriptGoal(
                                script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kt" -> dev.jkbuild.runtime.ScriptGoals.kotlinScriptGoal(
                                script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kts" -> dev.jkbuild.runtime.ScriptGoals.ktsScriptGoal(script, cache, repoUrl, extraDeps);
                        case "jar" -> dev.jkbuild.runtime.ScriptGoals.jarGoal(script, cache, repoUrl);
                        default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                    };
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            streamSingleGoal(goal, session, writer, result -> {
                Path classesDir = goal.get(dev.jkbuild.runtime.ScriptGoals.CLASSES_DIR).orElse(null);
                Path kotlincBin = goal.get(dev.jkbuild.runtime.ScriptGoals.KOTLINC_BIN).orElse(null);
                Path stdlib = goal.get(dev.jkbuild.runtime.ScriptGoals.KT_STDLIB).orElse(null);
                return EngineProtocol.goalFinishScript(
                        dir,
                        result.success(),
                        goal.get(dev.jkbuild.runtime.ScriptGoals.MAIN_CLASS).orElse(null),
                        dev.jkbuild.runtime.ScriptGoals.classpathOf(goal).stream()
                                .map(Path::toString)
                                .toList(),
                        classesDir != null ? classesDir.toString() : null,
                        kotlincBin != null ? kotlincBin.toString() : null,
                        stdlib != null ? stdlib.toString() : null);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#TOOL_RESOLVE_REQUEST} and run the shared tool-resolution goal
     * in-session ({@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s Maven
     * resolve + fetch — see {@link dev.jkbuild.runtime.ToolGoals}). The terminal goal-finish carries
     * the resolved main class + classpath; the launcher write / inheritIO exec stays client-side.
     */
    private void runToolResolve(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String coord = Ndjson.str(requestLine, "coord");
            String bin = Ndjson.str(requestLine, "bin");
            String mainClass = Ndjson.str(requestLine, "mainClass");
            java.net.URI repoUrl = repoUrlOf(requestLine);
            java.nio.file.Files.createDirectories(cache);
            dev.jkbuild.model.ToolCoordSpec spec = dev.jkbuild.model.ToolCoordSpec.parse(coord);
            java.util.List<dev.jkbuild.model.ToolCoordSpec> with = Ndjson.strArray(requestLine, "with").stream()
                    .map(dev.jkbuild.model.ToolCoordSpec::parse)
                    .toList();
            Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_GOAL_DIR;
            // Plain g:a[:v] label — coordinate colorization is a client-side concern.
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.ToolGoals.resolveGoal(
                    spec, with, bin, mainClass, repoUrl, cache, coord);
            streamSingleGoal(goal, session, writer, result -> {
                dev.jkbuild.tool.ToolEnv env =
                        goal.get(dev.jkbuild.runtime.ToolGoals.TOOL_ENV).orElse(null);
                return EngineProtocol.goalFinishTool(
                        dir,
                        result.success(),
                        env != null ? env.primary().toGav() : null,
                        env != null ? env.mainClass() : null,
                        env != null
                                ? env.classpath().stream().map(Path::toString).toList()
                                : java.util.List.of());
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#CACHE_PRUNE_REQUEST} and run its maintenance op ({@code prune}
     * / {@code purge} / {@code gc}) as an idle-boundary job: take {@link #cacheGate}'s write side
     * (emitting {@link EngineProtocol#PRUNE_WAIT} first when pipelines are in flight, so the client
     * isn't staring at silence) and the cross-process {@code .prune.lock}, then stream the shared
     * {@link dev.jkbuild.runtime.CacheGoals} goal — {@link EngineProtocol#TEST_REQUEST}'s wire shape
     * with a {@link EngineProtocol#goalFinishCache} terminal.
     */
    private void runCacheMaintenance(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String op = String.valueOf(Ndjson.str(requestLine, "op"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            boolean dryRun = Ndjson.bool(requestLine, "dryRun", false);

            if (!cacheGate.writeLock().tryLock()) {
                sendQuiet(writer, EngineProtocol.pruneWait(activePipelines.get(), false));
                cacheGate.writeLock().lock();
            }
            try {
                java.nio.file.Files.createDirectories(cache);
                try (FileChannel lockChan = FileChannel.open(
                        cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    FileLock pruneLock = lockChan.tryLock();
                    if (pruneLock == null) {
                        // Another process's prune holds the cross-process lock — wait for it too.
                        sendQuiet(writer, EngineProtocol.pruneWait(0, true));
                        pruneLock = lockChan.lock();
                    }
                    try {
                        dev.jkbuild.run.Goal goal =
                                switch (op) {
                                    case "purge" -> dev.jkbuild.runtime.CacheGoals.purgeGoal(cache);
                                    case "gc" -> dev.jkbuild.runtime.CacheGoals.gcGoal(cache);
                                    case "clear" -> dev.jkbuild.runtime.CacheGoals.clearGoal(
                                            cache, Path.of(Ndjson.str(requestLine, "projectRoot")), dryRun);
                                    default -> dev.jkbuild.runtime.CacheGoals.pruneGoal(
                                            cache,
                                            Ndjson.intValue(requestLine, "olderThanDays", 30),
                                            dryRun,
                                            Ndjson.bool(requestLine, "sweep", false),
                                            Ndjson.str(requestLine, "maxSize"),
                                            Ndjson.bool(requestLine, "includeJkTmp", false));
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        String dir = EngineProtocol.SINGLE_GOAL_DIR;
                        streamSingleGoal(goal, session, writer, result -> EngineProtocol.goalFinishCache(
                                dir,
                                result.success(),
                                goal.get(dev.jkbuild.runtime.CacheGoals.FILES).orElse(-1L),
                                goal.get(dev.jkbuild.runtime.CacheGoals.BYTES).orElse(-1L),
                                goal.get(dev.jkbuild.runtime.CacheGoals.REACHABLE_EVICTED)
                                        .orElse(-1L),
                                goal.get(dev.jkbuild.runtime.CacheGoals.REPO_LINKS)
                                        .orElse(-1L)));
                    } finally {
                        pruneLock.release();
                    }
                }
            } finally {
                cacheGate.writeLock().unlock();
            }
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#NATIVE_REQUEST} and run {@code jk native}'s serial module
     * cascade in-session, speaking {@link EngineProtocol#BUILD_REQUEST}'s workspace event
     * vocabulary (a single project is a cascade of one): a full plan burst first (so the client
     * calibrates its aggregate bar to the whole-workspace weight up front), then each module's goal
     * — the {@code native-image} child process forking engine-side — stopping at the first failure.
     * Exit codes are computed here ({@link dev.jkbuild.runtime.NativeGoals#failureExitCode}) and
     * ride {@code module-finish}/{@code workspace-finish}.
     */
    private void runNative(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            String mainClass = Ndjson.str(requestLine, "mainClass");
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            java.util.List<String> extraArgs = Ndjson.strArray(requestLine, "extraArgs");
            java.util.List<String> graalDirs = Ndjson.strArray(requestLine, "graalDirs");
            java.util.List<String> graalHomes = Ndjson.strArray(requestLine, "graalHomes");
            java.util.Map<Path, Path> graalByDir = new java.util.HashMap<>();
            for (int i = 0; i < Math.min(graalDirs.size(), graalHomes.size()); i++) {
                graalByDir.put(Path.of(graalDirs.get(i)), Path.of(graalHomes.get(i)));
            }
            Session session = resolveSession(requestLine, cancelToken, false).withJdksDir(jdksDir);
            SessionContext.where(session, () -> {
                nativeCascade(
                        session.workingDir(),
                        session.cacheDir(),
                        jdksDir,
                        mainClass,
                        extraArgs,
                        graalByDir,
                        skipTests,
                        verbose,
                        writer);
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /** The module cascade {@link #runNative} streams — see its javadoc for the wire shape. */
    private void nativeCascade(
            Path entryDir,
            Path cache,
            Path jdksDir,
            String mainClass,
            java.util.List<String> extraArgs,
            java.util.Map<Path, Path> graalByDir,
            boolean skipTests,
            boolean verbose,
            BufferedWriter writer) {
        JkBuild root;
        try {
            root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
        } catch (RuntimeException | IOException e) {
            sendQuiet(writer, EngineProtocol.workspaceFinish(
                    false, dev.jkbuild.model.command.Exit.CONFIG, java.util.List.of(String.valueOf(e.getMessage()))));
            return;
        }

        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        if (root.isWorkspaceRoot()) {
            java.util.Map<Path, JkBuild> modulesByDir;
            try {
                modulesByDir = dev.jkbuild.config.WorkspaceLoader.loadModules(entryDir, root);
            } catch (RuntimeException | IOException e) {
                sendQuiet(writer, EngineProtocol.workspaceFinish(
                        false,
                        dev.jkbuild.model.command.Exit.CONFIG,
                        java.util.List.of(String.valueOf(e.getMessage()))));
                return;
            }
            for (Path dir : dev.jkbuild.runtime.BuildGraph.orderModules(modulesByDir)) {
                scopes.put(dir, modulesByDir.get(dir));
            }
        } else {
            scopes.put(entryDir, root);
        }

        // Assemble every module's goal up front and send the whole plan burst first, so the
        // client's aggregate bar calibrates to the workspace total before any module runs.
        var goals = new java.util.LinkedHashMap<Path, dev.jkbuild.run.Goal>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            dev.jkbuild.run.Goal goal = dev.jkbuild.runtime.NativeGoals.moduleGoal(
                    dir,
                    scope.getValue(),
                    cache,
                    jdksDir,
                    graalByDir.get(dir),
                    mainClass,
                    extraArgs,
                    skipTests,
                    verbose);
            goals.put(dir, goal);
            coords.put(dir, dev.jkbuild.runtime.LockGoals.coordLabel(scope.getValue(), dir));
        }
        for (var entry : goals.entrySet()) {
            String dirTag = entry.getKey().toString();
            dev.jkbuild.run.Goal goal = entry.getValue();
            sendQuiet(writer, EngineProtocol.planModule(
                    dirTag,
                    coords.get(entry.getKey()),
                    goal.name(),
                    (int) Math.min(Integer.MAX_VALUE, goal.estimatedTotalWeight()),
                    false));
            for (Phase p : goal.phases()) {
                sendQuiet(writer, EngineProtocol.planPhase(dirTag, p.name(), p.label()));
            }
        }
        sendQuiet(writer, EngineProtocol.planDone(goals.size()));

        for (var entry : goals.entrySet()) {
            Path dir = entry.getKey();
            String dirTag = dir.toString();
            dev.jkbuild.run.Goal goal = entry.getValue();
            sendQuiet(writer, EngineProtocol.moduleStart(dirTag));
            goal.addListener(wireGoalListener(dirTag, writer, goal));
            long startNanos = System.nanoTime();
            GoalResult result = goal.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int exitCode = result.success() ? 0 : dev.jkbuild.runtime.NativeGoals.failureExitCode(goal, result);
            sendQuiet(writer, EngineProtocol.moduleFinish(
                    dirTag, coords.get(dir), result.success(), exitCode, millis));
            if (!result.success()) {
                sendQuiet(writer, EngineProtocol.workspaceFinish(false, exitCode, java.util.List.of()));
                return;
            }
        }
        sendQuiet(writer, EngineProtocol.workspaceFinish(true, 0, java.util.List.of()));
    }

    /**
     * The lock/update cascade both {@link #runLock} and {@link #runUpdate} stream: parse the entry
     * manifest, apply workspace context, then run one {@link dev.jkbuild.runtime.LockGoals} goal per
     * scope (entry project first, then each workspace module in declaration order), stopping at the
     * first failure. Exit codes are computed here — the engine saw the phase statuses — and sent on
     * the {@link EngineProtocol#LOCK_FINISH} terminal; pre-goal failures (manifest parse, module
     * load) travel as its plain-text {@code errors}.
     */
    private void lockCascade(
            Path entryDir,
            Path cache,
            java.net.URI repoUrl,
            java.util.List<String> features,
            boolean withDefaults,
            boolean sources,
            boolean update,
            BufferedWriter writer)
            throws Exception {
        java.nio.file.Files.createDirectories(cache);
        JkBuild root;
        try {
            root = JkBuildParser.parse(entryDir.resolve("jk.toml"));
        } catch (RuntimeException e) {
            sendQuiet(writer, EngineProtocol.lockFinish(
                    false, dev.jkbuild.model.command.Exit.CONFIG,
                    java.util.List.of(String.valueOf(e.getMessage())), -1));
            return;
        }
        JkBuild effectiveRoot = dev.jkbuild.runtime.LockGoals.applyWorkspaceContextIfModule(entryDir, root);

        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        scopes.put(entryDir, effectiveRoot);
        coords.put(entryDir, dev.jkbuild.runtime.LockGoals.coordLabel(effectiveRoot, entryDir));
        if (effectiveRoot.isWorkspaceRoot()) {
            java.util.Map<Path, JkBuild> modules;
            try {
                modules = dev.jkbuild.config.WorkspaceLoader.loadModules(entryDir, effectiveRoot);
            } catch (RuntimeException e) {
                sendQuiet(writer, EngineProtocol.lockFinish(
                        false, dev.jkbuild.model.command.Exit.CONFIG,
                        java.util.List.of(String.valueOf(e.getMessage())), -1));
                return;
            }
            for (var entry : modules.entrySet()) {
                scopes.put(
                        entry.getKey(),
                        dev.jkbuild.model.WorkspaceMerge.applyToModule(effectiveRoot, entry.getValue(), modules.values()));
                coords.put(entry.getKey(), dev.jkbuild.runtime.LockGoals.coordLabel(entry.getValue(), entry.getKey()));
            }
        }

        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            String dirTag = dir.toString();
            sendQuiet(writer, EngineProtocol.lockModule(dirTag, coords.get(dir)));

            dev.jkbuild.resolver.ResolveObserver observer = new dev.jkbuild.resolver.ResolveObserver() {
                @Override
                public void onTotal(int total) {
                    // scope growth already rides the goal's scope-update events
                }

                @Override
                public void onPackage(String module, String version) {
                    sendQuiet(writer, EngineProtocol.lockPackage(dirTag, module, version));
                }
            };
            dev.jkbuild.run.Goal goal = update
                    ? dev.jkbuild.runtime.LockGoals.updateGoal(dir, scope.getValue(), cache, repoUrl, features, withDefaults)
                    : dev.jkbuild.runtime.LockGoals.lockGoal(
                            dir, scope.getValue(), cache, repoUrl, features, withDefaults, sources, observer, null);
            for (Phase p : goal.phases()) {
                sendQuiet(writer, EngineProtocol.planPhase(dirTag, p.name(), p.label()));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            goal.addListener(wireGoalListener(dirTag, writer, (java.util.function.Function<GoalResult, String>)
                    result -> {
                        dev.jkbuild.lock.Lockfile lock =
                                goal.get(dev.jkbuild.runtime.LockGoals.LOCKFILE).orElse(null);
                        return EngineProtocol.goalFinishLock(
                                dirTag,
                                result.success(),
                                lock != null ? lock.artifacts().size() : -1,
                                lock != null
                                        ? lock.artifacts().stream()
                                                .filter(a -> a.sourcesChecksum() != null)
                                                .count()
                                        : -1,
                                lock != null ? lock.plugins().size() : -1);
                    }));

            GoalResult result = goal.run();
            if (!result.success()) {
                sendQuiet(writer, EngineProtocol.lockFinish(
                        false, dev.jkbuild.runtime.LockGoals.failureExitCode(result), java.util.List.of(), -1));
                return;
            }
        }
        sendQuiet(writer, EngineProtocol.lockFinish(true, 0, java.util.List.of(), -1));
    }

    /**
     * Reconstruct the request's {@link Session} from the flat config fields every lock/sync/update
     * request carries ({@code offline}/{@code force}/{@code verbose}, plus sync's {@code refresh})
     * — the same fields {@link #runBuild} decodes inline.
     */
    private static Session resolveSession(String requestLine, Session.CancelToken cancelToken, boolean refresh) {
        Path entryDir = Path.of(Ndjson.str(requestLine, "entryDir"));
        Path cache = Path.of(Ndjson.str(requestLine, "cache"));
        JkConfig config = new JkConfig(
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "offline", false)),
                Optional.empty(),
                Optional.of(refresh),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "verbose", false)),
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "force", false)),
                Optional.empty());
        return Session.defaults()
                .withConfig(config)
                .withWorkingDir(entryDir)
                .withCacheDir(cache)
                .withCancel(cancelToken)
                .withJvm(EngineProtocol.jvmTuning(requestLine));
    }

    /** The optional {@code repoUrl} request field ({@code --repo-url} overrides), or {@code null}. */
    private static java.net.URI repoUrlOf(String requestLine) {
        String s = Ndjson.str(requestLine, "repoUrl");
        return s != null ? java.net.URI.create(s) : null;
    }

    /** Translate every {@link WorkspaceBuildListener} callback into a wire event on {@code writer}. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer) {
        // Created on the runner's thread — capture the request id for the dashboard events now;
        // the callbacks below fire on scheduler/worker threads where the ThreadLocal isn't set.
        long eventRequestId = eventRequestId();
        return new WorkspaceBuildListener() {
            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                for (ModulePlan m : plan) {
                    String dir = m.dir().toString();
                    sendQuiet(
                            writer,
                            EngineProtocol.planModule(dir, m.coord(), m.goal().name(), m.weight(), m.fullyCached()));
                    for (Phase p : m.goal().phases()) {
                        sendQuiet(writer, EngineProtocol.planPhase(dir, p.name(), p.label()));
                    }
                }
                sendQuiet(writer, EngineProtocol.planDone(plan.size()));
                publishPlan(eventRequestId, plan.stream().mapToLong(ModulePlan::weight).sum());
            }

            @Override
            public void onEtaEstimate(long millis) {
                sendQuiet(writer, EngineProtocol.eta(millis));
                publishEta(eventRequestId, millis);
            }

            @Override
            public GoalListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                sendQuiet(writer, EngineProtocol.moduleStart(dir));
                publishModuleStart(eventRequestId, dir, m.coord());
                // wireGoalListener captures the dashboard request id from the currentEventRequestId
                // ThreadLocal — but onModuleStart runs on a WorkspaceScheduler thread where it isn't
                // set, so without this seed every per-module phase/goal-progress hub event would
                // publish under id -1 and be dropped (no per-module chains or weight bar). Seed it
                // with the request id captured on the request thread when wireListener was created.
                Long prev = currentEventRequestId.get();
                currentEventRequestId.set(eventRequestId);
                try {
                    return wireGoalListener(dir, writer, (dev.jkbuild.run.Goal) null);
                } finally {
                    if (prev == null) currentEventRequestId.remove();
                    else currentEventRequestId.set(prev);
                }
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                sendQuiet(
                        writer,
                        EngineProtocol.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.exitCode(), o.millis()));
                publishModuleFinish(eventRequestId, o.dir().toString(), o.coord(), o.success(), o.millis());
                accModule(eventRequestId, o);
            }
        };
    }

    /** The current thread's hosted-request id for dashboard events; {@code -1} outside a request. */
    private long eventRequestId() {
        Long id = currentEventRequestId.get();
        return id != null ? id : -1;
    }

    private void publishModuleStart(long requestId, String dir, String coord) {
        if (!eventsWanted()) return;
        publishEvent(
                "module-start",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("coord", coord));
    }

    private void publishModuleFinish(long requestId, String dir, String coord, boolean success, long millis) {
        if (!eventsWanted()) return;
        publishEvent(
                "module-finish",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("coord", coord)
                        .put("success", success)
                        .put("millis", millis));
    }

    private void publishGoalFinish(long requestId, String dir, boolean success) {
        if (!eventsWanted()) return;
        publishEvent(
                "goal-finish",
                dev.jkbuild.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("success", success));
    }

    // ---- build-history journal capture (docs: state/builds) ---------------------

    /** Request kinds we journal — the actual "build" verbs; lock/sync/tool/etc. are not history. */
    private static final java.util.Set<String> JOURNALED_KINDS = java.util.Set.of("build", "test");

    /** Open an accumulator for a journaled build kind (no-op when history is off or kind is other). */
    private void registerAccumulator(long requestId, String kind, String dir) {
        if (!historyConfig.enabled() || !JOURNALED_KINDS.contains(kind)) return;
        accumulators.put(requestId, new BuildAccumulator(kind, dir, coordOf(dir)));
    }

    /** The project's {@code group:name}, or {@code null} when its {@code jk.toml} doesn't parse. */
    private static String coordOf(String dir) {
        try {
            var project = JkBuildParser.parse(Path.of(dir).resolve("jk.toml")).project();
            return project.group() + ":" + project.name();
        } catch (Exception e) {
            return null;
        }
    }

    private void accModule(long requestId, ModuleOutcome o) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addModule(o);
    }

    private void accGoalFinish(long requestId, String dir, GoalResult result) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addGoal(dir, result);
    }

    /**
     * Record one finished phase under its module dir — the same {@code phaseFinish} signal the
     * dashboard renders, so the journal's per-module chains match the live cards exactly (a
     * workspace module's {@code GoalResult.phases()} isn't reliably populated, so we capture the
     * events directly).
     */
    private void accPhaseFinish(long requestId, String dir, String phase, String status, long millis) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addPhase(dir, phase, status, millis);
    }

    private void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null && tests != null) a.setTests(tests);
    }

    private void accOutcome(long requestId, boolean success, int exitCode) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.setOutcome(success, exitCode);
    }

    /**
     * Persist the finished build to the journal — ungated by dashboard subscribers, so every build
     * is captured whether or not a browser is watching, and survives an engine restart. Best-effort:
     * a failure here is logged, never propagated (journaling must not affect the build's outcome).
     */
    private void writeJournal(long requestId, boolean cancelled, long millis) {
        BuildAccumulator a = accumulators.remove(requestId);
        if (a == null) return;
        try {
            long finishedAt = clockMillis.getAsLong();
            BuildRecord record = a.toRecord(finishedAt, cancelled, millis, version);
            Path dir = Path.of(a.dir());
            // Snapshot paths mirror BuildLayout.markdownTestResults() and the project's jk.lock; each
            // is copied only if it exists at finish, so a skip-tests or lock-less build just omits it.
            BuildJournal.Snapshot snapshot = new BuildJournal.Snapshot(
                    dir.resolve("target").resolve("reports").resolve("test-results.md"),
                    dir.resolve("jk.lock"),
                    a.diagnosticsText());
            journal.append(record, snapshot);
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal append failed: " + e);
        }
    }

    /** {@code history-list-request} → one flat {@code history-entry} per entry, then {@code history-done}. */
    private void handleHistoryList(String requestLine, BufferedWriter writer) throws IOException {
        int limit = Math.max(1, Ndjson.intValue(requestLine, "limit", 200));
        java.util.List<BuildRecord> records = journal.list();
        int n = Math.min(records.size(), limit);
        for (int i = 0; i < n; i++) {
            BuildRecord r = records.get(i);
            BuildRecord.Tests t = r.tests();
            int failedModules = (int) r.modules().stream().filter(m -> !m.success()).count();
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_ENTRY)
                    .put("id", r.id())
                    .put("kind", r.kind())
                    .put("dir", r.dir())
                    .put("coord", r.coord())
                    .put("startedAt", r.startedAt())
                    .put("finishedAt", r.finishedAt())
                    .put("millis", r.millis())
                    .put("success", r.success())
                    .put("cancelled", r.cancelled())
                    .put("exitCode", r.exitCode())
                    .put("testsTotal", t != null ? t.total() : -1)
                    .put("testsFailed", t != null ? t.failed() : -1)
                    .put("moduleCount", r.modules().size())
                    .put("failedModules", failedModules)
                    .toString());
        }
        send(writer, JsonOut.object().put("t", EngineProtocol.HISTORY_DONE).put("count", n).toString());
    }

    /** {@code history-show-request} → a {@code history-record} header + module/phase/diag rows + {@code history-done}. */
    private void handleHistoryShow(String requestLine, BufferedWriter writer) throws IOException {
        String id = Ndjson.str(requestLine, "id");
        java.util.Optional<BuildRecord> found = id == null ? java.util.Optional.empty() : journal.get(id);
        if (found.isEmpty()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_ERROR)
                    .put("message", "no such build: " + id)
                    .toString());
            return;
        }
        BuildRecord r = found.get();
        BuildRecord.Tests t = r.tests();
        send(writer, JsonOut.object()
                .put("t", EngineProtocol.HISTORY_RECORD)
                .put("id", r.id())
                .put("kind", r.kind())
                .put("dir", r.dir())
                .put("coord", r.coord())
                .put("startedAt", r.startedAt())
                .put("finishedAt", r.finishedAt())
                .put("millis", r.millis())
                .put("success", r.success())
                .put("cancelled", r.cancelled())
                .put("exitCode", r.exitCode())
                .put("jkVersion", r.jkVersion())
                .put("testsTotal", t != null ? t.total() : -1)
                .put("testsSucceeded", t != null ? t.succeeded() : -1)
                .put("testsFailed", t != null ? t.failed() : -1)
                .put("testsSkipped", t != null ? t.skipped() : -1)
                .toString());
        int phaseCount = 0;
        for (BuildRecord.Module m : r.modules()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_MODULE)
                    .put("coord", m.coord())
                    .put("dir", m.dir())
                    .put("success", m.success())
                    .put("exitCode", m.exitCode())
                    .put("millis", m.millis())
                    .toString());
            // Each module's own phase chain, tagged with the module so the CLI can group them.
            String label = m.coord() != null ? m.coord() : m.dir();
            for (BuildRecord.Phase p : m.phases()) {
                send(writer, phaseLine(p, label));
                phaseCount++;
            }
        }
        // Single-goal builds carry their phases at the record's top level (no module rows).
        for (BuildRecord.Phase p : r.phases()) {
            send(writer, phaseLine(p, null));
            phaseCount++;
        }
        for (BuildRecord.Diag d : r.diagnostics()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_DIAG)
                    .put("severity", d.severity())
                    .put("phase", d.phase())
                    .put("code", d.code())
                    .put("message", d.message())
                    .put("test", d.test())
                    .put("exceptionClass", d.exceptionClass())
                    .toString());
        }
        send(writer, JsonOut.object()
                .put("t", EngineProtocol.HISTORY_DONE)
                .put("count", r.modules().size() + phaseCount + r.diagnostics().size())
                .toString());
    }

    /** A {@code history-phase} line, optionally tagged with its module label (null for single-goal). */
    private static String phaseLine(BuildRecord.Phase p, String module) {
        return JsonOut.object()
                .put("t", EngineProtocol.HISTORY_PHASE)
                .put("module", module)
                .put("name", p.name())
                .put("status", p.status())
                .put("millis", p.millis())
                .toString();
    }

    /** {@code history-delete-request} → {@code history-deleted} carrying whether the entry existed. */
    private void handleHistoryDelete(String requestLine, BufferedWriter writer) throws IOException {
        String id = Ndjson.str(requestLine, "id");
        boolean deleted = id != null && journal.delete(id);
        send(writer, JsonOut.object()
                .put("t", EngineProtocol.HISTORY_DELETED)
                .put("id", id)
                .put("deleted", deleted)
                .toString());
    }

    /**
     * Translate every {@link GoalListener} callback for one goal into a {@code dir}-tagged wire
     * event. {@code realGoal} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build phases)
     * ride along on the {@link EngineProtocol#GOAL_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build goal (where neither applies at the module level).
     */
    private GoalListener wireGoalListener(String dir, BufferedWriter writer, dev.jkbuild.run.Goal realGoal) {
        return wireGoalListener(dir, writer, (java.util.function.Function<GoalResult, String>) result -> {
            dev.jkbuild.run.TestSummary testResult = realGoal == null
                    ? null
                    : realGoal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null);
            String buildOutcome = realGoal == null
                    ? null
                    : realGoal.get(dev.jkbuild.runtime.BuildPipeline.BUILD_OUTCOME).orElse(null);
            return testResult == null && buildOutcome == null
                    ? EngineProtocol.goalFinish(dir, result.success())
                    : EngineProtocol.goalFinish(
                            dir,
                            result.success(),
                            buildOutcome,
                            testResult != null ? testResult.total() : -1,
                            testResult != null ? testResult.succeeded() : -1,
                            testResult != null ? testResult.failed() : -1,
                            testResult != null ? testResult.skipped() : -1);
        });
    }

    /**
     * As {@link #wireGoalListener(String, BufferedWriter, dev.jkbuild.run.Goal)}, but with a
     * pluggable terminal encoder: {@code finishEncoder} maps the finished {@link GoalResult} to the
     * {@link EngineProtocol#GOAL_FINISH} message to send (after the {@link
     * EngineProtocol#GOAL_DIAGNOSTIC} burst) — how lock/update/sync ride their summary counts on the
     * same message the build/test goals already send.
     */
    private GoalListener wireGoalListener(
            String dir, BufferedWriter writer, java.util.function.Function<GoalResult, String> finishEncoder) {
        // Created on the runner's thread (directly, or via wireListener's onModuleStart which runs
        // on a scheduler thread — there the ThreadLocal is unset and module events carry the id).
        long eventRequestId = eventRequestId();
        return new GoalListener() {
            @Override
            public void goalStart(GoalView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.goalStart(
                                dir,
                                view.goalName(),
                                view.numerator(),
                                view.denominator(),
                                view.phasesTotal(),
                                view.phasesComplete(),
                                view.cancelled()));
                publishGoalProgress(eventRequestId, dir, view);
            }

            @Override
            public void phaseStart(String phase, int scope) {
                sendQuiet(writer, EngineProtocol.phaseStart(dir, phase, scope));
                publishPhaseStart(eventRequestId, dir, phase);
            }

            @Override
            public void progress(String phase, int delta, GoalView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.progress(
                                dir,
                                phase,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.phasesTotal(),
                                view.phasesComplete(),
                                view.cancelled()));
                publishGoalProgress(eventRequestId, dir, view);
            }

            @Override
            public void scopeUpdate(String phase, int delta, GoalView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.scopeUpdate(
                                dir,
                                phase,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.phasesTotal(),
                                view.phasesComplete(),
                                view.cancelled()));
                publishGoalProgress(eventRequestId, dir, view);
            }

            @Override
            public void label(String phase, String label) {
                sendQuiet(writer, EngineProtocol.label(dir, phase, label));
            }

            @Override
            public void output(String phase, String line) {
                sendQuiet(writer, EngineProtocol.output(dir, phase, line));
                publishOutput(eventRequestId, dir, phase, line);
            }

            @Override
            public void warn(String phase, String code, String message) {
                sendQuiet(writer, EngineProtocol.warn(dir, phase, code, message));
            }

            @Override
            public void error(String phase, String code, String message, String test, String exceptionClass) {
                sendQuiet(writer, EngineProtocol.error(dir, phase, code, message, test, exceptionClass));
            }

            @Override
            public void phaseFinish(String phase, dev.jkbuild.run.PhaseStatus status, Duration duration) {
                sendQuiet(writer, EngineProtocol.phaseFinish(dir, phase, status.name()));
                publishPhaseFinish(eventRequestId, dir, phase, status.name());
                accPhaseFinish(eventRequestId, dir, phase, status.name(), duration.toMillis());
            }

            @Override
            public void goalFinish(GoalResult result) {
                for (GoalResult.Diagnostic d : result.errors()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.goalDiagnostic(
                                    dir, d.phase(), d.code(), d.message(), d.test(), d.exceptionClass()));
                }
                sendQuiet(writer, finishEncoder.apply(result));
                publishGoalFinish(eventRequestId, dir, result.success());
                if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                accGoalFinish(eventRequestId, dir, result);
            }
        };
    }

    /** Best-effort send: a write failure means the client is gone — nothing more to do for this event. */
    private static void sendQuiet(BufferedWriter writer, String line) {
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // the cancel-watching read loop will notice the same disconnect and cancel the build
        }
    }

    private void onConnectionFinished() {
        synchronized (lifecycleLock) {
            int remaining = activeConnections.decrementAndGet();
            lastActivityAtMillis = clockMillis.getAsLong();
            // httpConfig == null: [http] overrides idle-minutes = 0 too — never self-terminate.
            if (remaining == 0 && hadActivity && httpConfig == null && config.exitAsSoonAsIdle() && !shuttingDown) {
                shuttingDown = true;
                closeServerChannelQuietly();
            }
        }
    }

    private void startIdleTickerIfNeeded() {
        // [http] enabled: never self-terminate, overriding any idle-minutes (docs/http.md) — the
        // dashboard is served by this process and only a CLI invocation can respawn one, so a web
        // client that works until the engine idles out would be an astonishing experience.
        if (httpConfig != null) return;
        if (config.neverExpires()) return; // idle-minutes = -1: only an explicit stop/kill ends this engine
        if (config.exitAsSoonAsIdle()) return; // idle-minutes = 0: handled inline in onConnectionFinished
        long idleMillis = config.idleMinutes() * 60_000L;
        idleTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-engine-idle-ticker");
            t.setDaemon(true);
            return t;
        });
        idleTicker.scheduleAtFixedRate(
                () -> {
                    try {
                        checkIdleTimeout(idleMillis);
                    } catch (RuntimeException ignored) {
                        // never let a tick failure kill the ticker
                    }
                },
                tickMillis,
                tickMillis,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Start the embedded HTTP server when the {@code [http]} table is present — advisory, never
     * load-bearing: a bind failure (port already claimed, unusable host) is logged, remembered for
     * {@code jk engine status}, and the engine serves builds without HTTP. See {@code docs/http.md}.
     */
    private void startHttpIfEnabled() {
        if (httpConfig == null) return;
        HttpEngineServer candidate = new HttpEngineServer(
                httpConfig,
                httpConfig.wwwRootPath(),
                paths.httpToken(),
                paths.log(),
                version,
                this::statusSnapshot,
                httpEvents,
                this::triggerHttpBuild,
                journal,
                log);
        try {
            candidate.start();
            Files.writeString(paths.http(), candidate.url());
            httpServer = candidate;
            log.accept("jk engine: http listening on " + candidate.url());
        } catch (IOException | RuntimeException e) {
            candidate.close();
            httpError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.accept("jk engine: http failed to start (" + httpError + ") — continuing without http");
        }
    }

    /**
     * {@code POST /api/build}'s trigger ({@code docs/http.md}): run a build of {@code dirStr} on
     * the same {@link BuildService#buildWorkspace} path the socket protocol uses, with the same
     * pipeline discipline ({@link #activePipelines}, {@link #cacheGate} read side, idle-boundary
     * GC), acknowledged immediately with a request id. There is no wire connection — progress goes
     * only to the dashboard event hub, and the terminal {@code request-finish} carries {@code
     * success} (the socket-request variant can't; its outcome is encoded in wire messages).
     */
    private long triggerHttpBuild(String dirStr) {
        Path entryDir = Path.of(dirStr);
        if (!entryDir.isAbsolute()) {
            throw new IllegalArgumentException("dir must be an absolute path");
        }
        if (!Files.isRegularFile(entryDir.resolve("jk.toml"))) {
            throw new IllegalArgumentException("no jk.toml in " + entryDir);
        }
        long eventRequestId = requestIds.incrementAndGet();
        long startMillis = clockMillis.getAsLong();
        publishRequestStart(eventRequestId, "build", entryDir.toString());
        registerAccumulator(eventRequestId, "build", entryDir.toString());
        activePipelines.incrementAndGet();
        Thread.ofVirtual().name("jk-engine-http-build-", 0).start(() -> {
            cacheGate.readLock().lock();
            currentEventRequestId.set(eventRequestId);
            boolean success = false;
            try {
                success = runHttpBuild(entryDir);
            } finally {
                currentEventRequestId.remove();
                cacheGate.readLock().unlock();
                maybeIdleBoundaryGc();
                long elapsedMillis = clockMillis.getAsLong() - startMillis;
                publishEvent(
                        "request-finish",
                        dev.jkbuild.engine.http.JsonOut.object()
                                .put("requestId", eventRequestId)
                                .put("kind", "build")
                                .put("dir", entryDir.toString())
                                .put("success", success)
                                .put("cancelled", false)
                                .put("millis", elapsedMillis));
                writeJournal(eventRequestId, false, elapsedMillis);
            }
        });
        return eventRequestId;
    }

    /** The build body of {@link #triggerHttpBuild} — {@link #runBuild} with defaults, hub-only events. */
    private boolean runHttpBuild(Path entryDir) {
        try {
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            Path cache = dev.jkbuild.util.JkDirs.cache();
            Path jdksDir = dev.jkbuild.util.JkDirs.jdks();
            WorkspaceRequest req = new WorkspaceRequest(
                    entryDir,
                    entryBuild,
                    cache,
                    jdksDir,
                    Runtime.getRuntime().availableProcessors(), // the shared plan's own worst-case cap
                    null,
                    false,
                    false,
                    0,
                    null, // let the engine forecast dirty modules itself — see docs/engine.md
                    false, // this engine plans memory once at startup, not per request
                    true); // auto-freshen a stale lock, like jk build
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir);
            WorkspaceResult result =
                    SessionContext.where(session, () -> BuildService.buildWorkspace(req, hubListener()));
            accOutcome(eventRequestId(), result.success(), result.exitCode());
            if (!result.success()) {
                for (String error : result.errors().stream().limit(5).toList()) {
                    publishRequestError(eventRequestId(), entryDir.toString(), error);
                }
            }
            return result.success();
        } catch (Exception e) {
            log.accept("jk engine: http-triggered build of " + entryDir + " failed: " + e.getMessage());
            publishRequestError(eventRequestId(), entryDir.toString(), String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** Module/goal events to the dashboard hub only — the HTTP trigger's counterpart of {@link #wireListener}. */
    private WorkspaceBuildListener hubListener() {
        long eventRequestId = eventRequestId();
        return new WorkspaceBuildListener() {
            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                publishPlan(eventRequestId, plan.stream().mapToLong(ModulePlan::weight).sum());
            }

            @Override
            public void onEtaEstimate(long millis) {
                publishEta(eventRequestId, millis);
            }

            @Override
            public GoalListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                publishModuleStart(eventRequestId, dir, m.coord());
                return new GoalListener() {
                    @Override
                    public void goalStart(GoalView view) {
                        publishGoalProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void progress(String phase, int delta, GoalView view) {
                        publishGoalProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void scopeUpdate(String phase, int delta, GoalView view) {
                        publishGoalProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void phaseStart(String phase, int scope) {
                        publishPhaseStart(eventRequestId, dir, phase);
                    }

                    @Override
                    public void phaseFinish(String phase, dev.jkbuild.run.PhaseStatus status, Duration duration) {
                        publishPhaseFinish(eventRequestId, dir, phase, status.name());
                        accPhaseFinish(eventRequestId, dir, phase, status.name(), duration.toMillis());
                    }

                    @Override
                    public void output(String phase, String line) {
                        publishOutput(eventRequestId, dir, phase, line);
                    }

                    @Override
                    public void goalFinish(GoalResult result) {
                        publishGoalFinish(eventRequestId, dir, result.success());
                        if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                        accGoalFinish(eventRequestId, dir, result);
                    }
                };
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                publishModuleFinish(eventRequestId, o.dir().toString(), o.coord(), o.success(), o.millis());
                accModule(eventRequestId, o);
            }
        };
    }

    /** The one source of engine vitals — feeds both the socket {@code status-ack} and {@code /api/status}. */
    private dev.jkbuild.engine.http.StatusSnapshot statusSnapshot() {
        Runtime rt = Runtime.getRuntime();
        long heapCommitted = rt.totalMemory();
        return new dev.jkbuild.engine.http.StatusSnapshot(
                version,
                pid,
                startedAtMillis,
                config.idleMinutes(),
                activeConnections.get(),
                activePipelines.get(),
                heapCommitted - rt.freeMemory(),
                heapCommitted,
                rt.maxMemory(),
                MemoryProbe.ownRssBytes());
    }

    private void checkIdleTimeout(long idleMillis) {
        synchronized (lifecycleLock) {
            if (shuttingDown || activeConnections.get() != 0) return;
            if (clockMillis.getAsLong() - lastActivityAtMillis >= idleMillis) {
                shuttingDown = true;
                closeServerChannelQuietly();
            }
        }
    }

    /** Caller-facing graceful stop — same effect as receiving a {@link EngineProtocol#SHUTDOWN} message. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            shuttingDown = true;
            closeServerChannelQuietly();
        }
    }

    private void closeServerChannelQuietly() {
        // Must be called while holding lifecycleLock: unblocks acceptLoop() (accept() throws
        // ClosedChannelException) without racing a connection being registered concurrently.
        try {
            if (serverChannel != null) serverChannel.close();
        } catch (IOException ignored) {
            // already closing
        }
    }

    private void cleanup() {
        if (httpServer != null) httpServer.close();
        deleteQuietly(paths.http());
        deleteQuietly(paths.httpToken());
        if (idleTicker != null) idleTicker.shutdownNow();
        if (connectionExecutor != null) connectionExecutor.shutdown();
        try {
            if (connectionExecutor != null) connectionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteQuietly(paths.socket());
        deleteQuietly(paths.token());
        deleteQuietly(paths.pid());
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // process exit releases it regardless
        }
        deleteQuietly(paths.lock());
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup — a leftover file is harmless (recreated/overwritten next start)
        }
    }

    private void writePidFile() throws IOException {
        Files.writeString(paths.pid(), pid + "\n" + startedAtMillis + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Size the worker-JVM memory plan once, for the whole engine process, against a coarse
     * assumed-worst-case concurrency (the host's core count) — not once per build request. Every
     * engine-hosted {@code buildWorkspace} call passes {@code applyMemoryPlan=false} so it doesn't
     * overwrite this shared plan out from under a sibling request that's already forking workers
     * under it (see {@code docs/engine.md} and {@code BuildService.buildWorkspace}'s javadoc). This
     * trades today's single-invocation "burst to use all spare RAM when building alone" precision
     * for correctness under concurrency — the actual bug this plan exists to fix. A more precise
     * demand-registry (each in-flight request contributing its own share) is a documented, deferred
     * follow-up, not required for this to be correct.
     */
    private void planSharedWorkerMemoryOnce() {
        int cap = Runtime.getRuntime().availableProcessors();
        JvmOptions.planAndApply(HeapPlan.requestedJvms(cap, 1, false, cap));
    }

    private static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    private static void closeQuietly(SocketChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Thread-safe collector of one build's outcome, folded from {@link WorkspaceBuildListener}/{@link
     * GoalListener} callbacks that fire on scheduler/worker threads, then frozen into a {@link
     * BuildRecord} at request-finish. Success is taken from the runner's terminal result when set,
     * else derived (no failed module/goal and not cancelled).
     */
    private static final class BuildAccumulator {
        private final String kind;
        private final String dir;
        private final String coord;
        private final java.util.List<ModuleOutcome> modules = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Phases per module dir (name → Phase, arrival order, last status wins). The single-goal path
        // uses the "" (SINGLE_GOAL_DIR) bucket; workspace modules use their real dir. Rendered as a
        // chain per module (the dashboard shows one chain per module, not one merged strip).
        private final java.util.Map<String, java.util.Map<String, BuildRecord.Phase>> phasesByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.List<BuildRecord.Diag> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile BuildRecord.Tests tests;
        private volatile boolean anyFailure;
        private volatile boolean userCancelled;
        private volatile Boolean success;
        private volatile int exitCode;

        BuildAccumulator(String kind, String dir, String coord) {
            this.kind = kind;
            this.dir = dir;
            this.coord = coord;
        }

        String dir() {
            return dir;
        }

        /** True only when the runner explicitly reported success (not merely "no failure seen yet"). */
        boolean succeeded() {
            return Boolean.TRUE.equals(success);
        }

        /** Genuine cancellation, from the runner's own signal (not the racy end-of-request EOF). */
        boolean wasCancelled() {
            return userCancelled;
        }

        void addModule(ModuleOutcome o) {
            modules.add(o);
            if (!o.success()) anyFailure = true;
        }

        /** One finished phase, stored under its module dir ("" for a single-goal build). */
        void addPhase(String dir, String phase, String status, long millis) {
            phasesByDir
                    .computeIfAbsent(dir == null ? "" : dir,
                            k -> java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>()))
                    .put(phase, new BuildRecord.Phase(phase, status, millis));
        }

        /** Diagnostics + failure flag from a finished goal (phases come from {@link #addPhase}). */
        void addGoal(String dir, GoalResult result) {
            String d0 = dir == null ? "" : dir;
            for (GoalResult.Diagnostic d : result.errors()) {
                diagnostics.add(new BuildRecord.Diag(
                        "error", d0, d.phase(), d.code(), d.message(), d.test(), d.exceptionClass()));
            }
            for (GoalResult.Diagnostic d : result.warnings()) {
                diagnostics.add(new BuildRecord.Diag(
                        "warning", d0, d.phase(), d.code(), d.message(), d.test(), d.exceptionClass()));
            }
            if (!result.success()) anyFailure = true;
            if (result.userCancelled()) userCancelled = true;
        }

        private java.util.List<BuildRecord.Phase> phasesFor(String dir) {
            java.util.Map<String, BuildRecord.Phase> m = phasesByDir.get(dir == null ? "" : dir);
            if (m == null) return java.util.List.of();
            synchronized (m) {
                return new java.util.ArrayList<>(m.values());
            }
        }

        void setTests(TestSummary t) {
            tests = new BuildRecord.Tests(t.total(), t.succeeded(), t.failed(), t.skipped());
        }

        void setOutcome(boolean ok, int exit) {
            this.success = ok;
            this.exitCode = exit;
            if (!ok) anyFailure = true;
        }

        String diagnosticsText() {
            if (diagnostics.isEmpty()) return null;
            StringBuilder b = new StringBuilder();
            for (BuildRecord.Diag d : diagnostics) {
                b.append('[').append(d.severity()).append("] ");
                if (notBlank(d.phase())) b.append(d.phase()).append(": ");
                if (notBlank(d.test())) b.append(d.test()).append(" — ");
                if (notBlank(d.exceptionClass())) b.append('(').append(d.exceptionClass()).append(") ");
                b.append(d.message() == null ? "" : d.message()).append('\n');
            }
            return b.toString();
        }

        BuildRecord toRecord(long finishedAt, boolean cancelled, long millis, String jkVersion) {
            boolean ok = success != null ? success : (!anyFailure && !cancelled);
            int exit = success != null ? exitCode : (ok ? 0 : 1);
            // A build that reported success was not cancelled: cancelToken.cancelled() also fires on
            // the benign end-of-request EOF (the client closes the socket the instant it reads the
            // terminal message, which can land just before the runner marks itself done), so trust
            // the outcome over that flag and never label a successful run "cancelled".
            boolean cancelledEffective = cancelled && !ok;
            // Each workspace module carries its own phase chain (keyed by its dir); a single-goal
            // build has no module rows, so its phases live in the record's top-level list (the ""
            // bucket). This is exactly the two shapes the dashboard renders (per-module vs compact).
            java.util.List<BuildRecord.Module> moduleList = new java.util.ArrayList<>();
            for (ModuleOutcome o : modules) {
                String mdir = o.dir() == null ? "" : o.dir().toString();
                moduleList.add(new BuildRecord.Module(
                        o.coord(), mdir, o.success(), o.exitCode(), o.millis(), phasesFor(mdir)));
            }
            java.util.List<BuildRecord.Phase> topPhases = moduleList.isEmpty() ? phasesFor("") : java.util.List.of();
            return new BuildRecord(
                    null, BuildRecord.SCHEMA, kind, dir, coord,
                    finishedAt - millis, finishedAt, millis,
                    ok, cancelledEffective, exit, jkVersion,
                    tests, moduleList, topPhases, new java.util.ArrayList<>(diagnostics));
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
