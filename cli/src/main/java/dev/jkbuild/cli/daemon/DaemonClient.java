// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.daemon;

import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.daemon.protocol.DaemonProtocol;
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
 * CLI-side counterpart to {@link dev.jkbuild.daemon.DaemonServer}: connects, spawns the daemon lazily
 * when none is reachable, and handles version-skew by killing a stale daemon and starting a fresh
 * one — all transparent to the caller. See {@code docs/daemon.md}.
 */
public final class DaemonClient {

    /** Per-read/connect socket timeout — a live daemon replies in well under this. */
    private static final int SOCKET_TIMEOUT_MILLIS = 2_000;

    /** How long to wait for a freshly spawned daemon to come up before giving up. */
    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(5);

    private DaemonClient() {}

    /** What a connection's {@code hello}/{@code hello-ack} handshake reveals about the daemon. */
    public record Handshake(String version, long pid, long startedAtMillis) {}

    /** The {@code jk daemon status} snapshot. */
    public record Status(String version, long pid, long startedAtMillis, int idleMinutes, int activeRequests) {}

    /**
     * Connect, ping, and get {@code pong} back — the daemon-existence check per {@code docs/daemon.md}
     * (never trust a pidfile alone). {@code false} for anything from "nothing is listening" to "it
     * answered something unexpected."
     */
    public static boolean ping(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            String reply = exchange(ch, DaemonProtocol.ping());
            return DaemonProtocol.PONG.equals(DaemonProtocol.typeOf(reply));
        } catch (IOException e) {
            return false;
        }
    }

    /** Connect and perform the {@code hello}/{@code hello-ack} handshake; empty if unreachable. */
    public static Optional<Handshake> handshake(Path socket, String clientVersion) {
        try (SocketChannel ch = connect(socket)) {
            String ack = exchange(ch, DaemonProtocol.hello(clientVersion));
            if (!DaemonProtocol.HELLO_ACK.equals(DaemonProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Handshake(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Connect and request a status snapshot; empty if no daemon is reachable. */
    public static Optional<Status> status(Path socket) {
        try (SocketChannel ch = connect(socket)) {
            exchange(ch, DaemonProtocol.hello("status-probe")); // handshake first, response discarded
            String ack = exchange(ch, DaemonProtocol.statusRequest());
            if (!DaemonProtocol.STATUS_ACK.equals(DaemonProtocol.typeOf(ack))) return Optional.empty();
            return Optional.of(new Status(
                    Ndjson.str(ack, "version"),
                    Ndjson.longValue(ack, "pid", -1),
                    Ndjson.longValue(ack, "startedAt", -1),
                    Ndjson.intValue(ack, "idleMinutes", -1),
                    Ndjson.intValue(ack, "activeRequests", -1)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Ask a reachable daemon to shut down gracefully; {@code true} if one was reached and acknowledged
     * (or was already not running — stopping a non-running daemon is not an error), {@code false} if
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
            String bye = exchange(ch, DaemonProtocol.shutdown());
            return DaemonProtocol.BYE.equals(DaemonProtocol.typeOf(bye));
        } catch (IOException e) {
            return false; // reachable but didn't behave — a real problem, not "already stopped"
        }
    }

    /**
     * The one entry point real commands use: a live, version-matched daemon is guaranteed to be
     * reachable at {@code paths.socket()} when this returns normally. Spawns lazily if none is
     * running; kills and replaces a stale (version-mismatched) daemon transparently. Throws with a
     * message pointing at the daemon's log file if it still can't be reached after a fresh spawn —
     * per {@code docs/daemon.md}, the daemon is load-bearing and this is not silently swallowed.
     */
    public static Handshake ensureRunning(DaemonPaths.Paths paths, String clientVersion) throws IOException {
        return ensureRunning(paths, clientVersion, DEFAULT_START_TIMEOUT);
    }

    /**
     * Run a workspace build against the daemon at {@code paths} instead of in-process — the daemon
     * equivalent of {@link BuildService#buildWorkspace}, driving the exact same {@code listener}.
     * Ensures a live, version-matched daemon first (spawning/replacing as needed), then streams the
     * build over a fresh connection. Throws with a clear message on any failure; per {@code
     * docs/daemon.md} there is no in-process fallback.
     */
    public static BuildService.WorkspaceResult buildWorkspace(
            DaemonPaths.Paths paths, BuildService.WorkspaceRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return DaemonBuildListenerAdapter.buildWorkspace(paths, req, listener);
    }

    /** Everything a daemon-hosted {@code jk test} run needs — mirrors {@code TestCommand}'s own local fields. */
    public record TestRequest(Path entryDir, Path cache, Path jdksDir, int workers, String profile, boolean verbose) {}

    /**
     * Run a single project's test goal against the daemon (Phase 3) — see {@link
     * DaemonBuildListenerAdapter#runTest} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runTest(
            DaemonPaths.Paths paths,
            TestRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut)
            throws IOException {
        return DaemonBuildListenerAdapter.runTest(paths, req, listenerFactory, testResultOut);
    }

    /** Everything a daemon-hosted single-project {@code jk build} needs — mirrors {@code BuildCommand}'s local fields. */
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
     * Run a single (non-workspace) project's build against the daemon — the daemon equivalent of
     * {@code BuildCommand.runForDir}'s {@code agg == null} branch — see {@link
     * DaemonBuildListenerAdapter#runSingleBuild} for the exact contract.
     */
    public static dev.jkbuild.run.GoalResult runSingleBuild(
            DaemonPaths.Paths paths,
            SingleBuildRequest req,
            java.util.function.Function<List<dev.jkbuild.run.Phase>, dev.jkbuild.run.GoalListener> listenerFactory,
            dev.jkbuild.test.JUnitLauncher.Result[] testResultOut,
            String[] buildOutcomeOut)
            throws IOException {
        return DaemonBuildListenerAdapter.runSingleBuild(paths, req, listenerFactory, testResultOut, buildOutcomeOut);
    }

    /**
     * Forecast a build against the daemon ({@code jk explain}) — see {@link
     * DaemonBuildListenerAdapter#explain} for the exact contract.
     */
    public static BuildService.ExplainPlan explain(DaemonPaths.Paths paths, Path entryDir, Path cache)
            throws IOException {
        return DaemonBuildListenerAdapter.explain(paths, entryDir, cache);
    }

    static Handshake ensureRunning(DaemonPaths.Paths paths, String clientVersion, Duration startTimeout)
            throws IOException {
        Optional<Handshake> existing = handshake(paths.socket(), clientVersion);
        if (existing.isPresent()) {
            if (clientVersion.equals(existing.get().version())) return existing.get();
            killStale(existing.get().pid(), startTimeout);
        }
        spawn(paths);
        return awaitStartup(paths, clientVersion, startTimeout)
                .orElseThrow(() -> new IOException(
                        "could not start the build daemon — see " + paths.log() + " for details"));
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

    /** Spawn a fresh daemon, detached — mirrors {@link CachePruneScheduler}'s spawn-and-forget pattern. */
    private static void spawn(DaemonPaths.Paths paths) throws IOException {
        String jkExe = CachePruneScheduler.resolveJkExe()
                .orElseThrow(() -> new IOException("could not resolve the running jk binary's path"));
        Files.createDirectories(paths.dir());
        rotateLog(paths.log());
        List<String> command = new ArrayList<>();
        command.add(jkExe);
        command.add("--daemon-server");
        ProcessBuilder pb = new ProcessBuilder(command);
        // Merge stderr into stdout inside the child (one fd, no interleaving risk from two
        // independently-opened streams onto the same file), then route that to the log — a fresh
        // file every start, per docs/daemon.md.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(paths.log().toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        p.getOutputStream().close(); // EOF immediately; the daemon doesn't read stdin
    }

    /**
     * Keep exactly one historical log ({@code <key>.log} → {@code <key>.log.1}) before each fresh
     * daemon start truncates {@code <key>.log}. Without this, a crash followed by the next lazy
     * respawn (which happens automatically, often before anyone looks) would silently destroy the
     * crashed daemon's own log — the one file {@link #ensureRunning}'s error message and {@code jk
     * daemon status} both point at for post-mortem. Best-effort: a failure here (e.g. permissions)
     * never blocks starting the daemon.
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

    private static Optional<Handshake> awaitStartup(DaemonPaths.Paths paths, String clientVersion, Duration timeout) {
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
     * Package-visible: {@link DaemonBuildListenerAdapter} opens its own long-lived connection. On
     * the loopback-TCP transport (Windows — see {@link dev.jkbuild.daemon.DaemonTransport}), {@code
     * socket} holds the port number (not a real socket path) and this also sends the required
     * {@link DaemonProtocol#AUTH} line before returning, so every caller authenticates transparently
     * without needing its own knowledge of the transport.
     */
    static SocketChannel connect(Path socket) throws IOException {
        if (dev.jkbuild.daemon.DaemonTransport.useLoopbackTcp()) {
            int port = Integer.parseInt(Files.readString(socket).trim());
            String token =
                    Files.readString(dev.jkbuild.daemon.DaemonPaths.tokenFor(socket)).trim();
            SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port));
            BufferedWriter authWriter =
                    new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
            authWriter.write(DaemonProtocol.auth(token));
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
     * adapter) has no built-in read timeout, so a watchdog thread closes the channel if the daemon
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
                "jk-daemon-client-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            String reply = reader.readLine();
            if (reply == null) throw new IOException("daemon closed the connection without replying");
            return reply;
        } catch (java.nio.channels.AsynchronousCloseException e) {
            throw new IOException("daemon did not reply within " + SOCKET_TIMEOUT_MILLIS + "ms", e);
        } finally {
            watchdog.interrupt();
        }
    }
}
