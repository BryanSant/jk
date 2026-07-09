// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.config.Session;
import dev.jkbuild.config.SessionContext;
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

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link EngineTransport}. */
    private String expectedToken;

    public EngineServer(EnginePaths.Paths paths, JkEngineConfig config, String version, Consumer<String> log) {
        this(paths, config, version, log, DEFAULT_TICK_MILLIS, System::currentTimeMillis);
    }

    /** Test seam: a short tick interval and an injectable clock, so idle-timeout tests don't sleep for real minutes. */
    EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            String version,
            Consumer<String> log,
            long tickMillis,
            LongSupplier clockMillis) {
        this.paths = paths;
        this.config = config;
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
                        Runtime rt = Runtime.getRuntime();
                        long heapCommitted = rt.totalMemory();
                        send(
                                writer,
                                EngineProtocol.statusAck(
                                        version,
                                        pid,
                                        startedAtMillis,
                                        config.idleMinutes(),
                                        activeConnections.get(),
                                        heapCommitted - rt.freeMemory(),
                                        heapCommitted,
                                        rt.maxMemory(),
                                        MemoryProbe.ownRssBytes()));
                    }
                    case EngineProtocol.SHUTDOWN -> {
                        send(writer, EngineProtocol.bye());
                        synchronized (lifecycleLock) {
                            shuttingDown = true;
                            closeServerChannelQuietly();
                        }
                        return;
                    }
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
        if (pipeline) activePipelines.incrementAndGet();
        try {
            Thread.ofVirtual().name(threadPrefix, 0).start(() -> {
                if (pipeline) cacheGate.readLock().lock();
                try {
                    runner.run(requestLine, cancelToken, writer);
                } finally {
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
        }
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
                    .withCancel(cancelToken);

            WorkspaceBuildListener listener = wireListener(writer);
            WorkspaceResult result =
                    SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            send(writer, EngineProtocol.workspaceFinish(result.success(), result.exitCode(), result.errors()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.buildError(String.valueOf(e.getMessage())));
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
                    .withCancel(cancelToken);

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
                    .withCancel(cancelToken);

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
                    .withCancel(cancelToken);
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
            dev.jkbuild.run.Goal goal =
                    switch (mode) {
                        case "java" -> dev.jkbuild.runtime.ScriptGoals.javaScriptGoal(
                                script, cache, stateDir, repoUrl, forceRecompile);
                        case "kt" -> dev.jkbuild.runtime.ScriptGoals.kotlinScriptGoal(
                                script, cache, stateDir, repoUrl, forceRecompile);
                        case "kts" -> dev.jkbuild.runtime.ScriptGoals.ktsScriptGoal(script, cache, repoUrl);
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
                .withCancel(cancelToken);
    }

    /** The optional {@code repoUrl} request field ({@code --repo-url} overrides), or {@code null}. */
    private static java.net.URI repoUrlOf(String requestLine) {
        String s = Ndjson.str(requestLine, "repoUrl");
        return s != null ? java.net.URI.create(s) : null;
    }

    /** Translate every {@link WorkspaceBuildListener} callback into a wire event on {@code writer}. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer) {
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
            }

            @Override
            public void onEtaEstimate(long millis) {
                sendQuiet(writer, EngineProtocol.eta(millis));
            }

            @Override
            public GoalListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                sendQuiet(writer, EngineProtocol.moduleStart(dir));
                return wireGoalListener(dir, writer, (dev.jkbuild.run.Goal) null);
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                sendQuiet(
                        writer,
                        EngineProtocol.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.exitCode(), o.millis()));
            }
        };
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
            }

            @Override
            public void phaseStart(String phase, int scope) {
                sendQuiet(writer, EngineProtocol.phaseStart(dir, phase, scope));
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
            }

            @Override
            public void label(String phase, String label) {
                sendQuiet(writer, EngineProtocol.label(dir, phase, label));
            }

            @Override
            public void output(String phase, String line) {
                sendQuiet(writer, EngineProtocol.output(dir, phase, line));
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
            if (remaining == 0 && hadActivity && config.exitAsSoonAsIdle() && !shuttingDown) {
                shuttingDown = true;
                closeServerChannelQuietly();
            }
        }
    }

    private void startIdleTickerIfNeeded() {
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
}
