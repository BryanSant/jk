// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine;

import build.jumpkick.config.JkBuildParser;
import build.jumpkick.config.JkConfig;
import build.jumpkick.config.JkEngineConfig;
import build.jumpkick.config.JkHistoryConfig;
import build.jumpkick.config.JkHttpConfig;
import build.jumpkick.config.Session;
import build.jumpkick.config.SessionContext;
import build.jumpkick.engine.http.HttpEngineServer;
import build.jumpkick.engine.http.JsonOut;
import build.jumpkick.engine.journal.BuildJournal;
import build.jumpkick.engine.journal.BuildRecord;
import build.jumpkick.engine.protocol.EngineProtocol;
import build.jumpkick.model.JkBuild;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.Step;
import build.jumpkick.run.TestSummary;
import build.jumpkick.runtime.BuildMetrics;
import build.jumpkick.runtime.BuildPlan;
import build.jumpkick.runtime.BuildService;
import build.jumpkick.runtime.ExplainPlan;
import build.jumpkick.runtime.ModuleOutcome;
import build.jumpkick.runtime.ModulePlan;
import build.jumpkick.runtime.WorkspaceBuildListener;
import build.jumpkick.runtime.WorkspaceRequest;
import build.jumpkick.runtime.WorkspaceResult;
import build.jumpkick.engine.plugin.HeapPlan;
import build.jumpkick.engine.plugin.JvmOptions;
import build.jumpkick.engine.plugin.MemoryProbe;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The engine's server loop: single-instance election, socket bind, and an accept loop that serves
 * the handshake/liveness/status/shutdown protocol on a virtual thread per connection. The engine is
 * resident — it runs until an explicit stop (or drain), never self-terminating. See {@code
 * docs/engine.md}.
 *
 * <p>Hosts the real engine operations: workspace builds ({@code buildWorkspace} dispatch), single
 * project builds and tests, and explain forecasts — each request served on its own connection in
 * its own {@link Session}, with pipeline events streamed back over the wire protocol.
 */
public final class EngineServer implements AutoCloseable {


    private final EnginePaths.Paths paths;
    private final JkEngineConfig config;

    /**
     * The {@code [http]} table when present, else {@code null} — the embedded HTTP server's enable
     * switch. The dashboard it serves is why the engine stays resident until an explicit stop.
     */
    private final JkHttpConfig httpConfig;

