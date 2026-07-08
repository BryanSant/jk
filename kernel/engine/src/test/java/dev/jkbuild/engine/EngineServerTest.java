// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.protocol.EngineProtocol;
import dev.jkbuild.plugin.protocol.Ndjson;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EngineServerTest {

    // Unix domain socket paths are capped at ~104 bytes (macOS/BSD) / ~108 (Linux) — JUnit's
    // @TempDir nests deep enough under Gradle's build dir to blow past that. Use a short-path temp
    // dir under the system temp root instead, mirroring the short paths ~/.jk/state/engine/ has in
    // real use.
    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkd-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanupTempDirs() {
        // Every test that gets a real EngineServer to run() triggers planSharedWorkerMemoryOnce(),
        // which mutates JvmOptions' process-wide static heap plan — reset it so it doesn't leak into
        // unrelated tests (e.g. JvmOptionsTest) sharing this test JVM.
        dev.jkbuild.worker.JvmOptions.resetSharedPlanForTests();
        for (Path dir : tempDirs) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
            } catch (IOException | java.io.UncheckedIOException ignored) {
                // An engine under test may still be deleting its own files (socket/pid/lock) as it
                // tears down concurrently with this cleanup — Files.walk's lazy traversal wraps a
                // file disappearing mid-walk as an UncheckedIOException, not IOException. Best-effort
                // either way; OS temp cleanup is the real backstop.
            }
        }
    }

    private static EnginePaths.Paths paths(Path stateDir) {
        return EnginePaths.resolve(stateDir);
    }

    /** Poll {@code condition} until true or {@code timeout} elapses (fails the test on timeout). */
    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }

    /** A minimal hand-rolled client: connect, send lines, read one reply line per line sent. */
    private static final class Client implements AutoCloseable {
        private final SocketChannel channel;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        Client(Path socket) throws IOException {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
        }

        String send(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static Thread runInBackground(EngineServer server) {
        List<Throwable> failures = new ArrayList<>();
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (Throwable e) {
                        e.printStackTrace();
                        synchronized (failures) {
                            failures.add(e);
                        }
                    }
                },
                "test-engine-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void serves_hello_ping_and_status_over_the_socket() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "9.9.9-test", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            String ack = c.send(EngineProtocol.hello("9.9.9-test"));
            assertThat(EngineProtocol.typeOf(ack)).isEqualTo(EngineProtocol.HELLO_ACK);
            assertThat(Ndjson.str(ack, "version")).isEqualTo("9.9.9-test");
            assertThat(Ndjson.longValue(ack, "pid", -1)).isEqualTo(ProcessHandle.current().pid());

            String pong = c.send(EngineProtocol.ping());
            assertThat(EngineProtocol.typeOf(pong)).isEqualTo(EngineProtocol.PONG);

            String status = c.send(EngineProtocol.statusRequest());
            assertThat(EngineProtocol.typeOf(status)).isEqualTo(EngineProtocol.STATUS_ACK);
            assertThat(Ndjson.intValue(status, "activeRequests", -1)).isEqualTo(1); // this very connection
            // Memory usage is best-effort, but heap numbers always exist on a live JVM.
            assertThat(Ndjson.longValue(status, "heapUsedBytes", -1)).isPositive();
            assertThat(Ndjson.longValue(status, "heapCommittedBytes", -1))
                    .isGreaterThanOrEqualTo(Ndjson.longValue(status, "heapUsedBytes", -1));
            long rss = Ndjson.longValue(status, "rssBytes", -99);
            assertThat(rss == -1 || rss > 0).isTrue(); // -1 only where the OS exposes no RSS
        }
        server.close();
    }

    @Test
    void a_second_instance_loses_the_election_and_returns_false() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer first = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(first);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        EngineServer second = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        assertThat(second.run()).isFalse(); // loses the tryLock() race immediately, does not block

        first.close();
    }

    @Test
    void shutdown_message_stops_the_server() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            String bye = c.send(EngineProtocol.shutdown());
            assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(p.socket())).isFalse(); // cleaned up
        assertThat(Files.exists(p.lock())).isFalse();
    }

    @Test
    void idle_minutes_zero_exits_as_soon_as_the_workload_drains() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, new JkEngineConfig(0), "1.0", null, 10, System::currentTimeMillis);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(EngineProtocol.ping());
        } // connection closes here — with idle-minutes=0 the engine should exit right after

        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void idle_minutes_negative_one_never_expires_on_its_own() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        // Tiny tick interval: if the (absent) idle timer were wrongly active, it would have fired
        // well within this window against an artificially advanced clock.
        AtomicLong clock = new AtomicLong(0);
        EngineServer server = new EngineServer(p, new JkEngineConfig(-1), "1.0", null, 10, clock::get);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(EngineProtocol.ping());
        }
        clock.addAndGet(Duration.ofDays(365).toMillis()); // "a very long time" of simulated idleness
        Thread.sleep(200); // let a few ticks pass, if any were scheduled

        assertThat(serverThread.isAlive()).isTrue(); // still up — idle-minutes=-1 never self-terminates
        server.close();
        serverThread.join(5_000);
    }

    @Test
    void idle_minutes_n_expires_after_the_configured_window() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        AtomicLong clock = new AtomicLong(0);
        // idle-minutes=1 with a 10ms tick: the ticker fires fast, but only trips once the injected
        // clock shows >= 60_000ms since the last activity.
        EngineServer server = new EngineServer(p, new JkEngineConfig(1), "1.0", null, 10, clock::get);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(EngineProtocol.ping());
        }
        // The server detects the closed connection (and stamps lastActivityAtMillis) asynchronously;
        // give it a moment before advancing the synthetic clock, so the idle window starts from a
        // known point rather than racing the EOF detection.
        Thread.sleep(100);
        // Not idle long enough yet.
        clock.addAndGet(30_000);
        Thread.sleep(100);
        assertThat(serverThread.isAlive()).isTrue();

        // Now past the 1-minute idle window.
        clock.addAndGet(31_000);
        waitUntil(Duration.ofSeconds(5), () -> !serverThread.isAlive());
    }

    /**
     * There's no real Windows box in this test run, but {@link EngineTransport#useLoopbackTcp()}
     * only ever reads {@code os.name} — overriding that system property exercises the exact same
     * bind/auth/connect code path a real Windows host would take, without needing one.
     */
    @Test
    void loopback_tcp_transport_gates_every_connection_on_the_token() throws Exception {
        String previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()) && Files.exists(p.token()));

            int port = Integer.parseInt(Files.readString(p.socket()).trim());
            String token = Files.readString(p.token()).trim();
            assertThat(token).isNotBlank();

            // Wrong token: the server closes the connection without ever replying.
            try (SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w =
                        new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(EngineProtocol.auth("not-the-real-token"));
                w.write('\n');
                w.write(EngineProtocol.ping());
                w.write('\n');
                w.flush();
                assertThat(r.readLine()).isNull(); // EOF — never authenticated, never dispatched
            }

            // Correct token: the connection behaves exactly like the Unix-domain-socket transport.
            try (SocketChannel ch = SocketChannel.open(
                    new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port))) {
                BufferedWriter w =
                        new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch), StandardCharsets.UTF_8));
                BufferedReader r =
                        new BufferedReader(new InputStreamReader(Channels.newInputStream(ch), StandardCharsets.UTF_8));
                w.write(EngineProtocol.auth(token));
                w.write('\n');
                w.write(EngineProtocol.ping());
                w.write('\n');
                w.flush();
                assertThat(EngineProtocol.typeOf(r.readLine())).isEqualTo(EngineProtocol.PONG);
            }

            server.close();
            serverThread.join(5_000);
            assertThat(Files.exists(p.token())).isFalse(); // cleaned up on shutdown, like .sock/.pid/.lock
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    @Test
    void a_stale_socket_file_from_a_killed_engine_does_not_block_a_fresh_start()
            throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        Files.createDirectories(p.dir());
        Files.createFile(p.socket()); // simulate a leftover socket file from a kill -9'd engine

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> {
            try (Client c = new Client(p.socket())) {
                return EngineProtocol.PONG.equals(EngineProtocol.typeOf(c.send(EngineProtocol.ping())));
            } catch (IOException e) {
                return false;
            }
        });
        server.close();
        serverThread.join(5_000);
    }
}
