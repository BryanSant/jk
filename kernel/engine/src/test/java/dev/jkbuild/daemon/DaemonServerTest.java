// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkDaemonConfig;
import dev.jkbuild.daemon.protocol.DaemonProtocol;
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

class DaemonServerTest {

    // Unix domain socket paths are capped at ~104 bytes (macOS/BSD) / ~108 (Linux) — JUnit's
    // @TempDir nests deep enough under Gradle's build dir to blow past that. Use a short-path temp
    // dir under the system temp root instead, mirroring the short paths ~/.jk/state/daemon/ has in
    // real use.
    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkd-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanupTempDirs() {
        // Every test that gets a real DaemonServer to run() triggers planSharedWorkerMemoryOnce(),
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
                // A daemon under test may still be deleting its own files (socket/pid/lock) as it
                // tears down concurrently with this cleanup — Files.walk's lazy traversal wraps a
                // file disappearing mid-walk as an UncheckedIOException, not IOException. Best-effort
                // either way; OS temp cleanup is the real backstop.
            }
        }
    }

    private static DaemonPaths.Paths paths(Path stateDir) {
        return DaemonPaths.resolve(stateDir);
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

    private static Thread runInBackground(DaemonServer server) {
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
                "test-daemon-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void serves_hello_ping_and_status_over_the_socket() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "9.9.9-test", null);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            String ack = c.send(DaemonProtocol.hello("9.9.9-test"));
            assertThat(DaemonProtocol.typeOf(ack)).isEqualTo(DaemonProtocol.HELLO_ACK);
            assertThat(Ndjson.str(ack, "version")).isEqualTo("9.9.9-test");
            assertThat(Ndjson.longValue(ack, "pid", -1)).isEqualTo(ProcessHandle.current().pid());

            String pong = c.send(DaemonProtocol.ping());
            assertThat(DaemonProtocol.typeOf(pong)).isEqualTo(DaemonProtocol.PONG);

            String status = c.send(DaemonProtocol.statusRequest());
            assertThat(DaemonProtocol.typeOf(status)).isEqualTo(DaemonProtocol.STATUS_ACK);
            assertThat(Ndjson.intValue(status, "activeRequests", -1)).isEqualTo(1); // this very connection
        }
        server.close();
    }

    @Test
    void a_second_instance_loses_the_election_and_returns_false() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        DaemonServer first = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "1.0", null);
        runInBackground(first);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        DaemonServer second = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "1.0", null);
        assertThat(second.run()).isFalse(); // loses the tryLock() race immediately, does not block

        first.close();
    }

    @Test
    void shutdown_message_stops_the_server() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            String bye = c.send(DaemonProtocol.shutdown());
            assertThat(DaemonProtocol.typeOf(bye)).isEqualTo(DaemonProtocol.BYE);
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(p.socket())).isFalse(); // cleaned up
        assertThat(Files.exists(p.lock())).isFalse();
    }

    @Test
    void idle_minutes_zero_exits_as_soon_as_the_workload_drains() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        DaemonServer server = new DaemonServer(p, new JkDaemonConfig(0), "1.0", null, 10, System::currentTimeMillis);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(DaemonProtocol.ping());
        } // connection closes here — with idle-minutes=0 the daemon should exit right after

        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void idle_minutes_negative_one_never_expires_on_its_own() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        // Tiny tick interval: if the (absent) idle timer were wrongly active, it would have fired
        // well within this window against an artificially advanced clock.
        AtomicLong clock = new AtomicLong(0);
        DaemonServer server = new DaemonServer(p, new JkDaemonConfig(-1), "1.0", null, 10, clock::get);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(DaemonProtocol.ping());
        }
        clock.addAndGet(Duration.ofDays(365).toMillis()); // "a very long time" of simulated idleness
        Thread.sleep(200); // let a few ticks pass, if any were scheduled

        assertThat(serverThread.isAlive()).isTrue(); // still up — idle-minutes=-1 never self-terminates
        server.close();
        serverThread.join(5_000);
    }

    @Test
    void idle_minutes_n_expires_after_the_configured_window() throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        AtomicLong clock = new AtomicLong(0);
        // idle-minutes=1 with a 10ms tick: the ticker fires fast, but only trips once the injected
        // clock shows >= 60_000ms since the last activity.
        DaemonServer server = new DaemonServer(p, new JkDaemonConfig(1), "1.0", null, 10, clock::get);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        try (Client c = new Client(p.socket())) {
            c.send(DaemonProtocol.ping());
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

    @Test
    void a_stale_socket_file_from_a_killed_daemon_does_not_block_a_fresh_start()
            throws Exception {
        DaemonPaths.Paths p = paths(shortTempDir());
        Files.createDirectories(p.dir());
        Files.createFile(p.socket()); // simulate a leftover socket file from a kill -9'd daemon

        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> {
            try (Client c = new Client(p.socket())) {
                return DaemonProtocol.PONG.equals(DaemonProtocol.typeOf(c.send(DaemonProtocol.ping())));
            } catch (IOException e) {
                return false;
            }
        });
        server.close();
        serverThread.join(5_000);
    }
}
