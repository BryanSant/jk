// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkDaemonConfig;
import dev.jkbuild.config.Session;
import dev.jkbuild.config.SessionContext;
import dev.jkbuild.daemon.protocol.DaemonProtocol;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.runtime.WorkspaceBuildListener;
import dev.jkbuild.worker.HeapPlan;
import dev.jkbuild.worker.JvmOptions;
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
 * The daemon's server loop: single-instance election, socket bind, an accept loop that serves the
 * Phase 1 handshake/liveness/status/shutdown protocol on a virtual thread per connection, and the
 * idle-timeout policy. See {@code docs/daemon.md}.
 *
 * <p>Not yet hosting any real engine operation (no {@code buildWorkspace} dispatch) — that lands once
 * the wire protocol grows request/event types. This class only proves the daemon can come up, be
 * discovered, report itself, and go back down, either on request or after being idle.
 */
public final class DaemonServer implements AutoCloseable {

    /** How often the idle-timeout check runs, in real (non-test) operation. */
    private static final long DEFAULT_TICK_MILLIS = 30_000;

    private final DaemonPaths.Paths paths;
    private final JkDaemonConfig config;
    private final String version;
    private final Consumer<String> log;
    private final long tickMillis;
    private final LongSupplier clockMillis;
    private final long pid;
    private final long startedAtMillis;

    private final Object lifecycleLock = new Object();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private volatile boolean shuttingDown;
    private volatile boolean hadActivity;
    private volatile long lastActivityAtMillis;

    private FileChannel lockChannel;
    private FileLock lock;
    private ServerSocketChannel serverChannel;
    private ExecutorService connectionExecutor;
    private ScheduledExecutorService idleTicker;

    /** Non-null only on the loopback-TCP transport (Windows) — see {@link DaemonTransport}. */
    private String expectedToken;

    public DaemonServer(DaemonPaths.Paths paths, JkDaemonConfig config, String version, Consumer<String> log) {
        this(paths, config, version, log, DEFAULT_TICK_MILLIS, System::currentTimeMillis);
    }

