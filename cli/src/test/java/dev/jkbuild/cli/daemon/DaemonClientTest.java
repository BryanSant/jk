// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkDaemonConfig;
import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.daemon.DaemonServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DaemonClient} against a real, in-process {@link DaemonServer} — everything except
 * the actual OS-process spawn ({@link DaemonClient#ensureRunning}'s cold-start path needs a real
 * {@code jk} binary to exec, which a JVM test run doesn't have; that path is covered by manual
 * verification per the Phase 1 plan, not a unit test).
 */
class DaemonClientTest {

    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkc-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanup() {
        // Every test that gets a real DaemonServer to run() triggers planSharedWorkerMemoryOnce(),
        // which mutates JvmOptions' process-wide static heap plan — reset it so it doesn't leak into
        // unrelated tests sharing this test JVM.
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
                // daemon under test may still be tearing down its own files concurrently — Files.walk's
                // lazy traversal wraps a file disappearing mid-walk as an UncheckedIOException, not IOException
            }
        }
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within " + timeout);
            Thread.sleep(10);
        }
    }

    private static Thread startInBackground(DaemonServer server) {
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (IOException ignored) {
                        // surfaced via the socket never appearing; waitUntil below will time out
                    }
                },
                "test-daemon-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void ping_is_false_when_nothing_is_listening() throws IOException {
        DaemonPaths.Paths p = DaemonPaths.resolve(shortTempDir());
        assertThat(DaemonClient.ping(p.socket())).isFalse();
    }

    @Test
    void ping_handshake_and_status_round_trip_against_a_real_daemon() throws Exception {
        DaemonPaths.Paths p = DaemonPaths.resolve(shortTempDir());
        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "7.7.7", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        assertThat(DaemonClient.ping(p.socket())).isTrue();

        var hs = DaemonClient.handshake(p.socket(), "7.7.7");
        assertThat(hs).isPresent();
        assertThat(hs.get().version()).isEqualTo("7.7.7");
        assertThat(hs.get().pid()).isEqualTo(ProcessHandle.current().pid());

        var status = DaemonClient.status(p.socket());
        assertThat(status).isPresent();
        assertThat(status.get().version()).isEqualTo("7.7.7");
        assertThat(status.get().idleMinutes()).isEqualTo(JkDaemonConfig.DEFAULTS.idleMinutes());

        server.close();
    }

    @Test
    void stop_gracefully_shuts_a_running_daemon_down() throws Exception {
        DaemonPaths.Paths p = DaemonPaths.resolve(shortTempDir());
        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        assertThat(DaemonClient.stop(p.socket())).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void stop_on_a_non_running_daemon_is_a_no_op_success() throws IOException {
        DaemonPaths.Paths p = DaemonPaths.resolve(shortTempDir());
        assertThat(DaemonClient.stop(p.socket())).isTrue();
    }

    @Test
    void ensure_running_returns_immediately_when_a_matching_version_daemon_is_already_up() throws Exception {
        DaemonPaths.Paths p = DaemonPaths.resolve(shortTempDir());
        DaemonServer server = new DaemonServer(p, JkDaemonConfig.DEFAULTS, "3.3.3", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        DaemonClient.Handshake hs = DaemonClient.ensureRunning(p, "3.3.3", Duration.ofSeconds(2));
        assertThat(hs.version()).isEqualTo("3.3.3");

        server.close();
    }
}