    private final String version;
    /** Content identity for -SNAPSHOT builds (see BuildIdentity); "" = version rule only. */
    private final String buildId;
    private final Consumer<String> log;
    private final LongSupplier clockMillis;
    private final long pid;
    private final long startedAtMillis;

    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * Sidecar AOT trainer ({@code docs/engine.md}): a factory the spawner installs before {@code
     * run()} when a cache should be recorded, and the live child while it runs. THIS engine owns
     * the trainer's whole life — spawned only after winning the election (a losing redundant spawn
     * never trains), tracked for {@code status-ack}, reaped on exit. Clients never talk to it.
     */
    private volatile java.util.function.Supplier<Process> aotTrainerSpawner;

    private volatile Process aotTrainer;
    private final AtomicInteger activePipelines = new AtomicInteger();

    /**
     * Dashboard event plumbing ({@code docs/http.md}) — non-null exactly when {@link #httpConfig}
     * is. Every hosted request publishes coarse lifecycle events through {@link #publishEvent};
     * with no SSE subscriber connected that's one {@code hasSubscribers} check and nothing else.
     */
    private final build.jumpkick.engine.http.HttpEvents httpEvents;

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

    /** The running invocation/step aggregates every finished build/test folds into. */
    private Path metricsFile = BuildMetrics.defaultFile();

    /** Test seam: point the metrics store at a sandbox file instead of the user's real state dir. */
    void metricsFileForTests(Path file) {
        this.metricsFile = file;
    }

    /**
     * The event-request id of the hosted operation this thread is running, set around {@code
     * runner.run} by {@link #handleAsyncPipelineRequest} — how {@link #wireListener}/{@link
     * #wirePipelineListener} tag their module/pipeline events with the right request without threading an
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
    // Graceful-drain pre-state: the listener stays open and quick commands (hello/ping/status) keep
    // answering, but new jobs are refused and the engine exits cleanly once in-flight jobs finish.
    private volatile boolean draining;

    /** One-shot child mode ({@code --job}): no election, no endpoint, no re-delegation. */
    private volatile boolean jobMode;

    private FileChannel lockChannel;
    private FileLock lock;
    /** The generation this engine bound (socket/lock/pid/token) — see EnginePaths.generation. */
    private EnginePaths.Paths active;
    private FileLock genLock;
    private FileChannel genLockChannel;
    private ServerSocketChannel serverChannel;
    private ExecutorService connectionExecutor;

    /** Non-null once the embedded HTTP server is up; stays null when disabled or bind failed. */
    private HttpEngineServer httpServer;

    /** Non-null when {@code [http]} is enabled but the server failed to start — surfaced in status. */
    private volatile String httpError;

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link EngineTransport}. */
    private String expectedToken;

    public EngineServer(EnginePaths.Paths paths, JkEngineConfig config, String version, Consumer<String> log) {
        this(paths, config, null, version, build.jumpkick.model.BuildIdentity.buildId(), log);
    }

    /** As above plus the optional {@code [http]} table ({@code null} = feature off). */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            Consumer<String> log) {
        this(paths, config, httpConfig, version, build.jumpkick.model.BuildIdentity.buildId(), log);
    }

    /**
     * Canonical: {@code buildId} is the engine's content identity ({@code BuildIdentity} —
     * derived from its own jar; injectable for election tests). Empty means "no opinion": the
     * same-version election then falls back to the version-string rule, exactly the release
     * behavior. For -SNAPSHOT dev builds it distinguishes a REBUILT engine from a stale one.
     */
    public EngineServer(
            EnginePaths.Paths paths,
            JkEngineConfig config,
            JkHttpConfig httpConfig,
            String version,
            String buildId,
            Consumer<String> log) {
        this.paths = paths;
        this.config = config;
        this.httpConfig = httpConfig;
        this.httpEvents = httpConfig != null ? new build.jumpkick.engine.http.HttpEvents() : null;
        this.version = version;
        this.buildId = buildId == null ? "" : buildId;
        this.log = log != null ? log : s -> {};
        this.clockMillis = System::currentTimeMillis;
        this.pid = ProcessHandle.current().pid();
        this.startedAtMillis = clockMillis.getAsLong();
    }

    /**
     * Try to become the engine and serve until shutdown. Returns {@code false} immediately, having
     * touched nothing but the lock file, if another engine already holds {@link
     * EnginePaths.Paths#lock()} — the caller (a losing spawn-race participant) should treat that as
     * success-by-proxy, not an error. Blocks until the server stops (an explicit {@link
     * EngineProtocol#SHUTDOWN} — {@code jk engine stop} — or {@link #close()}), then returns
     * {@code true}; there is no idle countdown — the engine stays resident until told to stop.
     */
    public boolean run() throws IOException {
        Files.createDirectories(paths.dir());
        // Startup mutex only (engine-versioning-plan §2): serializes concurrent spawns/takeovers
        // through bind + endpoint write; ownership of a RUNNING engine is the generation lock.
        lockChannel = FileChannel.open(paths.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            lockChannel = null;
            return false; // another engine is mid-startup — it wins this race
        }

        // Where clients currently connect — the engine this one displaces (drained below).
        // Captured BEFORE we bind, and null when nothing was live: once the compat pointer is
        // written the flat path names US, and a drain aimed there is a self-shutdown. (That
        // self-drain shipped for a while, masked only by accidents — on TCP the old raw-token
        // auth failed, on Unix the closed drain connection made the bye() reply throw before
        // shuttingDown was set. See EngineTcpTransportTest.)
        Path previousActive = EnginePaths.activeSocket(paths);
        if (!Files.exists(previousActive)) previousActive = null;

        // Same-version election: if a live engine of THIS version AND build identity already
        // serves, this instance is a redundant spawn-race participant — lose quietly. A different
        // version — or the same -SNAPSHOT version with a DIFFERENT buildId (a rebuilt dev
        // engine; stale incumbents once won these elections and served old code) — proceeds to
        // takeover. An empty buildId on either side means "no opinion": version rule only.
        Incumbent incumbent = helloProbe(previousActive, version);
        if (incumbent != null
                && version.equals(incumbent.version())
                && (buildId.isEmpty() || incumbent.buildId().isEmpty() || buildId.equals(incumbent.buildId()))) {
            releaseStartupLock();
            return false;
        }

        // Claim the first free generation. The winner's gen lock is held for the engine's whole
        // life; a crashed engine's stale gen files are reclaimed here by winning its lock.
        for (int n = 1; n < 10_000 && active == null; n++) {
            EnginePaths.Paths cand = EnginePaths.generation(paths, n);
            FileChannel gc = FileChannel.open(cand.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock gl;
            try {
                gl = gc.tryLock();
            } catch (OverlappingFileLockException e) {
                gl = null;
            }
            if (gl != null) {
                active = cand;
                genLock = gl;
                genLockChannel = gc;
            } else {
                gc.close();
            }
        }
        if (active == null) {
            releaseStartupLock();
            return false;
        }

        // Stale files from a crashed prior owner of this generation.
        Files.deleteIfExists(active.socket());
        Files.deleteIfExists(active.token());

        if (EngineTransport.useLoopbackTcp()) {
            // Windows: no dependable Unix-domain-socket support — bind an ephemeral loopback TCP
            // port instead, and gate every connection on a shared secret (see EngineTransport),
            // since a TCP port (unlike a socket file) isn't filesystem-permission-gated by default.
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int port = ((java.net.InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            expectedToken = EngineTransport.newToken();
            Files.writeString(active.token(), expectedToken);
            Files.writeString(active.socket(), Integer.toString(port));
        } else {
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(active.socket()));
        }
        writePidFile();

        // TAKEOVER: from this write on, every new connection resolves to this generation.
        EnginePaths.writeEndpoint(paths, active.socket());
        releaseStartupLock();

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-conn-", 0).factory());
        startHttpIfEnabled();
        planSharedWorkerMemoryOnce();

        log.accept("jk engine: listening on " + active.socket() + " (pid " + pid + ")");
        startAotTrainerIfConfigured();
        drainDisplaced(previousActive);
        startDisplacementWatchdog();
        acceptLoop();
        cleanup();
        log.accept("jk engine: stopped");
        return true;
    }

    private void releaseStartupLock() {
        try {
            if (lock != null) lock.release();
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // best-effort; process exit releases it regardless
        }
        lock = null;
        lockChannel = null;
    }

    /**
     * Graceful drain of the engine this one displaced (engine-versioning-plan §2 step 3): a plain
     * {@code shutdown force=false} — it finishes in-flight jobs as a lame duck and exits at idle.
     */
    private void drainDisplaced(Path previousActive) {
        if (previousActive == null || previousActive.equals(active.socket())) return;
        if (!Files.exists(previousActive)) return;
        if (namesSelf(previousActive)) return; // a stale flat pointer we just re-claimed — never self-drain
        try (SocketChannel ch = openClient(previousActive)) {
            java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.shutdown(false));
            w.write('\n');
            w.flush();
            log.accept("jk engine: drained displaced engine at " + previousActive.getFileName());
        } catch (IOException e) {
            // Nothing live there (stale file) — fine; the watchdog on the other side also covers us.
        }
    }

    /**
     * True when {@code candidate} resolves to THIS engine's own listener — the flat compat
     * pointer after we've re-claimed a crashed generation's name (Unix symlink → our gen socket;
     * TCP → a copy of our own port). Drain/probe traffic must never target it.
     */
    private boolean namesSelf(Path candidate) {
        try {
            if (EngineTransport.useLoopbackTcp()) {
                return Files.readString(candidate)
                        .trim()
                        .equals(Files.readString(active.socket()).trim());
            }
            return candidate.toRealPath().equals(active.socket().toRealPath());
        } catch (IOException e) {
            return false; // unreadable/vanished — the connect attempt sorts it out
        }
    }

    /** A live engine's identity as answered on the wire. */
    private record Incumbent(String version, String buildId) {}

    /** The identity a live engine at {@code socket} answers with, or {@code null}. */
    private static Incumbent helloProbe(Path socket, String probeVersion) {
        if (socket == null || !Files.exists(socket)) return null;
        try (SocketChannel ch = openClient(socket)) {
            java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            java.nio.channels.Channels.newInputStream(ch), StandardCharsets.UTF_8));
            w.write(EngineProtocol.hello(probeVersion, "probe"));
            w.write('\n');
            w.flush();
            String ack = r.readLine();
            if (ack == null || !EngineProtocol.HELLO_ACK.equals(EngineProtocol.typeOf(ack))) return null;
            String v = build.jumpkick.plugin.protocol.Ndjson.str(ack, "version");
            if (v == null) return null;
            String id = build.jumpkick.plugin.protocol.Ndjson.str(ack, "buildId");
            return new Incumbent(v, id == null ? "" : id);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Minimal client connect for engine→engine signalling (token-gated on the TCP transport). */
    private static SocketChannel openClient(Path socket) throws IOException {
        if (EngineTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            Path token = EnginePaths.tokenFor(socket);
            java.io.BufferedWriter w = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            java.nio.channels.Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            // The auth envelope, exactly as the CLI client sends it — authenticate() accepts
            // nothing else (a raw token line here once broke takeover/election on TCP).
            w.write(EngineProtocol.auth(Files.readString(token).trim()));
            w.write('\n');
            w.flush();
            return ch;
        }
        return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    }

    /**
     * Displacement watchdog: when the endpoint stops naming this generation (a newer engine took
     * over and its drain signal was lost), self-drain — belt and suspenders for §2 step 3.
     */
    private void startDisplacementWatchdog() {
        Thread t = new Thread(() -> {
            String mine = active.socket().getFileName().toString();
            while (!shuttingDown) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Path ep = EnginePaths.endpoint(paths);
                    if (Files.isRegularFile(ep) && !mine.equals(Files.readString(ep).trim())) {
                        log.accept("jk engine: displaced by a newer generation — draining");
                        synchronized (lifecycleLock) {
                            if (activePipelines.get() == 0) {
                                shuttingDown = true;
                                closeServerChannelQuietly();
                            } else {
                                draining = true;
                            }
                        }
                        return;
                    }
                } catch (IOException ignored) {
                    // transient read failure — check again next tick
                }
            }
        }, "jk-engine-displacement-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (!shuttingDown) {
            SocketChannel ch;
            try {
                ch = serverChannel.accept();
            } catch (ClosedChannelException e) {
                break; // close() / drain-complete / shutdown message closed the listener
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
            }
            connectionExecutor.execute(() -> handleConnection(ch));
        }
    }

    /** Loopback-TCP transport only: the connection's first line must be a matching {@link EngineProtocol#AUTH}. */
    private boolean authenticate(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null || !EngineProtocol.AUTH.equals(EngineProtocol.typeOf(line))) return false;
        String presented = Ndjson.str(line, "token");
        if (presented == null) return false;
        return java.security.MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void handleConnection(SocketChannel ch) {
        try (ch;
                BufferedReader reader = new build.jumpkick.plugin.protocol.BoundedLineReader(
                        new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                BufferedWriter writer =
                        new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8))) {
            if (expectedToken != null && !authenticate(reader)) {
                // Typed refusal (then close): a silent close is indistinguishable from a crash.
                sendQuiet(writer, EngineProtocol.error(EngineProtocol.ERR_AUTH, "engine token rejected"));
                return;
            }
            serveConnection(reader, writer);
        } catch (IOException ignored) {
            // client disconnected / socket error mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    /**
     * One-shot job mode (engine-versioning-plan §3 / P3): serve one connection's worth of
     * requests over the given streams and return — {@code jk-engine --job}. This is how a NEWER
     * daemon runs a build pinned to THIS (older) version: it execs this engine as a child over
     * stdio, forwards the original request line, and relays the event stream verbatim. No socket,
     * no daemonization, no election — the worker pattern.
     */
    public void serveJob(BufferedReader reader, BufferedWriter writer) {
        jobMode = true;
        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-engine-job-", 0).factory());
        planSharedWorkerMemoryOnce();
        activeConnections.incrementAndGet();
        try {
            serveConnection(reader, writer);
        } catch (IOException ignored) {
            // parent disconnected mid-exchange — nothing to do
        } finally {
            onConnectionFinished();
        }
    }

    private void serveConnection(BufferedReader reader, BufferedWriter writer) throws IOException {
        {
            String line;
            while ((line = reader.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (type == null) {
                    // A garbled REQUEST gets a typed refusal, never silence — a silently-dropped
                    // request wedges a streaming client that is waiting for a terminal event.
                    sendQuiet(writer, EngineProtocol.error(EngineProtocol.ERR_PROTOCOL,
                            "unparseable request line (no \"t\" discriminator)"));
                    continue;
                }
                // Downward-delegation gate for artifact-producing requests (engine-versioning §3).
                if (DELEGATABLE.contains(type) && maybeDelegate(line, reader, writer)) {
                    return; // served by the pinned version's child engine (see EngineDelegate)
                }
                switch (type) {
                    case EngineProtocol.HELLO -> {
                        int clientProto = Ndjson.intValue(line, "proto", EngineProtocol.PROTOCOL);
                        if (clientProto > EngineProtocol.PROTOCOL) {
                            // A newer-protocol client: this engine must not serve wire semantics
                            // it postdates — the client reacts by taking over (spawn + drain).
                            send(writer, EngineProtocol.error(EngineProtocol.ERR_VERSION_SKEW,
                                    "client speaks protocol " + clientProto + " but this engine speaks "
                                            + EngineProtocol.PROTOCOL + " — start a matching engine"));
                            return;
                        }
                        send(writer, EngineProtocol.helloAck(version, pid, startedAtMillis, draining, buildId));
                    }
                    case EngineProtocol.PING -> send(writer, EngineProtocol.pong());
                    case EngineProtocol.STATUS -> {
                        build.jumpkick.engine.http.StatusSnapshot s = statusSnapshot();
                        send(
                                writer,
                                EngineProtocol.statusAck(
                                        s.version(),
                                        s.pid(),
                                        s.startedAtMillis(),
                                        s.activeRequests(),
                                        s.activePipelines(),
                                        draining,
                                        s.heapUsedBytes(),
                                        s.heapCommittedBytes(),
                                        s.heapMaxBytes(),
                                        s.rssBytes(),
                                        s.aotTrainingPid(),
                                        httpServer != null ? httpServer.url() : null,
                                        httpError));
                    }
                    case EngineProtocol.SHUTDOWN -> {
                        boolean force = build.jumpkick.plugin.protocol.Ndjson.bool(line, "force", false);
                        synchronized (lifecycleLock) {
                            int jobs = activePipelines.get();
                            if (force || jobs == 0) {
                                // Immediate: no in-flight jobs, or an explicit force — close the listener
                                // now so run() returns and the JVM exits cleanly (AOT still assembles).
                                send(writer, EngineProtocol.bye(jobs, false));
                                shuttingDown = true;
                                closeServerChannelQuietly();
                            } else {
                                // Graceful drain: keep the listener open (so new commands get a clear
                                // "shutting down" handshake and in-flight jobs finish); the last job to
                                // complete triggers the clean exit (see maybeIdleBoundaryGc).
                                draining = true;
                                send(writer, EngineProtocol.bye(jobs, true));
                            }
                        }
                        return;
                    }
                    case EngineProtocol.HISTORY_LIST_REQUEST -> handleHistoryList(line, writer);
                    case EngineProtocol.HISTORY_SHOW_REQUEST -> handleHistoryShow(line, writer);
                    case EngineProtocol.HISTORY_DELETE_REQUEST -> handleHistoryDelete(line, writer);
                    case EngineProtocol.METRICS_REQUEST -> handleMetrics(line, writer);
                    case EngineProtocol.BUILD_REQUEST -> {
                        // Owns the rest of this connection's lifecycle: forks the build onto its own
                        // thread and keeps reading this loop for a build-cancel/EOF while it runs.
                        handleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.TEST_REQUEST -> {
                        // Same shape as BUILD_REQUEST but for a single project's test pipeline (Step 3).
                        handleTestRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.SINGLE_BUILD_REQUEST -> {
                        // Same shape as TEST_REQUEST but a real (non-testOnly) build pipeline.
                        handleSingleBuildRequest(line, reader, writer);
                        return;
                    }
                    case EngineProtocol.LOCK_REQUEST -> {
                        // Same fork-and-watch shape as BUILD_REQUEST, hosting jk lock's cascade.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-lock-", "lock", this::runLock);
                        return;
                    }
                    case EngineProtocol.UPDATE_REQUEST -> {
                        // jk update rides jk lock's event vocabulary (plus the --git splice mode).
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-update-", "update", this::runUpdate);
                        return;
                    }
                    case EngineProtocol.SYNC_REQUEST -> {
                        // jk sync is a single pipeline — TEST_REQUEST's wire shape.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-sync-", "sync", this::runSync);
                        return;
                    }
                    case EngineProtocol.AUDIT_REQUEST -> {
                        // Wave 2 (hosted worker commands): single pipeline, worker forked engine-side.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-audit-", "audit", this::runAudit);
                        return;
                    }
                    case EngineProtocol.FORMAT_REQUEST -> {
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-format-", "format", this::runFormat);
                        return;
                    }
                    case EngineProtocol.PUBLISH_REQUEST -> {
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-publish-", "publish", this::runPublish);
                        return;
                    }
                    case EngineProtocol.IMAGE_REQUEST -> {
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-image-", "image", this::runImage);
                        return;
                    }
                    case EngineProtocol.IMPORT_REQUEST -> {
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-import-", "import", this::runImport);
                        return;
                    }
                    case EngineProtocol.PROVISION_REQUEST -> {
                        // One-shot (no pipeline events), but the worker may download a whole Maven/Gradle
                        // distribution — same fork-and-watch shape so an EOF still cancels.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-provision-", "provision", this::runProvision);
                        return;
                    }
                    case EngineProtocol.COMPILE_REQUEST -> {
                        // Wave 3 (hosted pipeline commands): jk compile is a single pipeline — TEST_REQUEST's shape.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-compile-", "compile", this::runCompile);
                        return;
                    }
                    case EngineProtocol.NATIVE_REQUEST -> {
                        // jk native's serial module cascade, speaking BUILD_REQUEST's workspace vocabulary.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-native-", "native", this::runNative);
                        return;
                    }
                    case EngineProtocol.INSTALL_REQUEST -> {
                        // jk install's build + cache-install halves; make-install stays client-side.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-install-", "install", this::runInstall);
                        return;
                    }
                    case EngineProtocol.GIT_FETCH_REQUEST -> {
                        // jk install <git-url>'s clone half (git runs in-process in the engine).
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-gitfetch-", "git-fetch", this::runGitFetch);
                        return;
                    }
                    case EngineProtocol.SCRIPT_PREPARE_REQUEST -> {
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-script-", "script", this::runScriptPrepare);
                        return;
                    }
                    case EngineProtocol.TOOL_RESOLVE_REQUEST -> {
                        // Wave 4 (hosted long-tail commands): jk tool install/run's Maven resolve+fetch.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-tool-", "tool", this::runToolResolve);
                        return;
                    }
                    case EngineProtocol.CACHE_PRUNE_REQUEST -> {
                        // Cache maintenance is an idle-boundary job, not a pipeline: it waits for
                        // activePipelines to drain (and blocks new ones) instead of joining them.
                        handleAsyncPipelineRequest(line, reader, writer, "jk-engine-cache-", "cache", this::runCacheMaintenance, false);
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
                    case EngineProtocol.OUTDATED_REQUEST -> handleOutdatedRequest(line, writer);
                    case EngineProtocol.EXEC_PLAN_REQUEST -> handleExecPlanRequest(line, writer);
                    case EngineProtocol.EDIT_REQUEST -> handleEditRequest(line, writer);
                    case EngineProtocol.DENY_CHECK_REQUEST -> handleDenyCheckRequest(line, writer);
                    case EngineProtocol.TREE_REQUEST -> handleTreeRequest(line, writer);
                    case EngineProtocol.WHY_REQUEST -> handleWhyRequest(line, writer);
                    case EngineProtocol.GENERATE_REQUEST -> handleGenerateRequest(line, writer);
                    case EngineProtocol.PLUGIN_VERB_REQUEST -> handlePluginCommandRequest(line, writer);
                    case EngineProtocol.IDE_MODEL_REQUEST -> handleIdeModelRequest(line, writer);
                    default -> sendQuiet(writer, EngineProtocol.error(EngineProtocol.ERR_PROTOCOL,
                            "unknown request type: " + type));
                }
            }
        }
    }

    /**
     * Run a workspace build on its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and stream every {@link
     * WorkspaceBuildListener}/{@link PipelineListener} callback back as a wire event. Returns once the
     * build finishes and its terminal message has been sent, or the connection drops.
     */
    private void handleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncPipelineRequest(requestLine, reader, writer, "jk-engine-build-", "build", this::runBuild);
    }

    /** An engine-hosted operation's body: decode the request, run it, stream events to {@code writer}. */
    @FunctionalInterface
    private interface PipelineRunner {
        void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer);
    }

    /**
     * Fork {@code runner} onto its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and wait for it to finish. Shared by every
     * request type that owns the rest of its connection's lifecycle ({@link #handleBuildRequest},
     * {@link #handleTestRequest}, {@link #handleSingleBuildRequest}) — they differ only in what
     * {@code runner} actually builds and runs.
     */
    private void handleAsyncPipelineRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            String kind,
            PipelineRunner runner) {
        handleAsyncPipelineRequest(requestLine, reader, writer, threadPrefix, kind, runner, true);
    }

    /**
     * As above; {@code pipeline=false} for a cache maintenance job, which is deliberately <em>not</em>
     * a pipeline: it doesn't join {@link #activePipelines} or hold {@link #cacheGate}'s read side —
     * its runner takes the write side itself (see {@link #runCacheMaintenance}).
     */
    private void handleAsyncPipelineRequest(
            String requestLine,
            BufferedReader reader,
            BufferedWriter writer,
            String threadPrefix,
            String kind,
            PipelineRunner runner,
            boolean pipeline) {
        // Refuse new jobs while draining (a graceful shutdown is finishing in-flight work). The client
        // normally can't even get here — its handshake sees `draining` and fails first — but guard the
        // server too so a raced/last-moment request is rejected instead of prolonging the drain.
        if (draining) {
            try {
                send(writer, EngineProtocol.error(EngineProtocol.ERR_SHUTTING_DOWN,
        "the engine is shutting down (draining) — retry; the successor engine takes over"));
            } catch (IOException ignored) {
                // Client vanished mid-refusal — nothing to do; the connection is closing anyway.
            }
            return;
        }
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        long eventRequestId = requestIds.incrementAndGet();
        // The kind rides explicitly from the dispatch site (never parsed back out of a thread
        // name); the journal dir falls back to a request's specific location field so non-build
        // requests never record the literal string "null".
        String eventKind = kind;
        String eventDir = journalDir(requestLine);
        long eventStartMillis = clockMillis.getAsLong();
        publishRequestStart(eventRequestId, eventKind, eventDir);
        registerAccumulator(eventRequestId, eventKind, eventDir, "cli");
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
                    build.jumpkick.engine.http.JsonOut.object()
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
     * "cancelled". For a build we trust the runner's own {@link PipelineResult#userCancelled()} (captured
     * in the accumulator); only non-build requests (no accumulator) fall back to the raw token.
     */
    private boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        BuildAccumulator a = accumulators.get(requestId);
        return a != null ? a.wasCancelled() : rawCancelled;
    }

    /** The request's location for journal/dashboard rows: {@code dir}, else the nearest thing. */
    private static String journalDir(String requestLine) {
        String dir = Ndjson.str(requestLine, "dir");
        if (dir != null) return dir;
        String cache = Ndjson.str(requestLine, "cache");
        if (cache != null) return cache;
        return "";
    }

    /** Publish to the dashboard event hub — free (one subscriber check) when no dashboard is open. */
    private void publishEvent(String type, build.jumpkick.engine.http.JsonOut payload) {
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
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("kind", kind)
                        .put("dir", dir)
                        .put("coord", coord));
    }

    private void publishStepStart(long requestId, String dir, String step, String phase) {
        if (!eventsWanted()) return;
        publishEvent(
                "step-start",
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("step", step)
                        .put("phase", phase));
    }

    private void publishStepFinish(long requestId, String dir, String step, String phase, String status) {
        if (!eventsWanted()) return;
        publishEvent(
                "step-finish",
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("step", step)
                        .put("phase", phase)
                        .put("status", status));
    }

    /** Wire spelling of a step's coarse {@link build.jumpkick.plugin.build.Phase} — {@code ""} when unset. */
    private static String phaseWire(build.jumpkick.plugin.build.Phase phase) {
        return phase == null ? "" : phase.wireName();
    }

    private void publishOutput(long requestId, String dir, String step, String line) {
        if (!eventsWanted()) return;
        publishEvent(
                "output",
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("step", step)
                        .put("line", line));
    }

    /** Failure detail is bounded on the wire: a compile explosion must not flood the event stream. */
    private static final int MAX_DIAGNOSTIC_EVENTS = 8;

    /**
     * The failure context behind a failed card ({@code docs/webclient.md}): the same structured
     * {@link PipelineResult.Diagnostic}s the CLI renders — test failures carry test/exceptionClass,
     * and lock/sync/parse/worker failures arrive as plain step diagnostics through the same seam.
     */
    private void publishDiagnostics(long requestId, String dir, java.util.List<PipelineResult.Diagnostic> errors) {
        if (!eventsWanted() || errors.isEmpty()) return;
        int shown = Math.min(errors.size(), MAX_DIAGNOSTIC_EVENTS);
        for (int i = 0; i < shown; i++) {
            PipelineResult.Diagnostic d = errors.get(i);
            publishEvent(
                    "diagnostic",
                    build.jumpkick.engine.http.JsonOut.object()
                            .put("requestId", requestId)
                            .put("dir", dir)
                            .put("step", d.step())
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
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("step", "request")
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
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("weight", totalWeight));
    }

    /**
     * Weight-based progress for one module pipeline — the identical {@code numerator}/{@code denominator}
     * the CLI progress bar renders (from {@link PipelineView}). The dashboard sums these across modules
     * for the request-level bar. Emitted on pipeline-start / progress / tick-update, exactly where the
     * socket path sends {@code EngineProtocol.pipelineStart/progress/tickUpdate}.
     */
    private void publishPipelineProgress(long requestId, String dir, PipelineView view) {
        if (!eventsWanted()) return;
        publishEvent(
                "pipeline-progress",
                build.jumpkick.engine.http.JsonOut.object()
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
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("millis", millis));
    }

    /**
     * Guard for event publishers: build the payload only when someone is listening. Split from
     * {@link #publishEvent} so hot listener callbacks (per-pipeline, per-module) pay one boolean check,
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
            pruneMetrics();
            // The last in-flight job of a graceful drain just finished — close the listener so run()
            // returns and the JVM exits cleanly (assembling the AOT cache), same as a normal stop.
            if (draining) {
                synchronized (lifecycleLock) {
                    shuttingDown = true;
                    closeServerChannelQuietly();
                }
            }
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
     * Enforce metrics retention at the idle boundary: age out rows for projects no longer built
     * here, then oldest-first past the byte cap. Best-effort; a failure is logged.
     */
    private void pruneMetrics() {
        try {
            BuildMetrics.Limits limits =
                    BuildMetrics.Limits.resolve(build.jumpkick.util.JkDirs.userConfigFile(), System::getenv);
            BuildMetrics.PruneReport r = BuildMetrics.prune(metricsFile, limits, clockMillis.getAsLong(), false);
            if (r.evictedByAge() + r.evictedBySize() > 0) {
                log.accept("jk engine: build metrics prune removed " + (r.evictedByAge() + r.evictedBySize())
                        + " rows (" + r.kept() + " kept, " + r.finalBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build metrics prune failed: " + e.getMessage());
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
            var config = build.jumpkick.config.JkCacheConfig.resolve();
            if (config.autoPrune() && build.jumpkick.task.CachePruneScheduler.shouldRun(config, cache)) {
                pendingPruneCache.compareAndSet(null, cache);
            }
        } catch (IOException ignored) {
            // Best-effort — the opportunistic prune is hygiene, never load-bearing.
        }
    }

    /**
     * Run the queued opportunistic prune, if any, now that no pipeline is in flight. Runs on the
     * finishing request's connection thread; a pipeline that starts concurrently wins the
     * {@link #cacheGate} race and the prune stays queued for the next boundary. Mirrors the legacy {@code --background} flags: sweep on,
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
                var config = build.jumpkick.config.JkCacheConfig.resolve();
                build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.CachePipelines.prunePipeline(
                        cache,
                        config.recordTtlDays(),
                        false,
                        true,
                        config.maxSizeGb().map(gb -> gb + "G").orElse(null),
                        false);
                build.jumpkick.run.PipelineResult result = pipeline.run();
                if (result.success()) {
                    Files.writeString(
                            cache.resolve(build.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE),
                            Long.toString(clockMillis.getAsLong()),
                            StandardCharsets.UTF_8);
                    log.accept("jk engine: idle-boundary cache prune removed "
                            + pipeline.get(build.jumpkick.runtime.CachePipelines.FILES).orElse(0L)
                            + " files ("
                            + pipeline.get(build.jumpkick.runtime.CachePipelines.BYTES).orElse(0L)
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
            String entryDirStr = Ndjson.str(requestLine, "dir");
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
            boolean rerun = Ndjson.bool(requestLine, "rebuild", false);
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
                    freshenLock)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(rerun),
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
            publishRequestError(eventRequestId(), Ndjson.str(requestLine, "dir"), String.valueOf(e.getMessage()));
        }
    }

    /**
     * As {@link #handleBuildRequest}, but for a single project's test pipeline (Step 3): forks the run
     * onto its own thread and keeps reading the connection for a cancel/EOF meanwhile.
     */
    private void handleTestRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncPipelineRequest(requestLine, reader, writer, "jk-engine-test-", "test", this::runTest);
    }

    /**
     * As {@link #handleTestRequest}, but for a single (non-workspace) project's real build pipeline — the
     * engine-hosted counterpart of {@code BuildCommand.runForDir}.
     */
    private void handleSingleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncPipelineRequest(requestLine, reader, writer, "jk-engine-1build-", "build", this::runSingleBuild);
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
        build.jumpkick.engine.protocol.ProjectInfo info;
        try {
            info = build.jumpkick.runtime.ExecPlans.projectInfo(Path.of(Ndjson.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            info = build.jumpkick.engine.protocol.ProjectInfo.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, info.encode());
    }

    /**
     * Answer {@link EngineProtocol#OUTDATED_REQUEST}: the read-only {@code jk outdated} report.
     * Synchronous, inline — parse + version enumeration only, never writes jk.lock. The session's
     * offline/force flags ride the request so version enumeration honors them (metadata TTL bypass,
     * stale-but-usable when offline). Errors ride the ack's {@code error} field.
     */
    private void handleOutdatedRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.OutdatedReport report;
        try {
            Path dir = Path.of(Ndjson.str(requestLine, "dir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String repoUrl = Ndjson.str(requestLine, "repoUrl");
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "offline", false)),
                    Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "force", false)),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withWorkingDir(dir)
                    .withCacheDir(cache);
            report = SessionContext.where(session, () -> build.jumpkick.runtime.OutdatedPipelines.compute(
                    dir, cache, repoUrl == null ? null : java.net.URI.create(repoUrl)));
        } catch (Exception e) {
            report = build.jumpkick.engine.protocol.OutdatedReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
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
            rendered = build.jumpkick.runtime.GraphOps.treeRender(
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
        build.jumpkick.engine.protocol.WhyReport report;
        try {
            report = build.jumpkick.runtime.GraphOps.why(
                    Path.of(Ndjson.str(requestLine, "dir")), Ndjson.str(requestLine, "query"));
        } catch (RuntimeException e) {
            report = build.jumpkick.engine.protocol.WhyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    private static final java.util.Set<String> DELEGATABLE = java.util.Set.of(
            EngineProtocol.BUILD_REQUEST,
            EngineProtocol.TEST_REQUEST,
            EngineProtocol.SINGLE_BUILD_REQUEST,
            EngineProtocol.COMPILE_REQUEST,
            EngineProtocol.NATIVE_REQUEST,
            EngineProtocol.IMAGE_REQUEST,
            EngineProtocol.INSTALL_REQUEST,
            EngineProtocol.PUBLISH_REQUEST);

    /**
     * Downward delegation gate (engine-versioning-plan §3): a request whose project pins an
     * OLDER jk runs on that version's engine as a one-shot child; a NEWER pin is refused with
     * an upgrade-shaped error (the client materializes + takes over — never the reverse). Same
     * version / no pin → false, serve locally. Job children never re-delegate: their version IS
     * the pin.
     */
    private boolean maybeDelegate(String requestLine, BufferedReader reader, BufferedWriter writer) {
        if (jobMode) return false; // a job child serves what it was handed — never re-routes
        String entryDir = build.jumpkick.plugin.protocol.Ndjson.str(requestLine, "dir");
        if (entryDir == null) return false;
        String pin = EngineDelegate.pinnedVersionDiffering(Path.of(entryDir), version);
        if (pin == null) return false;
        if (EngineDelegate.pinIsNewer(pin, version)) {
            sendQuiet(writer, EngineProtocol.error(EngineProtocol.ERR_VERSION_SKEW, "this build pins jk " + pin + " but the engine is "
                    + version + " — run that project's wrapper (./jk) or `jk self update` to upgrade;"
                    + " the newer engine takes over without interrupting running builds"));
            return true;
        }
        try {
            EngineDelegate.runAsChild(pin, requestLine, reader, writer, paths.log(), log);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendQuiet(writer, EngineProtocol.requestFailed("interrupted delegating to jk " + pin));
        } catch (IOException e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
        return true;
    }

    /** Answer {@link EngineProtocol#PLUGIN_VERB_REQUEST}: a plugin-declared command, worker-executed. */
    private void handlePluginCommandRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.PluginCommandReport report;
        try {
            report = build.jumpkick.runtime.PluginCommands.run(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Path.of(Ndjson.str(requestLine, "cache")),
                    Ndjson.str(requestLine, "command"),
                    Ndjson.strArray(requestLine, "args"),
                    EngineProtocol.variantOf(requestLine),
                    EngineProtocol.clientEnvOf(requestLine));
        } catch (RuntimeException e) {
            report = build.jumpkick.engine.protocol.PluginCommandReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#GENERATE_REQUEST}: full-model generator payloads, engine-side. */
    private void handleGenerateRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.GeneratedFiles files;
        try {
            files = build.jumpkick.runtime.GenerateOps.generate(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Ndjson.str(requestLine, "kind"),
                    EngineProtocol.generateParams(requestLine));
        } catch (RuntimeException e) {
            files = build.jumpkick.engine.protocol.GeneratedFiles.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, files.encode());
    }

    /** Answer {@link EngineProtocol#IDE_MODEL_REQUEST}: the IDE-agnostic workspace model, engine-side. */
    private void handleIdeModelRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.IdeWireModel model;
        try {
            String jdksDir = Ndjson.str(requestLine, "jdksDir");
            model = build.jumpkick.runtime.IdeOps.ideModel(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Path.of(Ndjson.str(requestLine, "cache")),
                    jdksDir == null ? null : Path.of(jdksDir),
                    false);
        } catch (RuntimeException e) {
            model = build.jumpkick.engine.protocol.IdeWireModel.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, model.encode());
    }

    /** Answer {@link EngineProtocol#DENY_CHECK_REQUEST}: policy parse + lock read + check, engine-side. */
    private void handleDenyCheckRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.DenyReport report;
        try {
            report = build.jumpkick.runtime.PolicyOps.denyCheck(Path.of(Ndjson.str(requestLine, "dir")));
        } catch (RuntimeException e) {
            report = build.jumpkick.engine.protocol.DenyReport.error(String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, report.encode());
    }

    /** Answer {@link EngineProtocol#EDIT_REQUEST}: one named jk.toml edit, engine-side. */
    private void handleEditRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.runtime.EditOps.Result result;
        try {
            result = build.jumpkick.runtime.EditOps.apply(
                    Path.of(Ndjson.str(requestLine, "file")),
                    Ndjson.str(requestLine, "op"),
                    Ndjson.strArray(requestLine, "args"));
        } catch (RuntimeException e) {
            result = new build.jumpkick.runtime.EditOps.Result(false, String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, EngineProtocol.editAck(result.changed(), result.error()));
    }

    private void handleExecPlanRequest(String requestLine, BufferedWriter writer) {
        build.jumpkick.engine.protocol.ExecPlan plan;
        try {
            String binDir = Ndjson.str(requestLine, "binDir");
            String libDir = Ndjson.str(requestLine, "libDir");
            plan = build.jumpkick.runtime.ExecPlans.execPlan(
                    Path.of(Ndjson.str(requestLine, "dir")),
                    Path.of(Ndjson.str(requestLine, "cache")),
                    Ndjson.str(requestLine, "kind"),
                    Ndjson.str(requestLine, "mainOverride"),
                    Ndjson.str(requestLine, "binName"),
                    binDir == null ? null : Path.of(binDir),
                    libDir == null ? null : Path.of(libDir),
                    EngineProtocol.variantOf(requestLine),
                    EngineProtocol.clientEnvOf(requestLine));
        } catch (RuntimeException e) {
            plan = build.jumpkick.engine.protocol.ExecPlan.error("unknown", String.valueOf(e.getMessage()));
        }
        sendQuiet(writer, plan.encode());
    }

    private void handleForecastRequest(String requestLine, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "dir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "offline", false)),
                    Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
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
     * module/step/edge messages — the engine-hosted counterpart of {@code ExplainCommand}'s call
     * into the same facade. Synchronous and inline (no worker JVM forked, no cancel/EOF fork needed
     * unlike {@link #handleBuildRequest}/{@link #handleTestRequest}/{@link #handleSingleBuildRequest}).
     */
    private void handleExplainRequest(String requestLine, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "dir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            ExplainPlan plan = BuildService.explain(entryDir, entryBuild, cache);
            if (plan.hasErrors()) {
                for (String err : plan.errors()) {
                    sendQuiet(writer, EngineProtocol.requestFailed(err));
                }
                sendQuiet(writer, EngineProtocol.explainDone(1, 0));
                return;
            }
            for (build.jumpkick.runtime.BuildPlan.Module m : plan.modules()) {
                String dir = m.dir().toString();
                sendQuiet(
                        writer,
                        EngineProtocol.explainModule(
                                dir, m.coord(), m.sourceCount(), m.testCount(), m.producesJar(), m.producesImage()));
                for (build.jumpkick.runtime.BuildPlan.Step p : m.steps()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.explainStep(
                                    dir, p.name(), p.status().name(), p.text(), p.key()));
                }
            }
            for (var e : plan.edges().entrySet()) {
                for (Path dep : e.getValue()) {
                    sendQuiet(writer, EngineProtocol.explainEdge(e.getKey().toString(), dep.toString()));
                }
            }
            // The schedule-aware build-time estimate (Wave 3: previously computed client-side
            // against BuildPipelines/EffortWeights/Calibration — the known docs/engine.md gap).
            // 0 = unknown; the client renders "Build time unknown" for that.
            String etaJdksDirStr = Ndjson.str(requestLine, "jdksDir");
            long etaMillis = BuildService.estimateEtaMillis(
                    plan,
                    entryDir,
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
            sendQuiet(writer, EngineProtocol.explainDone(0, 0));
        }
    }

    /**
     * Build and run the test-only {@code Pipeline} exactly as {@code TestCommand} does in-process, but
     * streaming its {@link PipelineListener} events over the wire via {@link #wirePipelineListener} — the
     * same single-pipeline event vocabulary {@link #runBuild} already speaks per module, here tagged with
     * the fixed {@link EngineProtocol#SINGLE_PIPELINE_DIR} sentinel since there's only one pipeline.
     */
    private void runTest(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "dir");
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

            int estimatedTestCount = build.jumpkick.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/java"))
                    + build.jumpkick.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/kotlin"));

            // The request's cache-relevant flags ride the session config exactly as
            // runSingleBuild's do — without this, `jk test --force` was silently
            // dropped on the hosted path (the run-tests stamp skip guards on rerunOr,
            // which reads the force flag).
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
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
            // Steps read in.session() for the force/rerun guards, so the ambient capture was
            // exactly how `--force` got dropped.
            build.jumpkick.runtime.BuildPipelines.Inputs inputs = new build.jumpkick.runtime.BuildPipelines.Inputs(
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
                    session)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            build.jumpkick.run.Pipeline pipeline =
                    build.jumpkick.runtime.BuildPipelines.coreBuilder(inputs).build();

            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            for (Step p : pipeline.steps()) {
                sendQuiet(writer, EngineProtocol.planStep(dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wirePipelineListener(dir, writer, pipeline));

            build.jumpkick.run.PipelineResult result = SessionContext.where(session, pipeline::run);
            accTests(eventRequestId(), pipeline.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            // pipelineFinish (with test counts, if any) was already sent by wirePipelineListener's own
            // pipelineFinish handling — nothing further to send here; the connection close signals "done".
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Build and run a single (non-workspace) project's real build pipeline — the engine-hosted
     * counterpart of {@code BuildCommand.runForDir}/{@code prepareModule}/{@code runPrepared}'s
     * {@code agg == null} branch. Same single-pipeline wire shape as {@link #runTest}, {@code
     * testOnly=false}. On success, folds the measured throughput into the host calibration and
     * queues the opportunistic cache prune for the next idle boundary (Wave 4: an engine-internal
     * enqueue, not a detached self-spawn) — exactly {@code runPrepared}'s post-success logic, moved
     * here since the engine (not the CLI) is the process that actually ran the pipeline.
     */
    private void runSingleBuild(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "dir");
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
                    : build.jumpkick.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/java"))
                            + build.jumpkick.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/kotlin"));

            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(offline),
                    Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
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
            build.jumpkick.runtime.BuildPipelines.Inputs inputs = new build.jumpkick.runtime.BuildPipelines.Inputs(
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
                    session)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            build.jumpkick.run.Pipeline.Builder builder = build.jumpkick.runtime.BuildPipelines.coreBuilder(inputs, false);
            build.jumpkick.runtime.BuildPipelines.appendDeclaredTails(builder, inputs);
            build.jumpkick.run.Pipeline pipeline = builder.build();
            long barWeight = pipeline.estimatedTotalWeight();

            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            for (Step p : pipeline.steps()) {
                sendQuiet(writer, EngineProtocol.planStep(dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wirePipelineListener(dir, writer, pipeline));

            long startNanos = System.nanoTime();
            build.jumpkick.run.PipelineResult result = SessionContext.where(session, pipeline::run);
            accTests(eventRequestId(), pipeline.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
            accOutcome(eventRequestId(), result.success(), result.success() ? 0 : 1);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    build.jumpkick.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                maybeEnqueuePrune(cache);
            }
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#LOCK_REQUEST} and run {@code jk lock}'s cascade in-session:
     * the entry project, then (for a workspace root) each declared module in declaration order —
     * each module a {@link EngineProtocol#LOCK_MODULE} + plan-step burst + the standard pipeline
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#UPDATE_REQUEST}: either the full re-resolve cascade (riding
     * {@link #runLock}'s exact event vocabulary, with {@code jk update}'s always-fresh pipeline) or the
     * {@code --git} splice mode, which runs no pipeline at all — just the {@link
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
                                false, build.jumpkick.model.command.Exit.CONFIG,
                                java.util.List.of(String.valueOf(e.getMessage())), -1));
                        return null;
                    }
                    var outcome = build.jumpkick.runtime.LockPipelines.updateGitOnly(
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#SYNC_REQUEST} and run {@code jk sync}'s single pipeline in-session
     * — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape, with the fetched/up-to-date counts
     * riding the terminal pipeline-finish. The pipeline is built with {@code allowJdkInstall = false}: JDK
     * installs never happen inside the engine (the client pre-flights them — see {@link
     * build.jumpkick.runtime.SyncPipelines}). On success, queues the opportunistic cache prune for the
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
                build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.SyncPipelines.syncPipeline(
                        entryDir, cache, jdksDir, repoUrl, sources, fetched, upToDate, null, false);
                String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
                for (Step p : pipeline.steps()) {
                    sendQuiet(writer, EngineProtocol.planStep(dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
                }
                sendQuiet(writer, EngineProtocol.planDone(1));
                pipeline.addListener(wirePipelineListener(
                        dir,
                        writer,
                        (java.util.function.Function<PipelineResult, String>) result ->
                                EngineProtocol.pipelineFinishSync(dir, result.success(), fetched.get(), upToDate.get())));
                PipelineResult result = pipeline.run();
                if (result.success()) {
                    maybeEnqueuePrune(cache);
                }
                return null;
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted worker commands (Wave 2 of the slim-client migration) -------------------------------

    /**
     * Stream one single-pipeline command over the wire — the shared tail of every Wave-2 handler: the
     * {@link EngineProtocol#SINGLE_PIPELINE_DIR}-tagged plan-step burst, the standard pipeline events via
     * {@link #wirePipelineListener}, and {@code finishEncoder}'s terminal {@code pipeline-finish} variant.
     */
    private void streamSinglePipeline(
            build.jumpkick.run.Pipeline pipeline,
            Session session,
            BufferedWriter writer,
            java.util.function.Function<PipelineResult, String> finishEncoder)
            throws Exception {
        String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
        for (Step p : pipeline.steps()) {
            sendQuiet(writer, EngineProtocol.planStep(dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
        }
        sendQuiet(writer, EngineProtocol.planDone(1));
        pipeline.addListener(wirePipelineListener(dir, writer, finishEncoder));
        SessionContext.where(session, pipeline::run);
    }

    /**
     * Decode an {@link EngineProtocol#AUDIT_REQUEST} and run {@code jk audit}'s pipeline in-session,
     * forking the auditor worker engine-side and streaming each finding as a structured {@link
     * EngineProtocol#AUDIT_FINDING} event (the client assembles/renders the report and applies the
     * severity threshold itself).
     */
    private void runAudit(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "dir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String severity = Ndjson.str(requestLine, "severity");
            String batch = Ndjson.str(requestLine, "osvBatchUrl");
            String vulns = Ndjson.str(requestLine, "osvVulnsUrl");
            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withCancel(cancelToken)
                    .withJvm(EngineProtocol.jvmTuning(requestLine));
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.AuditPipelines.auditPipeline(
                    entryDir.resolve("jk.lock"),
                    cache,
                    severity,
                    batch != null ? java.net.URI.create(batch) : null,
                    vulns != null ? java.net.URI.create(vulns) : null,
                    (module, version, vulnId, sev, summary) -> sendQuiet(
                            writer, EngineProtocol.auditFinding(dir, module, version, vulnId, sev, summary)));
            streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#FORMAT_REQUEST} and run {@code jk format}'s pipeline in-session:
     * source collection, formatter-jar resolution (through jk's own resolver — previously done in
     * the client process), and the formatter worker fork, with per-file results streaming as {@link
     * EngineProtocol#FORMAT_FILE} events and the counts riding the terminal pipeline-finish.
     */
    private void runFormat(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean check = Ndjson.bool(requestLine, "check", false);
            String javaStyle = Ndjson.str(requestLine, "javaStyle");
            String kotlinStyle = Ndjson.str(requestLine, "kotlinStyle");
            boolean optimizeImports = Ndjson.bool(requestLine, "optimizeImports", true);
            String rewriteConfig = Ndjson.str(requestLine, "rewriteConfig");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.FormatPipelines.formatPipeline(
                    session.workingDir(),
                    session.cacheDir(),
                    check,
                    javaStyle,
                    kotlinStyle,
                    optimizeImports,
                    rewriteConfig != null ? Path.of(rewriteConfig) : null,
                    (path, status, message, index, total) ->
                            sendQuiet(writer, EngineProtocol.formatFile(dir, path, status, message, index, total)));
            streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinishFormat(
                    dir,
                    result.success(),
                    pipeline.get(build.jumpkick.runtime.FormatPipelines.CHANGED).orElse(-1),
                    pipeline.get(build.jumpkick.runtime.FormatPipelines.CLEAN).orElse(-1),
                    pipeline.get(build.jumpkick.runtime.FormatPipelines.ERRORS).orElse(-1),
                    pipeline.get(build.jumpkick.runtime.FormatPipelines.TOTAL).orElse(-1),
                    pipeline.get(build.jumpkick.runtime.FormatPipelines.WORKER_EXIT).orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PUBLISH_REQUEST} and run {@code jk publish}'s pipeline in-session.
     * The credential/passphrase fields were resolved client-side (env/keychain live there); they
     * pass straight through to the worker's 0600 spec file and are never logged.
     */
    private void runPublish(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "dir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String jar = Ndjson.str(requestLine, "jar");
            String keyFile = Ndjson.str(requestLine, "keyFile");
            build.jumpkick.credential.RepoCredential credential =
                    switch (String.valueOf(Ndjson.str(requestLine, "authType"))) {
                        case "basic" -> new build.jumpkick.credential.RepoCredential.Basic(
                                Ndjson.str(requestLine, "user"),
                                Ndjson.str(requestLine, "pass") != null ? Ndjson.str(requestLine, "pass") : "");
                        case "bearer" -> new build.jumpkick.credential.RepoCredential.Bearer(
                                Ndjson.str(requestLine, "token"));
                        default -> build.jumpkick.credential.RepoCredential.ANONYMOUS;
                    };
            build.jumpkick.runtime.PublishPipelines.Request req = new build.jumpkick.runtime.PublishPipelines.Request(
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
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.PublishPipelines.publishPipeline(entryDir, cache, req);
            streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinishPublish(
                    dir,
                    result.success(),
                    pipeline.get(build.jumpkick.runtime.PublishPipelines.FILES).orElse(-1)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMAGE_REQUEST} and run {@code jk image}'s pipeline in-session —
     * the full build pipeline plus the image tail (Jib worker or Dockerfile child process), all
     * engine-side. The terminal pipeline-finish carries the structured success-tail fields alongside
     * the test counts the client's exit-code logic needs.
     */
    private void runImage(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path entryDir = Path.of(Ndjson.str(requestLine, "dir"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.of(Ndjson.bool(requestLine, "offline", false)),
                    Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
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
                    .withCancel(cancelToken)
                    .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session: the pipeline factory's BuildPipelines.Inputs captures the
            // ambient SessionContext at construction, so building it outside where() would
            // silently pin this request to the engine's default config (dropping --force et al).
            build.jumpkick.run.Pipeline pipeline = SessionContext.where(session, () -> build.jumpkick.runtime.ImagePipelines.imagePipeline(
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
            streamSinglePipeline(pipeline, session, writer, result -> {
                build.jumpkick.run.TestSummary testResult =
                        pipeline.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null);
                build.jumpkick.image.ImageConfig cfg =
                        pipeline.get(build.jumpkick.runtime.ImagePipelines.CONFIG).orElse(null);
                Path tarball = pipeline.get(build.jumpkick.runtime.ImagePipelines.TARBALL_PATH)
                        .orElse(null);
                JkBuild project =
                        pipeline.get(build.jumpkick.runtime.BuildPipelines.PROJECT).orElse(null);
                boolean daemonMode =
                        tarball == null && (cfg == null || cfg.registry() == null || cfg.registry().isBlank());
                String daemonExe = !daemonMode
                        ? null
                        : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
                return EngineProtocol.pipelineFinishImage(
                        dir,
                        result.success(),
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1,
                        pipeline.get(build.jumpkick.runtime.ImagePipelines.IMAGE_REF).orElse(null),
                        tarball != null ? tarball.toString() : null,
                        project != null ? project.project().name() : null,
                        project != null ? project.project().version() : null,
                        daemonExe);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#IMPORT_REQUEST} and run {@code jk import}'s single-step pipeline
     * in-session, streaming the worker's progress notes as {@link EngineProtocol#IMPORT_NOTE}
     * events. The worker's exit code/warnings/error ride the terminal pipeline-finish (a non-zero
     * worker exit is a result the client renders, not a pipeline failure).
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
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.CompatPipelines.importPipeline(
                    Path.of(Ndjson.str(requestLine, "source")),
                    Path.of(Ndjson.str(requestLine, "out")),
                    baseDir,
                    Path.of(Ndjson.str(requestLine, "tmpDir")),
                    Ndjson.bool(requestLine, "force", false),
                    report != null ? Path.of(report) : null,
                    cache,
                    (kind, text) -> sendQuiet(writer, EngineProtocol.importNote(dir, kind, text)));
            streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinishImport(
                    dir,
                    result.success(),
                    pipeline.get(build.jumpkick.runtime.CompatPipelines.EXIT).orElse(1),
                    pipeline.get(build.jumpkick.runtime.CompatPipelines.WARNINGS).orElse(0),
                    pipeline.get(build.jumpkick.runtime.CompatPipelines.ERROR).orElse(null),
                    pipeline.get(build.jumpkick.runtime.CompatPipelines.DIAG).orElse(null)));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#PROVISION_REQUEST}, provision the Maven/Gradle distribution
     * via the compat-bridge worker, and reply with the one-shot {@link
     * EngineProtocol#PROVISION_RESULT} terminal. The exec of the provisioned tool stays client-side.
     */
    private void runProvision(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            var outcome = build.jumpkick.runtime.CompatPipelines.provision(
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted pipeline commands (Wave 3 of the slim-client migration) ------------------------------

    /**
     * Decode a {@link EngineProtocol#COMPILE_REQUEST} and run {@code jk compile}'s single
     * compile-only pipeline in-session — {@link EngineProtocol#TEST_REQUEST}'s exact wire shape with a
     * plain terminal pipeline-finish (the command has no structured summary beyond success).
     */
    private void runCompile(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String profile = Ndjson.str(requestLine, "profile");
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            build.jumpkick.run.Pipeline pipeline = SessionContext.where(
                    session,
                    () -> build.jumpkick.runtime.CompilePipelines.compilePipeline(
                            session.workingDir(), session.cacheDir(), profile, verbose));
            streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinish(dir, result.success()));
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode an {@link EngineProtocol#INSTALL_REQUEST} and run {@code jk install}'s build +
     * cache-install pipeline in-session (see {@link build.jumpkick.runtime.InstallPipelines}). The terminal
     * pipeline-finish carries the test counts for the client's exit-code logic; the launcher-writing
     * "make install" half runs client-side after this succeeds.
     */
    private void runInstall(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            boolean skipTests = Ndjson.bool(requestLine, "skipTests", false);
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);
            String m2DirStr = Ndjson.str(requestLine, "m2Dir");
            String graalHomeStr = Ndjson.str(requestLine, "graalHome");
            Session session = resolveSession(requestLine, cancelToken, false);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Constructed in-session — see runImage's note on ambient-session capture.
            build.jumpkick.run.Pipeline pipeline = SessionContext.where(
                    session,
                    () -> build.jumpkick.runtime.InstallPipelines.projectInstallPipeline(
                            session.workingDir(),
                            session.cacheDir(),
                            Path.of(m2DirStr),
                            skipTests,
                            verbose,
                            graalHomeStr != null ? Path.of(graalHomeStr) : null));
            streamSinglePipeline(pipeline, session, writer, result -> {
                build.jumpkick.run.TestSummary testResult =
                        pipeline.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null);
                return testResult == null
                        ? EngineProtocol.pipelineFinish(dir, result.success())
                        : EngineProtocol.pipelineFinish(
                                dir,
                                result.success(),
                                testResult.total(),
                                testResult.succeeded(),
                                testResult.failed(),
                                testResult.skipped());
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#GIT_FETCH_REQUEST} and materialize the checkout in-session
     * (git runs in-process — {@link build.jumpkick.git.GitFetcher} prefers the git CLI, else JGit);
     * the terminal pipeline-finish carries the checkout path + sha the client's follow-up {@link
     * EngineProtocol#INSTALL_REQUEST} needs.
     */
    private void runGitFetch(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            boolean refresh = Ndjson.bool(requestLine, "refresh", false);
            JkConfig config = new JkConfig(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(refresh),
                    Optional.empty());
            Session session = Session.defaults()
                    .withConfig(config)
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.InstallPipelines.gitFetchPipeline(
                    Ndjson.str(requestLine, "url"),
                    Ndjson.str(requestLine, "canonicalUrl"),
                    Ndjson.str(requestLine, "ref"),
                    cache,
                    refresh,
                    Ndjson.bool(requestLine, "requireJkToml", true));
            streamSinglePipeline(pipeline, session, writer, result -> {
                Path checkout = pipeline.get(build.jumpkick.runtime.InstallPipelines.CHECKOUT).orElse(null);
                String sha = pipeline.get(build.jumpkick.runtime.InstallPipelines.FETCHED_SHA).orElse(null);
                return EngineProtocol.pipelineFinishGitFetch(
                        dir, result.success(), checkout != null ? checkout.toString() : null, sha);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    // ---- hosted long-tail commands (Wave 4 of the slim-client migration) -----------------------------

    /**
     * Decode a {@link EngineProtocol#SCRIPT_PREPARE_REQUEST} and run the shared script-preparation
     * pipeline ({@code jk tool run <file>}'s parse/resolve/compile half — see {@link
     * build.jumpkick.runtime.ScriptPipelines}). The terminal pipeline-finish carries the exec ingredients; the
     * exec stays client-side.
     */
    private void runScriptPrepare(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String mode = String.valueOf(Ndjson.str(requestLine, "mode"));
            Path script = Path.of(Ndjson.str(requestLine, "script"));
            Path cache = Path.of(Ndjson.str(requestLine, "cache"));
            String stateDirStr = Ndjson.str(requestLine, "stateDir");
            Path stateDir = stateDirStr != null ? Path.of(stateDirStr) : build.jumpkick.util.JkDirs.state();
            java.net.URI repoUrl = repoUrlOf(requestLine);
            boolean forceRecompile = Ndjson.bool(requestLine, "forceRecompile", false);
            java.nio.file.Files.createDirectories(cache);
            Session session = Session.defaults()
                    .withWorkingDir(script.toAbsolutePath().getParent())
                    .withCacheDir(cache)
                    .withCancel(cancelToken);
            java.util.List<build.jumpkick.model.Dependency> extraDeps = Ndjson.strArray(requestLine, "with").stream()
                    .map(build.jumpkick.script.ScriptHeaderParser::parseDependency)
                    .toList();
            build.jumpkick.run.Pipeline pipeline =
                    switch (mode) {
                        case "java" -> build.jumpkick.runtime.ScriptPipelines.javaScriptPipeline(
                                script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kt" -> build.jumpkick.runtime.ScriptPipelines.kotlinScriptPipeline(
                                script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                        case "kts" -> build.jumpkick.runtime.ScriptPipelines.ktsScriptPipeline(script, cache, repoUrl, extraDeps);
                        case "jar" -> build.jumpkick.runtime.ScriptPipelines.jarPipeline(script, cache, repoUrl);
                        default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                    };
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            streamSinglePipeline(pipeline, session, writer, result -> {
                Path classesDir = pipeline.get(build.jumpkick.runtime.ScriptPipelines.CLASSES_DIR).orElse(null);
                Path kotlincBin = pipeline.get(build.jumpkick.runtime.ScriptPipelines.KOTLINC_BIN).orElse(null);
                Path stdlib = pipeline.get(build.jumpkick.runtime.ScriptPipelines.KT_STDLIB).orElse(null);
                return EngineProtocol.pipelineFinishScript(
                        dir,
                        result.success(),
                        pipeline.get(build.jumpkick.runtime.ScriptPipelines.MAIN_CLASS).orElse(null),
                        build.jumpkick.runtime.ScriptPipelines.classpathOf(pipeline).stream()
                                .map(Path::toString)
                                .toList(),
                        classesDir != null ? classesDir.toString() : null,
                        kotlincBin != null ? kotlincBin.toString() : null,
                        stdlib != null ? stdlib.toString() : null);
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#TOOL_RESOLVE_REQUEST} and run the shared tool-resolution pipeline
     * in-session ({@code jk tool install}/{@code jk tool run}/{@code jk install <g:a:v>}'s Maven
     * resolve + fetch — see {@link build.jumpkick.runtime.ToolPipelines}). The terminal pipeline-finish carries
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
            build.jumpkick.model.ToolCoordSpec spec = build.jumpkick.model.ToolCoordSpec.parse(coord);
            java.util.List<build.jumpkick.model.ToolCoordSpec> with = Ndjson.strArray(requestLine, "with").stream()
                    .map(build.jumpkick.model.ToolCoordSpec::parse)
                    .toList();
            Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
            String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
            // Plain g:a[:v] label — coordinate colorization is a client-side concern.
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.ToolPipelines.resolvePipeline(
                    spec, with, bin, mainClass, repoUrl, cache, coord);
            streamSinglePipeline(pipeline, session, writer, result -> {
                build.jumpkick.tool.ToolEnv env =
                        pipeline.get(build.jumpkick.runtime.ToolPipelines.TOOL_ENV).orElse(null);
                return EngineProtocol.pipelineFinishTool(
                        dir,
                        result.success(),
                        env != null ? env.primary().toGav() : null,
                        env != null ? env.mainClass() : null,
                        env != null
                                ? env.classpath().stream().map(Path::toString).toList()
                                : java.util.List.of());
            });
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#CACHE_PRUNE_REQUEST} and run its maintenance op ({@code prune}
     * / {@code purge} / {@code gc}) as an idle-boundary job: take {@link #cacheGate}'s write side
     * (emitting {@link EngineProtocol#PRUNE_WAIT} first when pipelines are in flight, so the client
     * isn't staring at silence) and the cross-process {@code .prune.lock}, then stream the shared
     * {@link build.jumpkick.runtime.CachePipelines} pipeline — {@link EngineProtocol#TEST_REQUEST}'s wire shape
     * with a {@link EngineProtocol#pipelineFinishCache} terminal.
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
                        build.jumpkick.run.Pipeline pipeline =
                                switch (op) {
                                    case "purge" -> build.jumpkick.runtime.CachePipelines.purgePipeline(cache);
                                    case "gc" -> build.jumpkick.runtime.CachePipelines.gcPipeline(cache);
                                    case "clear" -> build.jumpkick.runtime.CachePipelines.clearPipeline(
                                            cache, Path.of(Ndjson.str(requestLine, "dir")), dryRun);
                                    default -> build.jumpkick.runtime.CachePipelines.prunePipeline(
                                            cache,
                                            Ndjson.intValue(requestLine, "olderThanDays", 30),
                                            dryRun,
                                            Ndjson.bool(requestLine, "sweep", false),
                                            Ndjson.str(requestLine, "maxSize"),
                                            Ndjson.bool(requestLine, "includeJkTmp", false));
                                };
                        Session session = Session.defaults().withCacheDir(cache).withCancel(cancelToken);
                        String dir = EngineProtocol.SINGLE_PIPELINE_DIR;
                        streamSinglePipeline(pipeline, session, writer, result -> EngineProtocol.pipelineFinishCache(
                                dir,
                                result.success(),
                                pipeline.get(build.jumpkick.runtime.CachePipelines.FILES).orElse(-1L),
                                pipeline.get(build.jumpkick.runtime.CachePipelines.BYTES).orElse(-1L),
                                pipeline.get(build.jumpkick.runtime.CachePipelines.REACHABLE_EVICTED)
                                        .orElse(-1L),
                                pipeline.get(build.jumpkick.runtime.CachePipelines.REPO_LINKS)
                                        .orElse(-1L)));
                    } finally {
                        pruneLock.release();
                    }
                }
            } finally {
                cacheGate.writeLock().unlock();
            }
        } catch (Exception e) {
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Decode a {@link EngineProtocol#NATIVE_REQUEST} and run {@code jk native}'s serial module
     * cascade in-session, speaking {@link EngineProtocol#BUILD_REQUEST}'s workspace event
     * vocabulary (a single project is a cascade of one): a full plan burst first (so the client
     * calibrates its aggregate bar to the whole-workspace weight up front), then each module's pipeline
     * — the {@code native-image} child process forking engine-side — stopping at the first failure.
     * Exit codes are computed here ({@link build.jumpkick.runtime.NativePipelines#failureExitCode}) and
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
            java.util.Map<Path, Path> graalByDir = new java.util.HashMap<>();
            Ndjson.strMap(requestLine, "graalHomes")
                    .forEach((d, h) -> graalByDir.put(Path.of(d), Path.of(h)));
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
            sendQuiet(writer, EngineProtocol.requestFailed(String.valueOf(e.getMessage())));
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
                    false, build.jumpkick.model.command.Exit.CONFIG, java.util.List.of(String.valueOf(e.getMessage()))));
            return;
        }

        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        if (root.isWorkspaceRoot()) {
            java.util.Map<Path, JkBuild> modulesByDir;
            try {
                modulesByDir = build.jumpkick.config.WorkspaceLoader.loadModules(entryDir, root);
            } catch (RuntimeException | IOException e) {
                sendQuiet(writer, EngineProtocol.workspaceFinish(
                        false,
                        build.jumpkick.model.command.Exit.CONFIG,
                        java.util.List.of(String.valueOf(e.getMessage()))));
                return;
            }
            for (Path dir : build.jumpkick.runtime.BuildGraph.orderModules(modulesByDir)) {
                scopes.put(dir, modulesByDir.get(dir));
            }
        } else {
            scopes.put(entryDir, root);
        }

        // Assemble every module's pipeline up front and send the whole plan burst first, so the
        // client's aggregate bar calibrates to the workspace total before any module runs.
        var pipelines = new java.util.LinkedHashMap<Path, build.jumpkick.run.Pipeline>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            build.jumpkick.run.Pipeline pipeline = build.jumpkick.runtime.NativePipelines.modulePipeline(
                    dir,
                    scope.getValue(),
                    cache,
                    jdksDir,
                    graalByDir.get(dir),
                    mainClass,
                    extraArgs,
                    skipTests,
                    verbose);
            pipelines.put(dir, pipeline);
            coords.put(dir, build.jumpkick.runtime.LockPipelines.coordLabel(scope.getValue(), dir));
        }
        for (var entry : pipelines.entrySet()) {
            String dirTag = entry.getKey().toString();
            build.jumpkick.run.Pipeline pipeline = entry.getValue();
            sendQuiet(writer, EngineProtocol.planModule(
                    dirTag,
                    coords.get(entry.getKey()),
                    pipeline.name(),
                    (int) Math.min(Integer.MAX_VALUE, pipeline.estimatedTotalWeight()),
                    false));
            for (Step p : pipeline.steps()) {
                sendQuiet(writer, EngineProtocol.planStep(dirTag, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
        }
        sendQuiet(writer, EngineProtocol.planDone(pipelines.size()));

        for (var entry : pipelines.entrySet()) {
            Path dir = entry.getKey();
            String dirTag = dir.toString();
            build.jumpkick.run.Pipeline pipeline = entry.getValue();
            sendQuiet(writer, EngineProtocol.moduleStart(dirTag));
            pipeline.addListener(wirePipelineListener(dirTag, writer, pipeline));
            long startNanos = System.nanoTime();
            PipelineResult result = pipeline.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000;
            int exitCode = result.success() ? 0 : build.jumpkick.runtime.NativePipelines.failureExitCode(pipeline, result);
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
     * manifest, apply workspace context, then run one {@link build.jumpkick.runtime.LockPipelines} pipeline per
     * scope (entry project first, then each workspace module in declaration order), stopping at the
     * first failure. Exit codes are computed here — the engine saw the step statuses — and sent on
     * the {@link EngineProtocol#LOCK_FINISH} terminal; pre-pipeline failures (manifest parse, module
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
                    false, build.jumpkick.model.command.Exit.CONFIG,
                    java.util.List.of(String.valueOf(e.getMessage())), -1));
            return;
        }
        JkBuild effectiveRoot = build.jumpkick.runtime.LockPipelines.applyWorkspaceContextIfModule(entryDir, root);

        var scopes = new java.util.LinkedHashMap<Path, JkBuild>();
        var coords = new java.util.LinkedHashMap<Path, String>();
        scopes.put(entryDir, effectiveRoot);
        coords.put(entryDir, build.jumpkick.runtime.LockPipelines.coordLabel(effectiveRoot, entryDir));
        if (effectiveRoot.isWorkspaceRoot()) {
            java.util.Map<Path, JkBuild> modules;
            try {
                modules = build.jumpkick.config.WorkspaceLoader.loadModules(entryDir, effectiveRoot);
            } catch (RuntimeException e) {
                sendQuiet(writer, EngineProtocol.lockFinish(
                        false, build.jumpkick.model.command.Exit.CONFIG,
                        java.util.List.of(String.valueOf(e.getMessage())), -1));
                return;
            }
            for (var entry : modules.entrySet()) {
                scopes.put(
                        entry.getKey(),
                        build.jumpkick.model.WorkspaceMerge.applyToModule(effectiveRoot, entry.getValue(), modules.values()));
                coords.put(entry.getKey(), build.jumpkick.runtime.LockPipelines.coordLabel(entry.getValue(), entry.getKey()));
            }
        }

        for (var scope : scopes.entrySet()) {
            Path dir = scope.getKey();
            String dirTag = dir.toString();
            sendQuiet(writer, EngineProtocol.lockModule(dirTag, coords.get(dir)));

            build.jumpkick.resolver.ResolveObserver observer = new build.jumpkick.resolver.ResolveObserver() {
                @Override
                public void onTotal(int total) {
                    // tick growth already rides the pipeline's tick-update events
                }

                @Override
                public void onPackage(String module, String version) {
                    sendQuiet(writer, EngineProtocol.lockPackage(dirTag, module, version));
                }
            };
            build.jumpkick.run.Pipeline pipeline = update
                    ? build.jumpkick.runtime.LockPipelines.updatePipeline(dir, scope.getValue(), cache, repoUrl, features, withDefaults)
                    : build.jumpkick.runtime.LockPipelines.lockPipeline(
                            dir, scope.getValue(), cache, repoUrl, features, withDefaults, sources, observer, null);
            for (Step p : pipeline.steps()) {
                sendQuiet(writer, EngineProtocol.planStep(dirTag, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
            }
            sendQuiet(writer, EngineProtocol.planDone(1));
            pipeline.addListener(wirePipelineListener(dirTag, writer, (java.util.function.Function<PipelineResult, String>)
                    result -> {
                        build.jumpkick.lock.Lockfile lock =
                                pipeline.get(build.jumpkick.runtime.LockPipelines.LOCKFILE).orElse(null);
                        return EngineProtocol.pipelineFinishLock(
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

            PipelineResult result = pipeline.run();
            if (!result.success()) {
                sendQuiet(writer, EngineProtocol.lockFinish(
                        false, build.jumpkick.runtime.LockPipelines.failureExitCode(result), java.util.List.of(), -1));
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
        Path entryDir = Path.of(Ndjson.str(requestLine, "dir"));
        Path cache = Path.of(Ndjson.str(requestLine, "cache"));
        JkConfig config = new JkConfig(
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "offline", false)),
                Optional.of(Ndjson.bool(requestLine, "rebuild", false)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "verbose", false)),
                Optional.empty(),
                Optional.of(Ndjson.bool(requestLine, "force", false) || refresh),
                Optional.empty());
        return Session.defaults()
                .withConfig(config)
                .withWorkingDir(entryDir)
                .withCacheDir(cache)
                .withCancel(cancelToken)
                .withJvm(EngineProtocol.jvmTuning(requestLine))
                // The variant selection rides the session: every pipeline factory's Inputs defaults
                // from it, so compile/install/native/publish/... are parameterized generically.
                .withVariant(EngineProtocol.variantOf(requestLine), EngineProtocol.clientEnvOf(requestLine));
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
        // Each module's pipeline, kept from onModuleStart so onModuleFinish can read its TEST_RESULT and
        // fold per-module test counts into the run's record — the workspace path has no single test
        // pipeline, so tests would otherwise never reach a dashboard-triggered build's history.
        java.util.Map<String, build.jumpkick.run.Pipeline> modulePipelines = new java.util.concurrent.ConcurrentHashMap<>();
        return new WorkspaceBuildListener() {
            @Override
            public void onPlan(java.util.List<ModulePlan> plan) {
                for (ModulePlan m : plan) {
                    String dir = m.dir().toString();
                    sendQuiet(
                            writer,
                            EngineProtocol.planModule(dir, m.coord(), m.pipeline().name(), m.weight(), m.fullyCached()));
                    for (Step p : m.pipeline().steps()) {
                        sendQuiet(writer, EngineProtocol.planStep(dir, p.name(), p.label(), phaseWire(p.phase().orElse(null))));
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
            public PipelineListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                modulePipelines.put(dir, m.pipeline()); // read its TEST_RESULT at finish (see onModuleFinish)
                sendQuiet(writer, EngineProtocol.moduleStart(dir));
                publishModuleStart(eventRequestId, dir, m.coord());
                // wirePipelineListener captures the dashboard request id from the currentEventRequestId
                // ThreadLocal — but onModuleStart runs on a WorkspaceScheduler thread where it isn't
                // set, so without this seed every per-module step/pipeline-progress hub event would
                // publish under id -1 and be dropped (no per-module chains or weight bar). Seed it
                // with the request id captured on the request thread when wireListener was created.
                Long prev = currentEventRequestId.get();
                currentEventRequestId.set(eventRequestId);
                try {
                    return wirePipelineListener(dir, writer, (build.jumpkick.run.Pipeline) null);
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
                build.jumpkick.run.Pipeline g = modulePipelines.remove(o.dir().toString());
                if (g != null) {
                    accTests(eventRequestId, g.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
                }
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
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("coord", coord));
    }

    private void publishModuleFinish(long requestId, String dir, String coord, boolean success, long millis) {
        if (!eventsWanted()) return;
        publishEvent(
                "module-finish",
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("coord", coord)
                        .put("success", success)
                        .put("millis", millis));
    }

    private void publishPipelineFinish(long requestId, String dir, boolean success) {
        if (!eventsWanted()) return;
        publishEvent(
                "pipeline-finish",
                build.jumpkick.engine.http.JsonOut.object()
                        .put("requestId", requestId)
                        .put("dir", dir)
                        .put("success", success));
    }

    // ---- build-history journal capture (docs: state/builds) ---------------------

    /** Request kinds we journal — the actual "build" commands; lock/sync/tool/etc. are not history. */
    private static final java.util.Set<String> JOURNALED_KINDS = java.util.Set.of("build", "test");

    /**
     * Open an accumulator for a journaled build kind (no-op for other kinds). Always on — even with
     * history disabled the accumulator feeds the running {@link BuildMetrics}; only the journal
     * append itself is gated on {@code historyConfig.enabled()}.
     */
    private void registerAccumulator(long requestId, String kind, String dir, String trigger) {
        if (!JOURNALED_KINDS.contains(kind)) return;
        accumulators.put(requestId, new BuildAccumulator(kind, dir, coordOf(dir), trigger));
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

    private void accPipelineFinish(long requestId, String dir, PipelineResult result) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addPipeline(dir, result);
    }

    /**
     * Record one finished step under its module dir — the same {@code stepFinish} signal the
     * dashboard renders, so the journal's per-module chains match the live cards exactly (a
     * workspace module's {@code PipelineResult.steps()} isn't reliably populated, so we capture the
     * events directly).
     */
    private void accStepFinish(long requestId, String dir, String step, String phase, String status, long millis) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null) a.addStep(dir, step, phase, status, millis);
    }

    private void accTests(long requestId, TestSummary tests) {
        BuildAccumulator a = accumulators.get(requestId);
        if (a != null && tests != null) a.addTests(tests);
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
            String commit = gitCommit(a.dir()); // best-effort short SHA of the project's HEAD
            BuildRecord record = a.toRecord(finishedAt, cancelled, millis, version, commit);
            // Folding metrics also mints this run's durable per-project build number; stamp it onto
            // the record so the journal (and the dashboard's #NNN pill) carry it. Metrics is folded
            // even when history is disabled, so the counter stays consistent regardless.
            long buildNumber = BuildMetrics.record(metricsFile, toOutcome(record), finishedAt);
            record = record.withBuildNumber(buildNumber);
            if (!historyConfig.enabled()) return;
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

    /**
     * The project's git HEAD as a short SHA, or {@code null} when the dir isn't a git repo, git isn't
     * on PATH, or the call errors/times out. Best-effort and non-blocking-ish (1s cap): a commit stamp
     * is a nice-to-have on the history record, never worth failing or stalling journaling.
     */
    private static String gitCommit(String dir) {
        if (dir == null || dir.isEmpty()) return null;
        try {
            Process p = new ProcessBuilder("git", "-C", dir, "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(false)
                    .start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Map a finished run's record into the running-metrics input shape: the invocation outcome plus
     * every per-module step (workspace) and top-level step (single-pipeline, whose steps carry the
     * record's own dir). Keeps journal types out of {@code build.jumpkick.runtime}.
     */
    private static BuildMetrics.Outcome toOutcome(BuildRecord r) {
        java.util.ArrayList<BuildMetrics.StepSample> steps = new java.util.ArrayList<>();
        for (BuildRecord.Step p : r.steps()) {
            steps.add(new BuildMetrics.StepSample(r.dir(), p.name(), p.status(), p.millis()));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Step p : m.steps()) {
                steps.add(new BuildMetrics.StepSample(m.dir(), p.name(), p.status(), p.millis()));
            }
        }
        return new BuildMetrics.Outcome(
                r.kind(), r.dir(), r.coord(), r.success(), r.cancelled(), r.millis(), steps);
    }

    /**
     * {@code metrics-request} → one flat {@code metrics-entry} per aggregate row, then
     * {@code metrics-done}. An optional {@code dir} keeps only that project's rows (the global
     * tiers are always included so the client can render its summary alongside).
     */
    private void handleMetrics(String requestLine, BufferedWriter writer) throws IOException {
        String dirFilter = Ndjson.str(requestLine, "dir");
        int n = 0;
        for (BuildMetrics.Entry e : BuildMetrics.load(metricsFile).entries()) {
            if (dirFilter != null && !e.dir().isEmpty() && !e.dir().equals(dirFilter)) continue;
            send(writer, metricsEntryJson(e));
            n++;
        }
        send(writer, JsonOut.object().put("t", EngineProtocol.METRICS_DONE).put("count", n).toString());
    }

    /** One aggregate row as a flat wire object; avg is pre-computed so clients stay arithmetic-free. */
    private static String metricsEntryJson(BuildMetrics.Entry e) {
        boolean global = e.dir().isEmpty();
        String scope = e.step() == null ? (global ? "global" : "project") : (global ? "step" : "project/step");
        return JsonOut.object()
                .put("t", EngineProtocol.METRICS_ENTRY)
                .put("scope", scope)
                .put("kind", e.kind())
                .put("dir", e.dir())
                .put("coord", e.coord())
                .put("step", e.step())
                .put("okCount", e.ok().count())
                .put("okTotalMillis", e.ok().totalMillis())
                .put("okMinMillis", e.ok().minMillis())
                .put("okMaxMillis", e.ok().maxMillis())
                .put("okAvgMillis", e.ok().avgMillis())
                .put("failCount", e.failed().count())
                .put("failTotalMillis", e.failed().totalMillis())
                .put("failMinMillis", e.failed().minMillis())
                .put("failMaxMillis", e.failed().maxMillis())
                .put("cancelledCount", e.cancelled().count())
                .put("updated", e.updatedMillis())
                .toString();
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

    /** {@code history-show-request} → a {@code history-record} header + module/step/diag rows + {@code history-done}. */
    private void handleHistoryShow(String requestLine, BufferedWriter writer) throws IOException {
        String id = Ndjson.str(requestLine, "id");
        java.util.Optional<BuildRecord> found = id == null ? java.util.Optional.empty() : journal.get(id);
        if (found.isEmpty()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.ERROR)
                    .put("code", EngineProtocol.ERR_REQUEST_FAILED)
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
        int stepCount = 0;
        for (BuildRecord.Module m : r.modules()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_MODULE)
                    .put("coord", m.coord())
                    .put("dir", m.dir())
                    .put("success", m.success())
                    .put("exitCode", m.exitCode())
                    .put("millis", m.millis())
                    .toString());
            // Each module's own step chain, tagged with the module so the CLI can group them.
            String label = m.coord() != null ? m.coord() : m.dir();
            for (BuildRecord.Step p : m.steps()) {
                send(writer, stepLine(p, label));
                stepCount++;
            }
        }
        // Single-pipeline builds carry their steps at the record's top level (no module rows).
        for (BuildRecord.Step p : r.steps()) {
            send(writer, stepLine(p, null));
            stepCount++;
        }
        for (BuildRecord.Diag d : r.diagnostics()) {
            send(writer, JsonOut.object()
                    .put("t", EngineProtocol.HISTORY_DIAG)
                    .put("severity", d.severity())
                    .put("step", d.step())
                    .put("code", d.code())
                    .put("message", d.message())
                    .put("test", d.test())
                    .put("exceptionClass", d.exceptionClass())
                    .toString());
        }
        send(writer, JsonOut.object()
                .put("t", EngineProtocol.HISTORY_DONE)
                .put("count", r.modules().size() + stepCount + r.diagnostics().size())
                .toString());
    }

    /** A {@code history-step} line, optionally tagged with its module label (null for single-pipeline). */
    private static String stepLine(BuildRecord.Step p, String module) {
        return JsonOut.object()
                .put("t", EngineProtocol.HISTORY_STEP)
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
     * Translate every {@link PipelineListener} callback for one pipeline into a {@code dir}-tagged wire
     * event. {@code realPipeline} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build steps)
     * ride along on the {@link EngineProtocol#PIPELINE_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build pipeline (where neither applies at the module level).
     */
    private PipelineListener wirePipelineListener(String dir, BufferedWriter writer, build.jumpkick.run.Pipeline realPipeline) {
        return wirePipelineListener(dir, writer, (java.util.function.Function<PipelineResult, String>) result -> {
            build.jumpkick.run.TestSummary testResult = realPipeline == null
                    ? null
                    : realPipeline.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null);
            String buildOutcome = realPipeline == null
                    ? null
                    : realPipeline.get(build.jumpkick.runtime.BuildPipelines.BUILD_OUTCOME).orElse(null);
            return testResult == null && buildOutcome == null
                    ? EngineProtocol.pipelineFinish(dir, result.success())
                    : EngineProtocol.pipelineFinish(
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
     * As {@link #wirePipelineListener(String, BufferedWriter, build.jumpkick.run.Pipeline)}, but with a
     * pluggable terminal encoder: {@code finishEncoder} maps the finished {@link PipelineResult} to the
     * {@link EngineProtocol#PIPELINE_FINISH} message to send (after the {@link
     * EngineProtocol#PIPELINE_DIAGNOSTIC} burst) — how lock/update/sync ride their summary counts on the
     * same message the build/test pipelines already send.
     */
    private PipelineListener wirePipelineListener(
            String dir, BufferedWriter writer, java.util.function.Function<PipelineResult, String> finishEncoder) {
        // Created on the runner's thread (directly, or via wireListener's onModuleStart which runs
        // on a scheduler thread — there the ThreadLocal is unset and module events carry the id).
        long eventRequestId = eventRequestId();
        return new PipelineListener() {
            @Override
            public void pipelineStart(PipelineView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.pipelineStart(
                                dir,
                                view.pipelineName(),
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishPipelineProgress(eventRequestId, dir, view);
            }

            @Override
            public void stepStart(String step, build.jumpkick.plugin.build.Phase phase, int ticks) {
                sendQuiet(writer, EngineProtocol.stepStart(dir, step, phaseWire(phase), ticks));
                publishStepStart(eventRequestId, dir, step, phaseWire(phase));
            }

            @Override
            public void progress(String step, int delta, PipelineView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.progress(
                                dir,
                                step,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishPipelineProgress(eventRequestId, dir, view);
            }

            @Override
            public void tickUpdate(String step, int delta, PipelineView view) {
                sendQuiet(
                        writer,
                        EngineProtocol.tickUpdate(
                                dir,
                                step,
                                delta,
                                view.numerator(),
                                view.denominator(),
                                view.stepsTotal(),
                                view.stepsComplete(),
                                view.cancelled()));
                publishPipelineProgress(eventRequestId, dir, view);
            }

            @Override
            public void label(String step, String label) {
                sendQuiet(writer, EngineProtocol.label(dir, step, label));
            }

            @Override
            public void output(String step, String line) {
                sendQuiet(writer, EngineProtocol.output(dir, step, line));
                publishOutput(eventRequestId, dir, step, line);
            }

            @Override
            public void warn(String step, String code, String message) {
                sendQuiet(writer, EngineProtocol.warn(dir, step, code, message));
            }

            @Override
            public void error(String step, String code, String message, String test, String exceptionClass) {
                sendQuiet(writer, EngineProtocol.errorLine(dir, step, code, message, test, exceptionClass));
            }

            @Override
            public void stepFinish(
                    String step, build.jumpkick.plugin.build.Phase phase, build.jumpkick.run.StepStatus status, Duration duration) {
                sendQuiet(writer, EngineProtocol.stepFinish(dir, step, phaseWire(phase), status.name()));
                publishStepFinish(eventRequestId, dir, step, phaseWire(phase), status.name());
                accStepFinish(eventRequestId, dir, step, phaseWire(phase), status.name(), duration.toMillis());
            }

            @Override
            public void pipelineFinish(PipelineResult result) {
                for (PipelineResult.Diagnostic d : result.errors()) {
                    sendQuiet(
                            writer,
                            EngineProtocol.pipelineDiagnostic(
                                    dir, d.step(), d.code(), d.message(), d.test(), d.exceptionClass()));
                }
                sendQuiet(writer, finishEncoder.apply(result));
                publishPipelineFinish(eventRequestId, dir, result.success());
                if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                accPipelineFinish(eventRequestId, dir, result);
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
        activeConnections.decrementAndGet();
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
                () -> BuildMetrics.load(metricsFile).entries(),
                () -> build.jumpkick.engine.http.CacheSnapshot.capture(build.jumpkick.util.JkDirs.cache()),
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
        if (draining) {
            throw new IllegalStateException("engine is shutting down");
        }
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
        registerAccumulator(eventRequestId, "build", entryDir.toString(), "web");
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
                        build.jumpkick.engine.http.JsonOut.object()
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
            Path cache = build.jumpkick.util.JkDirs.cache();
            Path jdksDir = build.jumpkick.util.JkDirs.jdks();
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

    /** Module/pipeline events to the dashboard hub only — the HTTP trigger's counterpart of {@link #wireListener}. */
    private WorkspaceBuildListener hubListener() {
        long eventRequestId = eventRequestId();
        // As in wireListener: keep each module's pipeline so onModuleFinish can fold its TEST_RESULT into
        // the record — a web-triggered build has no single test pipeline, so tests would otherwise never
        // reach the journal for dashboard builds.
        java.util.Map<String, build.jumpkick.run.Pipeline> modulePipelines = new java.util.concurrent.ConcurrentHashMap<>();
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
            public PipelineListener onModuleStart(ModulePlan m) {
                String dir = m.dir().toString();
                modulePipelines.put(dir, m.pipeline());
                publishModuleStart(eventRequestId, dir, m.coord());
                return new PipelineListener() {
                    @Override
                    public void pipelineStart(PipelineView view) {
                        publishPipelineProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void progress(String step, int delta, PipelineView view) {
                        publishPipelineProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void tickUpdate(String step, int delta, PipelineView view) {
                        publishPipelineProgress(eventRequestId, dir, view);
                    }

                    @Override
                    public void stepStart(String step, build.jumpkick.plugin.build.Phase phase, int ticks) {
                        publishStepStart(eventRequestId, dir, step, phaseWire(phase));
                    }

                    @Override
                    public void stepFinish(
                            String step, build.jumpkick.plugin.build.Phase phase, build.jumpkick.run.StepStatus status, Duration duration) {
                        publishStepFinish(eventRequestId, dir, step, phaseWire(phase), status.name());
                        accStepFinish(eventRequestId, dir, step, phaseWire(phase), status.name(), duration.toMillis());
                    }

                    @Override
                    public void output(String step, String line) {
                        publishOutput(eventRequestId, dir, step, line);
                    }

                    @Override
                    public void pipelineFinish(PipelineResult result) {
                        publishPipelineFinish(eventRequestId, dir, result.success());
                        if (!result.success()) publishDiagnostics(eventRequestId, dir, result.errors());
                        accPipelineFinish(eventRequestId, dir, result);
                    }
                };
            }

            @Override
            public void onModuleFinish(ModuleOutcome o) {
                publishModuleFinish(eventRequestId, o.dir().toString(), o.coord(), o.success(), o.millis());
                accModule(eventRequestId, o);
                build.jumpkick.run.Pipeline g = modulePipelines.remove(o.dir().toString());
                if (g != null) {
                    accTests(eventRequestId, g.get(build.jumpkick.runtime.BuildPipelines.TEST_RESULT).orElse(null));
                }
            }
        };
    }

    /** The one source of engine vitals — feeds both the socket {@code status-ack} and {@code /api/status}. */
    private build.jumpkick.engine.http.StatusSnapshot statusSnapshot() {
        Runtime rt = Runtime.getRuntime();
        long heapCommitted = rt.totalMemory();
        return new build.jumpkick.engine.http.StatusSnapshot(
                version,
                pid,
                startedAtMillis,
                activeConnections.get(),
                activePipelines.get(),
                heapCommitted - rt.freeMemory(),
                heapCommitted,
                rt.maxMemory(),
                MemoryProbe.ownRssBytes(),
                aotTrainingPid());
    }

    /** The sidecar AOT trainer's pid while one is alive, {@code -1} otherwise. */
    private long aotTrainingPid() {
        Process p = aotTrainer;
        return (p != null && p.isAlive()) ? p.pid() : -1;
    }

    /**
     * Install the sidecar AOT-trainer factory; must be called before {@link #run()}. The factory
     * is invoked once, only if this engine wins its election and starts serving; it may return
     * {@code null} (nothing to train after all — e.g. the cache appeared meanwhile).
     */
    public void aotTrainerSpawner(java.util.function.Supplier<Process> spawner) {
        this.aotTrainerSpawner = spawner;
    }

    /**
     * Spawn and adopt the sidecar AOT trainer. Best-effort: a trainer that fails to start (or
     * never finishes) costs a log line, never the engine. The trainer self-terminates in seconds;
     * the timeout is a belt against a hung child, generous enough to never fire on a healthy one.
     */
    private void startAotTrainerIfConfigured() {
        java.util.function.Supplier<Process> spawner = aotTrainerSpawner;
        if (spawner == null) return;
        try {
            Process p = spawner.get();
            if (p == null) return;
            aotTrainer = p;
            log.accept("jk engine: AOT training sidecar started (pid " + p.pid() + ")");
            p.onExit()
                    .orTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                    .whenComplete((proc, err) -> {
                        if (err != null) {
                            p.destroyForcibly();
                            log.accept("jk engine: AOT training sidecar overran; killed (pid " + p.pid() + ")");
                        } else {
                            log.accept("jk engine: AOT training sidecar finished (pid " + p.pid()
                                    + ", exit " + proc.exitValue() + ")");
                        }
                        aotTrainer = null;
                    });
        } catch (RuntimeException e) {
            log.accept("jk engine: AOT training sidecar failed to start: " + e.getMessage());
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
        deleteQuietly(paths.http()); // the live bound-URL file — stale once we stop
        // The http token is deliberately NOT deleted: it persists across restarts so an open
        // dashboard tab survives an upgrade/crash respawn (docs/http.md). `jk engine rotate-token`
        // is the explicit way to invalidate it.
        if (connectionExecutor != null) connectionExecutor.shutdown();
        try {
            if (connectionExecutor != null) connectionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (active != null) {
            deleteQuietly(active.socket());
            deleteQuietly(active.token());
            deleteQuietly(active.pid());
            // Retire the endpoint only if it still names US — a takeover successor owns it
            // now and must not be un-pointed by the lame duck's exit.
            try {
                Path ep = EnginePaths.endpoint(paths);
                String mine = active.socket().getFileName().toString();
                if (Files.isRegularFile(ep) && mine.equals(Files.readString(ep).trim())) {
                    deleteQuietly(ep);
                }
            } catch (IOException ignored) {
                // best-effort
            }
            try {
                if (genLock != null) genLock.release();
                if (genLockChannel != null) genLockChannel.close();
            } catch (IOException ignored) {
                // process exit releases it regardless
            }
            deleteQuietly(active.lock());
        }
        releaseStartupLock();
        deleteQuietly(paths.lock()); // the transient startup mutex file
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort cleanup — a leftover file is harmless (recreated/overwritten next start)
        }
    }

    private void writePidFile() throws IOException {
        Files.writeString(active.pid(), pid + "\n" + startedAtMillis + "\n", StandardCharsets.UTF_8);
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
     * PipelineListener} callbacks that fire on scheduler/worker threads, then frozen into a {@link
     * BuildRecord} at request-finish. Success is taken from the runner's terminal result when set,
     * else derived (no failed module/pipeline and not cancelled).
     */
    private static final class BuildAccumulator {
        private final String kind;
        private final String dir;
        private final String coord;
        private final String trigger; // how the build was started: "cli" (socket) or "web" (dashboard)
        private final java.util.List<ModuleOutcome> modules = new java.util.concurrent.CopyOnWriteArrayList<>();
        // Steps per module dir (name → Step, arrival order, last status wins). The single-pipeline path
        // uses the "" (SINGLE_PIPELINE_DIR) bucket; workspace modules use their real dir. Rendered as a
        // chain per module (the dashboard shows one chain per module, not one merged strip).
        private final java.util.Map<String, java.util.Map<String, BuildRecord.Step>> stepsByDir =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.List<BuildRecord.Diag> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile BuildRecord.Tests tests;
        private volatile boolean anyFailure;
        private volatile boolean userCancelled;
        private volatile Boolean success;
        private volatile int exitCode;

        BuildAccumulator(String kind, String dir, String coord, String trigger) {
            this.kind = kind;
            this.dir = dir;
            this.coord = coord;
            this.trigger = trigger;
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

        /** One finished step, stored under its module dir ("" for a single-pipeline build). */
        void addStep(String dir, String step, String phase, String status, long millis) {
            stepsByDir
                    .computeIfAbsent(dir == null ? "" : dir,
                            k -> java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>()))
                    .put(step, new BuildRecord.Step(step, phase, status, millis));
        }

        /** Diagnostics + failure flag from a finished pipeline (steps come from {@link #addStep}). */
        void addPipeline(String dir, PipelineResult result) {
            String d0 = dir == null ? "" : dir;
            for (PipelineResult.Diagnostic d : result.errors()) {
                diagnostics.add(new BuildRecord.Diag(
                        "error", d0, d.step(), d.code(), d.message(), d.test(), d.exceptionClass()));
            }
            for (PipelineResult.Diagnostic d : result.warnings()) {
                diagnostics.add(new BuildRecord.Diag(
                        "warning", d0, d.step(), d.code(), d.message(), d.test(), d.exceptionClass()));
            }
            if (!result.success()) anyFailure = true;
            if (result.userCancelled()) userCancelled = true;
        }

        private java.util.List<BuildRecord.Step> stepsFor(String dir) {
            java.util.Map<String, BuildRecord.Step> m = stepsByDir.get(dir == null ? "" : dir);
            if (m == null) return java.util.List.of();
            synchronized (m) {
                return new java.util.ArrayList<>(m.values());
            }
        }

        /**
         * Fold in one pipeline's test summary. Single-pipeline {@code jk test}/{@code 1build} call this once;
         * a workspace build calls it per module (each module's {@code TEST_RESULT}), so the counts
         * accumulate into the run's total rather than the last module overwriting the rest.
         */
        synchronized void addTests(TestSummary t) {
            if (t == null) return;
            tests = tests == null
                    ? new BuildRecord.Tests(t.total(), t.succeeded(), t.failed(), t.skipped())
                    : new BuildRecord.Tests(
                            tests.total() + t.total(),
                            tests.succeeded() + t.succeeded(),
                            tests.failed() + t.failed(),
                            tests.skipped() + t.skipped());
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
                if (notBlank(d.step())) b.append(d.step()).append(": ");
                if (notBlank(d.test())) b.append(d.test()).append(" — ");
                if (notBlank(d.exceptionClass())) b.append('(').append(d.exceptionClass()).append(") ");
                b.append(d.message() == null ? "" : d.message()).append('\n');
            }
            return b.toString();
        }

        BuildRecord toRecord(long finishedAt, boolean cancelled, long millis, String jkVersion, String commit) {
            boolean ok = success != null ? success : (!anyFailure && !cancelled);
            int exit = success != null ? exitCode : (ok ? 0 : 1);
            // A build that reported success was not cancelled: cancelToken.cancelled() also fires on
            // the benign end-of-request EOF (the client closes the socket the instant it reads the
            // terminal message, which can land just before the runner marks itself done), so trust
            // the outcome over that flag and never label a successful run "cancelled".
            boolean cancelledEffective = cancelled && !ok;
            // Each workspace module carries its own step chain (keyed by its dir); a single-pipeline
            // build has no module rows, so its steps live in the record's top-level list (the ""
            // bucket). This is exactly the two shapes the dashboard renders (per-module vs compact).
            java.util.List<BuildRecord.Module> moduleList = new java.util.ArrayList<>();
            for (ModuleOutcome o : modules) {
                String mdir = o.dir() == null ? "" : o.dir().toString();
                moduleList.add(new BuildRecord.Module(
                        o.coord(), mdir, o.success(), o.exitCode(), o.millis(), stepsFor(mdir)));
            }
            java.util.List<BuildRecord.Step> topSteps = moduleList.isEmpty() ? stepsFor("") : java.util.List.of();
            return new BuildRecord(
                    null, 0L, BuildRecord.SCHEMA, kind, dir, coord,
                    finishedAt - millis, finishedAt, millis,
                    ok, cancelledEffective, exit, jkVersion,
                    tests, moduleList, topSteps, new java.util.ArrayList<>(diagnostics), trigger, commit);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
