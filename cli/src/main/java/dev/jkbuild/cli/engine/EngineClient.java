// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.BuildService;
import dev.jkbuild.runtime.WorkspaceBuildListener;
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

    /** How long to wait for a freshly spawned engine to come up before giving up. */
    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(5);

    private EngineClient() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the engine. */
    public record Handshake(String version, long pid, long startedAtMillis) {}

    /**
     * The {@code jk engine status} snapshot. Memory fields are best-effort: {@code -1} means the
     * engine couldn't observe that number (no OS RSS source, or an older engine that predates the
     * fields).
     */
    public record Status(
            String version,
            long pid,
            long startedAtMillis,
            int idleMinutes,
            int activeRequests,
            long heapUsedBytes,
            long heapCommittedBytes,
            long heapMaxBytes,
            long rssBytes) {}

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
            return Optional.of(new Handshake(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Connect and request a status snapshot; empty if no engine is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            exchange(ch, EngineProtocol.hello("status-probe")); // handshake first, response discarded
            String ack = exchange(ch, EngineProtocol.statusRequest());
            if (!EngineProtocol.STATUS_ACK.equals(EngineProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Status(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1),
                    Ndjson.intValue(ack, "idleMinutes", -1),
                    Ndjson.intValue(ack, "activeRequests", -1),
                    Ndjson.longValue(ack, "heapUsedBytes", -1),
                    Ndjson.longValue(ack, "heapCommittedBytes", -1),
                    Ndjson.longValue(ack, "heapMaxBytes", -1),
                    Ndjson.longValue(ack, "rssBytes", -1)));
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
     * The one entry point real commands use: a live, version-matched engine is guaranteed to be
     * reachable at {@code paths.socket()} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) engine transparently. Throws with a
     * message pointing at the engine's log file if it still can't be reached after a fresh spawn —
     * per {@code docs/engine.md}, the engine is load-bearing and this is not silently swallowed.
     */
    public static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion) throws IOException {
        return ensureRunning(paths, clientVersion, DEFAULT_START_TIMEOUT);
    }

    /**
     * Run a workspace build against the engine at {@code paths} instead of in-process — the engine
     * equivalent of {@link BuildService#buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched engine first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/engine.md} there is no in-process fallback.
     */
    public static BuildService.WorkspaceResult buildWorkspace(
            EnginePaths.Paths paths, BuildService.WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Everything an engine-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(Path entryDir, Path cache, Path jdksDir, int workers, String profile, boolean verbose) {}

    /**
     * Run a single project's test goal against the engine (Phase 3) — see {@link
     * EngineBuildListenerAdapter#runTest} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runTest(
            EnginePaths.Paths paths,
            TestRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut)
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
            boolean force) {}

    /**
     * Run a single (non-workspace) project's build against the engine — the engine equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * EngineBuildListenerAdapter#runSingleBuild} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runSingleBuild(
            EnginePaths.Paths paths,
            SingleBuildRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return EngineBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /**
     * Forecast a build against the engine ({@code jk explain}) — see {@link
     * EngineBuildListenerAdapter#explain} for the exact contract.
     */
    public static BuildService.ExplainPlan explain(EnginePaths.Paths paths, Path entryDir, Path cache)
            throws IOException {
        return EngineBuildListenerAdapter.explain(paths, entryDir, cache);
    }

    static Handshake ensureRunning(EnginePaths.Paths paths, String clientVersion, Duration startTimeout)
            throws IOException {
        Optional<Handshake> existing = handshake(paths.socket(), clientVersion);
        if (existing.isPresent()) {
            if (clientVersion.equals(existing.get().version())) return existing.get();
            killStale(existing.get().pid(), startTimeout);
        }
        spawn(paths);
        return awaitStartup(paths, clientVersion, startTimeout)
                .orElseThrow(() -> new IOException(
                        "could not start the build engine — see " + paths.log() + " for details"));
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

    /** Spawn a fresh engine, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static void spawn(EnginePaths.Paths paths) throws IOException {
        String jkExe = CachePruneScheduler.resolveJkExe()
                .orElseThrow(() -> new IOException("could not resolve the running jk binary's path"));
        JkEngineConfig config = JkEngineConfig.resolve();
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        // The child detaches ITSELF into its own session (setsid(2) via PosixDetach, first thing
        // in the engine role) — without that it stays in THIS client's process group, and a
        // Ctrl-C/SIGTERM aimed at the client (or its whole group) would take down the engine and
        // every other build it is hosting.
        command.add(jkExe);
        command.add("--engine-server");
        // Size the engine process's own heap (docs/engine.md "Memory target") — the spawner is
        // the only place that can, since a process can't shrink its own -Xmx. The -Xms pre-sizing
        // matters for a long-lived process (no growth churn). Native image consumes -Xm* runtime
        // options from argv (position-independent) before main(); placed after --engine-server so
        // that if they ever *aren't* consumed, the engine still starts (unsized) instead of
        // failing on an unknown verb. The native binary is already built with --gc=serial.
        if (config.heapCapped() && isNativeImage()) {
            command.add("-Xms" + config.minHeapMb() + "m");
            command.add("-Xmx" + config.maxHeapMb() + "m");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        // On a JVM (installDist) the equivalent knob is the start script's JVM-options env var —
        // appended last so it wins over any blanket JK_OPTS the user exported. SerialGC matches
        // the native binary: lowest footprint/latency, and a ≤256 MiB heap is well inside its
        // comfort zone.
        if (config.heapCapped() && !isNativeImage()) {
            String opts = System.getenv("JK_OPTS");
            String sizing = "-XX:+UseSerialGC -Xms" + config.minHeapMb() + "m -Xmx" + config.maxHeapMb() + "m";
            pb.environment().put("JK_OPTS", (opts == null || opts.isBlank() ? "" : opts + " ") + sizing);
        }
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/engine.md.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the engine doesn't read stdin
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

    private static Optional<Handshake> awaitStartup(EnginePaths.Paths paths, String clientVersion, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<Handshake> h = handshake(paths.socket(), clientVersion);
            if (h.isPresent()) return h;
            sleepQuietly(50);
        }
        return Optional.empty();
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
                new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
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