    /** Test seam: a short tick interval and an injectable clock, so idle-timeout tests don't sleep for real minutes. */
    DaemonServer(
            DaemonPaths.Paths paths,
            JkDaemonConfig config,
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
     * Try to become the daemon and serve until shutdown. Returns {@code false} immediately, having
     * touched nothing but the lock file, if another daemon already holds {@link
     * DaemonPaths.Paths#lock()} — the caller (a losing spawn-race participant) should treat that as
     * success-by-proxy, not an error. Blocks until the server stops (idle timeout, an explicit {@link
     * DaemonProtocol#SHUTDOWN}, or {@link #close()}), then returns {@code true}.
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
            return false; // another daemon already won the election
        }

        // A stale socket file (left by a killed process) makes bind() fail with "address already in
        // use" even though nothing is listening — safe to remove now: winning the lock proves no live
        // daemon owns it.
        Files.deleteIfExists(paths.socket());
        Files.deleteIfExists(paths.token());

        if (DaemonTransport.useLoopbackTcp()) {
            // Windows: no dependable Unix-domain-socket support — bind an ephemeral loopback TCP
            // port instead, and gate every connection on a shared secret (see DaemonTransport),
            // since a TCP port (unlike a socket file) isn't filesystem-permission-gated by default.
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0));
            int port = ((java.net.InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            expectedToken = DaemonTransport.newToken();
            Files.writeString(paths.token(), expectedToken);
            Files.writeString(paths.socket(), Integer.toString(port));
        } else {
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            serverChannel.bind(UnixDomainSocketAddress.of(paths.socket()));
        }
        writePidFile();

        connectionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("jk-daemon-conn-", 0).factory());
        lastActivityAtMillis = clockMillis.getAsLong();
        startIdleTickerIfNeeded();
        planSharedWorkerMemoryOnce();

        log.accept("jk daemon: listening on " + paths.socket() + " (pid " + pid + ")");
        acceptLoop();
        cleanup();
        log.accept("jk daemon: stopped");
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
                log.accept("jk daemon: accept failed: " + e.getMessage());
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

    /** Loopback-TCP transport only: the connection's first line must be a matching {@link DaemonProtocol#AUTH}. */
    private boolean authenticate(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line != null
                && DaemonProtocol.AUTH.equals(DaemonProtocol.typeOf(line))
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
                String type = DaemonProtocol.typeOf(line);
                if (type == null) continue; // ignore malformed/blank lines
                switch (type) {
                    case DaemonProtocol.HELLO -> send(writer, DaemonProtocol.helloAck(version, pid, startedAtMillis));
                    case DaemonProtocol.PING -> send(writer, DaemonProtocol.pong());
                    case DaemonProtocol.STATUS -> send(
                            writer,
                            DaemonProtocol.statusAck(
                                    version, pid, startedAtMillis, config.idleMinutes(), activeConnections.get()));
                    case DaemonProtocol.SHUTDOWN -> {
                        send(writer, DaemonProtocol.bye());
                        synchronized (lifecycleLock) {
                            shuttingDown = true;
                            closeServerChannelQuietly();
                        }
                        return;
                    }
                    case DaemonProtocol.BUILD_REQUEST -> {
                        // Owns the rest of this connection's lifecycle: forks the build onto its own
                        // thread and keeps reading this loop for a build-cancel/EOF while it runs.
                        handleBuildRequest(line, reader, writer);
                        return;
                    }
                    case DaemonProtocol.TEST_REQUEST -> {
                        // Same shape as BUILD_REQUEST but for a single project's test goal (Phase 3).
                        handleTestRequest(line, reader, writer);
                        return;
                    }
                    case DaemonProtocol.SINGLE_BUILD_REQUEST -> {
                        // Same shape as TEST_REQUEST but a real (non-testOnly) build goal.
                        handleSingleBuildRequest(line, reader, writer);
                        return;
                    }
                    case DaemonProtocol.EXPLAIN_REQUEST -> {
                        // Synchronous read, no worker JVM forked — handled inline, connection continues.
                        handleExplainRequest(line, writer);
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
     * {@link DaemonProtocol#BUILD_CANCEL} or EOF meanwhile) and stream every {@link
     * WorkspaceBuildListener}/{@link GoalListener} callback back as a wire event. Returns once the
     * build finishes and its terminal message has been sent, or the connection drops.
     */
    private void handleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-daemon-build-", this::runBuild);
    }

    /** A daemon-hosted operation's body: decode the request, run it, stream events to {@code writer}. */
    @FunctionalInterface
    private interface GoalRunner {
        void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer);
    }

    /**
     * Fork {@code runner} onto its own thread (so this method can keep reading the connection for a
     * {@link DaemonProtocol#BUILD_CANCEL} or EOF meanwhile) and wait for it to finish. Shared by every
     * request type that owns the rest of its connection's lifecycle ({@link #handleBuildRequest},
     * {@link #handleTestRequest}, {@link #handleSingleBuildRequest}) — they differ only in what
     * {@code runner} actually builds and runs.
     */
    private void handleAsyncGoalRequest(
            String requestLine, BufferedReader reader, BufferedWriter writer, String threadPrefix, GoalRunner runner) {
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().name(threadPrefix, 0).start(() -> {
            try {
                runner.run(requestLine, cancelToken, writer);
            } finally {
                done.countDown();
            }
        });
        try {
            String line;
            while (done.getCount() > 0 && (line = reader.readLine()) != null) {
                if (DaemonProtocol.BUILD_CANCEL.equals(DaemonProtocol.typeOf(line))) {
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
    }

    /** Decode the request, reconstruct a {@link Session}/{@link BuildService.WorkspaceRequest}, and run it. */
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

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;

            JkBuild entryBuild = JkBuildParser.parse(entryDir.resolve("jk.toml"));
            BuildService.WorkspaceRequest req = new BuildService.WorkspaceRequest(
                    entryDir,
                    entryBuild,
                    cache,
                    jdksDir,
                    workers,
                    profile,
                    skipTests,
                    verbose,
                    maxModuleConcurrency,
                    null, // let the engine forecast dirty modules itself — see docs/daemon.md
                    false); // this daemon plans memory once at startup, not per request

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
                    .withParallelTests(parallelTests)
                    .withCancel(cancelToken);

            WorkspaceBuildListener listener = wireListener(writer);
            BuildService.WorkspaceResult result =
                    SessionContext.where(session, () -> BuildService.buildWorkspace(req, listener));
            send(writer, DaemonProtocol.workspaceFinish(result.success(), result.exitCode(), result.errors()));
        } catch (Exception e) {
            sendQuiet(writer, DaemonProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * As {@link #handleBuildRequest}, but for a single project's test goal (Phase 3): forks the run
     * onto its own thread and keeps reading the connection for a cancel/EOF meanwhile.
     */
    private void handleTestRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-daemon-test-", this::runTest);
    }

    /**
     * As {@link #handleTestRequest}, but for a single (non-workspace) project's real build goal — the
     * daemon-hosted counterpart of {@code BuildCommand.runForDir}.
     */
    private void handleSingleBuildRequest(String requestLine, BufferedReader reader, BufferedWriter writer) {
        handleAsyncGoalRequest(requestLine, reader, writer, "jk-daemon-1build-", this::runSingleBuild);
    }

    /**
     * Forecast a build via {@link BuildService#explain} and stream the plan as a burst of
     * module/phase/edge messages — the daemon-hosted counterpart of {@code ExplainCommand}'s call
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
            BuildService.ExplainPlan plan = BuildService.explain(entryDir, entryBuild, cache);
            if (plan.hasErrors()) {
                for (String err : plan.errors()) {
                    sendQuiet(writer, DaemonProtocol.explainError(err));
                }
                sendQuiet(writer, DaemonProtocol.explainDone(1, 0));
                return;
            }
            for (dev.jkbuild.runtime.BuildPlanForecast.Module m : plan.modules()) {
                String dir = m.dir().toString();
                sendQuiet(
                        writer,
                        DaemonProtocol.explainModule(
                                dir, m.coord(), m.sourceCount(), m.testCount(), m.producesJar(), m.producesImage()));
                for (dev.jkbuild.runtime.BuildPlanForecast.Phase p : m.phases()) {
                    sendQuiet(
                            writer,
                            DaemonProtocol.explainPhase(
                                    dir, p.name(), p.status().name(), p.text(), p.key()));
                }
            }
            for (var e : plan.edges().entrySet()) {
                for (Path dep : e.getValue()) {
                    sendQuiet(writer, DaemonProtocol.explainEdge(e.getKey().toString(), dep.toString()));
                }
            }
            sendQuiet(writer, DaemonProtocol.explainDone(plan.maxReadyWidth(), plan.modules().size()));
        } catch (Exception e) {
            sendQuiet(writer, DaemonProtocol.explainError(String.valueOf(e.getMessage())));
            sendQuiet(writer, DaemonProtocol.explainDone(0, 0));
        }
    }

    /**
     * Build and run the test-only {@code Goal} exactly as {@code TestCommand} does in-process, but
     * streaming its {@link GoalListener} events over the wire via {@link #wireGoalListener} — the
     * same single-goal event vocabulary {@link #runBuild} already speaks per module, here tagged with
     * the fixed {@link DaemonProtocol#SINGLE_GOAL_DIR} sentinel since there's only one goal.
     */
    private void runTest(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String entryDirStr = Ndjson.str(requestLine, "entryDir");
            String cacheStr = Ndjson.str(requestLine, "cache");
            String jdksDirStr = Ndjson.str(requestLine, "jdksDir");
            int workers = Ndjson.intValue(requestLine, "workers", 1);
            String profile = Ndjson.str(requestLine, "profile");
            boolean verbose = Ndjson.bool(requestLine, "verbose", false);

            Path entryDir = Path.of(entryDirStr);
            Path cache = Path.of(cacheStr);
            Path jdksDir = jdksDirStr != null ? Path.of(jdksDirStr) : null;
            Path buildFile = entryDir.resolve("jk.toml");
            Path lockFile = entryDir.resolve("jk.lock");
            int workerCount = Math.max(1, workers);

            int estimatedTestCount = dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/java"))
                    + dev.jkbuild.runtime.TestSupport.estimateTestCount(entryDir.resolve("src/test/kotlin"));

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
                    verbose, /* testOnly */
                    true);
            dev.jkbuild.run.Goal goal =
                    dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs).build();

            Session session = Session.defaults()
                    .withWorkingDir(entryDir)
                    .withCacheDir(cache)
                    .withJdksDir(jdksDir)
                    .withCancel(cancelToken);

            String dir = DaemonProtocol.SINGLE_GOAL_DIR;
            for (Phase p : goal.phases()) {
                sendQuiet(writer, DaemonProtocol.planPhase(dir, p.name(), p.label()));
            }
            sendQuiet(writer, DaemonProtocol.planDone(1));
            goal.addListener(wireGoalListener(dir, writer, goal));

            dev.jkbuild.run.GoalResult result = SessionContext.where(session, goal::run);
            // goalFinish (with test counts, if any) was already sent by wireGoalListener's own
            // goalFinish handling — nothing further to send here; the connection close signals "done".
        } catch (Exception e) {
            sendQuiet(writer, DaemonProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Build and run a single (non-workspace) project's real build goal — the daemon-hosted
     * counterpart of {@code BuildCommand.runForDir}/{@code prepareModule}/{@code runPrepared}'s
     * {@code agg == null} branch. Same single-goal wire shape as {@link #runTest}, {@code
     * testOnly=false}. On success, folds the measured throughput into the host calibration and
     * kicks the opportunistic cache-prune scheduler — exactly {@code runPrepared}'s post-success
     * logic, moved here since the daemon (not the CLI) is the process that actually ran the goal.
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
                    verbose, /* testOnly */
                    false);
            dev.jkbuild.run.Goal.Builder builder = dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs, false);
            dev.jkbuild.runtime.BuildPipeline.appendDeclaredTails(builder, inputs);
            dev.jkbuild.run.Goal goal = builder.build();
            long barWeight = goal.estimatedTotalWeight();

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

            String dir = DaemonProtocol.SINGLE_GOAL_DIR;
            for (Phase p : goal.phases()) {
                sendQuiet(writer, DaemonProtocol.planPhase(dir, p.name(), p.label()));
            }
            sendQuiet(writer, DaemonProtocol.planDone(1));
            goal.addListener(wireGoalListener(dir, writer, goal));

            long startNanos = System.nanoTime();
            dev.jkbuild.run.GoalResult result = SessionContext.where(session, goal::run);
            if (result.success() && barWeight > 0) {
                long moduleMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (moduleMs > 0) {
                    dev.jkbuild.runtime.Calibration.refine(moduleMs / (double) barWeight, System.currentTimeMillis());
                }
                var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                        .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            }
        } catch (Exception e) {
            sendQuiet(writer, DaemonProtocol.buildError(String.valueOf(e.getMessage())));
        }
    }

    /** Translate every {@link WorkspaceBuildListener} callback into a wire event on {@code writer}. */
    private WorkspaceBuildListener wireListener(BufferedWriter writer) {
        return new WorkspaceBuildListener() {
            @Override
            public void onPlan(java.util.List<BuildService.ModulePlan> plan) {
                for (BuildService.ModulePlan m : plan) {
                    String dir = m.dir().toString();
                    sendQuiet(
                            writer,
                            DaemonProtocol.planModule(dir, m.coord(), m.goal().name(), m.weight(), m.fullyCached()));
                    for (Phase p : m.goal().phases()) {
                        sendQuiet(writer, DaemonProtocol.planPhase(dir, p.name(), p.label()));
                    }
                }
                sendQuiet(writer, DaemonProtocol.planDone(plan.size()));
            }

            @Override
            public void onEtaEstimate(long millis) {
                sendQuiet(writer, DaemonProtocol.eta(millis));
            }

            @Override
            public GoalListener onModuleStart(BuildService.ModulePlan m) {
                String dir = m.dir().toString();
                sendQuiet(writer, DaemonProtocol.moduleStart(dir));
                return wireGoalListener(dir, writer, null);
            }

            @Override
            public void onModuleFinish(BuildService.ModuleOutcome o) {
                sendQuiet(
                        writer,
                        DaemonProtocol.moduleFinish(o.dir().toString(), o.coord(), o.success(), o.exitCode(), o.millis()));
            }
        };
    }

    /**
     * Translate every {@link GoalListener} callback for one goal into a {@code dir}-tagged wire
     * event. {@code realGoal} is non-null only for {@link #runTest}/{@link #runSingleBuild} — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys (populated by the run-tests/parse-build phases)
     * ride along on the {@link DaemonProtocol#GOAL_FINISH} message so the client can render its
     * summary line before it even sees the terminal message; {@code null} for a plain per-module
     * workspace-build goal (where neither applies at the module level).
     */
    private GoalListener wireGoalListener(String dir, BufferedWriter writer, dev.jkbuild.run.Goal realGoal) {
        return new GoalListener() {
            @Override
            public void goalStart(GoalView view) {
                sendQuiet(
                        writer,
                        DaemonProtocol.goalStart(
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
                sendQuiet(writer, DaemonProtocol.phaseStart(dir, phase, scope));
            }

            @Override
            public void progress(String phase, int delta, GoalView view) {
                sendQuiet(
                        writer,
                        DaemonProtocol.progress(
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
                        DaemonProtocol.scopeUpdate(
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
                sendQuiet(writer, DaemonProtocol.label(dir, phase, label));
            }

            @Override
            public void output(String phase, String line) {
                sendQuiet(writer, DaemonProtocol.output(dir, phase, line));
            }

            @Override
            public void warn(String phase, String code, String message) {
                sendQuiet(writer, DaemonProtocol.warn(dir, phase, code, message));
            }

            @Override
            public void error(String phase, String code, String message, String test, String exceptionClass) {
                sendQuiet(writer, DaemonProtocol.error(dir, phase, code, message, test, exceptionClass));
            }

            @Override
            public void phaseFinish(String phase, dev.jkbuild.run.PhaseStatus status, Duration duration) {
                sendQuiet(writer, DaemonProtocol.phaseFinish(dir, phase, status.name()));
            }

            @Override
            public void goalFinish(GoalResult result) {
                for (GoalResult.Diagnostic d : result.errors()) {
                    sendQuiet(
                            writer,
                            DaemonProtocol.goalDiagnostic(
                                    dir, d.phase(), d.code(), d.message(), d.test(), d.exceptionClass()));
                }
                dev.jkbuild.test.JUnitLauncher.Result testResult = realGoal == null
                        ? null
                        : realGoal.get(dev.jkbuild.runtime.BuildPipeline.TEST_RESULT).orElse(null);
                String buildOutcome = realGoal == null
                        ? null
                        : realGoal.get(dev.jkbuild.runtime.BuildPipeline.BUILD_OUTCOME).orElse(null);
                sendQuiet(
                        writer,
                        testResult == null && buildOutcome == null
                                ? DaemonProtocol.goalFinish(dir, result.success())
                                : DaemonProtocol.goalFinish(
                                        dir,
                                        result.success(),
                                        buildOutcome,
                                        testResult != null ? testResult.total() : -1,
                                        testResult != null ? testResult.succeeded() : -1,
                                        testResult != null ? testResult.failed() : -1,
                                        testResult != null ? testResult.skipped() : -1));
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
        if (config.neverExpires()) return; // idle-minutes = -1: only an explicit stop/kill ends this daemon
        if (config.exitAsSoonAsIdle()) return; // idle-minutes = 0: handled inline in onConnectionFinished
        long idleMillis = config.idleMinutes() * 60_000L;
        idleTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jk-daemon-idle-ticker");
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

    /** Caller-facing graceful stop — same effect as receiving a {@link DaemonProtocol#SHUTDOWN} message. */
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
     * Size the worker-JVM memory plan once, for the whole daemon process, against a coarse
     * assumed-worst-case concurrency (the host's core count) — not once per build request. Every
     * daemon-hosted {@code buildWorkspace} call passes {@code applyMemoryPlan=false} so it doesn't
     * overwrite this shared plan out from under a sibling request that's already forking workers
     * under it (see {@code docs/daemon.md} and {@code BuildService.buildWorkspace}'s javadoc). This
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
