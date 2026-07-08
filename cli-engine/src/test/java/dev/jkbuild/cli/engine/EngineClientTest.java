// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkEngineConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.engine.EngineServer;
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
 * Exercises {@link EngineClient} against a real, in-process {@link EngineServer} — everything except
 * the actual OS-process spawn ({@link EngineClient#ensureRunning}'s cold-start path needs a real
 * {@code jk} binary to exec, which a JVM test run doesn't have; that path is covered by manual
 * verification per the Phase 1 plan, not a unit test).
 */
class EngineClientTest {

    private final List<Path> tempDirs = new ArrayList<>();

    private Path shortTempDir() throws IOException {
        Path dir = Files.createTempDirectory("jkc-");
        tempDirs.add(dir);
        return dir;
    }

    @AfterEach
    void cleanup() {
        // Every test that gets a real EngineServer to run() triggers planSharedWorkerMemoryOnce(),
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
                // engine under test may still be tearing down its own files concurrently — Files.walk's
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

    private static Thread startInBackground(EngineServer server) {
        Thread t = new Thread(
                () -> {
                    try {
                        server.run();
                    } catch (IOException ignored) {
                        // surfaced via the socket never appearing; waitUntil below will time out
                    }
                },
                "test-engine-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void ping_is_false_when_nothing_is_listening() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        assertThat(EngineClient.ping(p.socket())).isFalse();
    }

    @Test
    void ping_handshake_and_status_round_trip_against_a_real_engine() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "7.7.7", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        assertThat(EngineClient.ping(p.socket())).isTrue();

        var hs = EngineClient.handshake(p.socket(), "7.7.7");
        assertThat(hs).isPresent();
        assertThat(hs.get().version()).isEqualTo("7.7.7");
        assertThat(hs.get().pid()).isEqualTo(ProcessHandle.current().pid());

        var status = EngineClient.status(p.socket());
        assertThat(status).isPresent();
        assertThat(status.get().version()).isEqualTo("7.7.7");
        assertThat(status.get().idleMinutes()).isEqualTo(JkEngineConfig.DEFAULTS.idleMinutes());
        assertThat(status.get().heapUsedBytes()).isPositive(); // best-effort memory made it across the wire
        assertThat(status.get().heapCommittedBytes()).isGreaterThanOrEqualTo(status.get().heapUsedBytes());

        server.close();
    }

    @Test
    void stop_gracefully_shuts_a_running_engine_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        assertThat(EngineClient.stop(p.socket())).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void stop_on_a_non_running_engine_is_a_no_op_success() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        assertThat(EngineClient.stop(p.socket())).isTrue();
    }

    /**
     * No real Windows box in this test run, but {@code EngineTransport.useLoopbackTcp()} only ever
     * reads {@code os.name} — overriding it exercises {@link EngineClient#connect}'s TCP+token
     * branch for real, end-to-end through the same public API every other test above uses.
     */
    @Test
    void ping_handshake_and_status_round_trip_over_the_loopback_tcp_transport() throws Exception {
        String previousOsName = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "7.7.7", null);
            startInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()) && Files.exists(p.token()));

            assertThat(EngineClient.ping(p.socket())).isTrue();

            var hs = EngineClient.handshake(p.socket(), "7.7.7");
            assertThat(hs).isPresent();
            assertThat(hs.get().version()).isEqualTo("7.7.7");

            var status = EngineClient.status(p.socket());
            assertThat(status).isPresent();
            assertThat(status.get().version()).isEqualTo("7.7.7");

            assertThat(EngineClient.stop(p.socket())).isTrue();
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    /**
     * The spawn path's artifact resolution: JK_ENGINE_EXE override, then {@code
     * ~/.jk/lib/jk-engine-<version>.jar} whose filename version matches this client's (the
     * installed JVM-hosted engine), then the client binary itself re-invoked with {@code
     * --engine-server}. The actual OS-process spawn stays manual-verification territory (see class
     * javadoc); this pins the decision logic.
     */
    @Test
    void engine_artifact_resolution_prefers_override_then_versioned_lib_jar_then_fallback() throws IOException {
        Path dir = shortTempDir();
        Path client = dir.resolve("jk");
        Files.createFile(client);
        Path libDir = dir.resolve("lib");

        // (c) no override, no lib dir: the client binary itself, needing --engine-server
        EngineClient.EngineArtifact fallback =
                EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", libDir);
        assertThat(fallback.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.FALLBACK);
        assertThat(fallback.path()).isEqualTo(client.toString());

        // a version-skewed engine jar never launches — the version match is the contract
        Files.createDirectories(libDir);
        Files.createFile(libDir.resolve("jk-engine-9.9.9.jar"));
        assertThat(EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", libDir)
                        .kind())
                .isEqualTo(EngineClient.EngineArtifact.Kind.FALLBACK);

        // (b) lib/jk-engine-<client version>.jar: the JVM-hosted engine's fat jar
        Path engineJar = libDir.resolve("jk-engine-1.2.3.jar");
        Files.createFile(engineJar);
        EngineClient.EngineArtifact viaLib =
                EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", libDir);
        assertThat(viaLib.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.JAR);
        assertThat(viaLib.path()).isEqualTo(engineJar.toString());

        // (a) JK_ENGINE_EXE wins over the lib jar, and is always treated as a dedicated executable
        EngineClient.EngineArtifact viaEnv =
                EngineClient.resolveEngineArtifact("/opt/jk/jk-engine", client.toString(), "1.2.3", libDir);
        assertThat(viaEnv.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.EXE);
        assertThat(viaEnv.path()).isEqualTo("/opt/jk/jk-engine");

        // a blank override is ignored, not obeyed
        assertThat(EngineClient.resolveEngineArtifact("  ", client.toString(), "1.2.3", libDir)
                        .path())
                .isEqualTo(viaLib.path());
    }

    @Test
    void ensure_running_returns_immediately_when_a_matching_version_engine_is_already_up() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "3.3.3", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        EngineClient.Handshake hs = EngineClient.ensureRunning(p, "3.3.3", Duration.ofSeconds(2));
        assertThat(hs.version()).isEqualTo("3.3.3");

        server.close();
    }
}
