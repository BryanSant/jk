// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.JkEngineConfig;
import build.jumpkick.engine.EnginePaths;
import build.jumpkick.engine.EngineServer;
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
 * verification per the Step 1 plan, not a unit test).
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
        build.jumpkick.engine.plugin.JvmOptions.resetSharedPlanForTests();
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
        assertThat(EngineClient.ping(EnginePaths.activeSocket(p))).isFalse();
    }

    @Test
    void ping_handshake_and_status_round_trip_against_a_real_engine() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "7.7.7", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        assertThat(EngineClient.ping(EnginePaths.activeSocket(p))).isTrue();

        var hs = EngineClient.handshake(EnginePaths.activeSocket(p), "7.7.7");
        assertThat(hs).isPresent();
        assertThat(hs.get().version()).isEqualTo("7.7.7");
        assertThat(hs.get().pid()).isEqualTo(ProcessHandle.current().pid());

        var status = EngineClient.status(EnginePaths.activeSocket(p));
        assertThat(status).isPresent();
        assertThat(status.get().version()).isEqualTo("7.7.7");
        assertThat(status.get().heapUsedBytes()).isPositive(); // best-effort memory made it across the wire
        assertThat(status.get().heapCommittedBytes()).isGreaterThanOrEqualTo(status.get().heapUsedBytes());

        server.close();
    }

    @Test
    void stop_gracefully_shuts_a_running_engine_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void stop_on_a_non_running_engine_is_a_no_op_success() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
    }

    @Test
    void a_normal_engine_reports_not_draining_and_zero_pipelines() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        assertThat(EngineClient.handshake(EnginePaths.activeSocket(p), "1.0").orElseThrow().draining()).isFalse();
        var s = EngineClient.status(EnginePaths.activeSocket(p)).orElseThrow();
        assertThat(s.draining()).isFalse();
        assertThat(s.activePipelines()).isZero();

        server.close();
    }

    @Test
    void drain_of_an_idle_engine_reports_zero_jobs_and_shuts_it_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        assertThat(EngineClient.drain(EnginePaths.activeSocket(p))).isZero(); // no in-flight jobs → immediate exit
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void force_stop_shuts_a_running_engine_down() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        assertThat(EngineClient.forceStop(EnginePaths.activeSocket(p))).isTrue();
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
    }

    @Test
    void drain_on_a_non_running_engine_is_minus_one() throws IOException {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        assertThat(EngineClient.drain(EnginePaths.activeSocket(p))).isEqualTo(-1);
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
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            assertThat(EngineClient.ping(EnginePaths.activeSocket(p))).isTrue();

            var hs = EngineClient.handshake(EnginePaths.activeSocket(p), "7.7.7");
            assertThat(hs).isPresent();
            assertThat(hs.get().version()).isEqualTo("7.7.7");

            var status = EngineClient.status(EnginePaths.activeSocket(p));
            assertThat(status).isPresent();
            assertThat(status.get().version()).isEqualTo("7.7.7");

            assertThat(EngineClient.stop(EnginePaths.activeSocket(p))).isTrue();
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    /**
     * The spawn path's artifact resolution: JK_ENGINE_EXE override, then the side-by-side layout
     * ({@code ~/.jk/versions/<v>/lib/jk-engine.jar} — the only installed layout), then the client
     * binary itself re-invoked with {@code --engine-server}. The actual OS-process spawn stays
     * manual-verification territory (see class javadoc); this pins the decision logic.
     */
    @Test
    void engine_artifact_resolution_prefers_override_then_versions_layout_then_fallback() throws IOException {
        Path dir = shortTempDir();
        Path client = dir.resolve("jk");
        Files.createFile(client);
        // Isolated store: the machine-global ~/.jk/versions must not leak into this contract.
        build.jumpkick.cache.VersionStore store = new build.jumpkick.cache.VersionStore(dir.resolve("versions"));

        // (c) no override, nothing materialized: the client binary itself, needing --engine-server
        EngineClient.EngineArtifact fallback =
                EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", store);
        assertThat(fallback.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.FALLBACK);
        assertThat(fallback.path()).isEqualTo(client.toString());

        // a version-skewed materialization never launches — the version match is the contract
        materialize(store, dir, "9.9.9");
        assertThat(EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", store)
                        .kind())
                .isEqualTo(EngineClient.EngineArtifact.Kind.FALLBACK);

        // (b) versions/<client version>/lib/jk-engine.jar: the JVM-hosted engine's fat jar
        Path engineJar = materialize(store, dir, "1.2.3");
        EngineClient.EngineArtifact viaVersions =
                EngineClient.resolveEngineArtifact(null, client.toString(), "1.2.3", store);
        assertThat(viaVersions.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.JAR);
        assertThat(viaVersions.path()).isEqualTo(engineJar.toString());

        // (a) JK_ENGINE_EXE wins over the materialized jar, always a dedicated executable
        EngineClient.EngineArtifact viaEnv =
                EngineClient.resolveEngineArtifact("/opt/jk/jk-engine", client.toString(), "1.2.3", store);
        assertThat(viaEnv.kind()).isEqualTo(EngineClient.EngineArtifact.Kind.EXE);
        assertThat(viaEnv.path()).isEqualTo("/opt/jk/jk-engine");

        // a blank override is ignored, not obeyed
        assertThat(EngineClient.resolveEngineArtifact("  ", client.toString(), "1.2.3", store)
                        .path())
                .isEqualTo(viaVersions.path());
    }

    /** Materialize a fake engine jar for {@code version} into the isolated store. */
    private static Path materialize(build.jumpkick.cache.VersionStore store, Path dir, String version)
            throws IOException {
        Path jar = dir.resolve("jk-engine-" + version + "-src.jar");
        Files.writeString(jar, "fake engine " + version);
        build.jumpkick.cache.Cas cas = new build.jumpkick.cache.Cas(dir.resolve("cache"));
        return store.materializeFromFiles(version, cas, jar, null).engineJar();
    }

    @Test
    void ensure_running_returns_immediately_when_a_matching_version_engine_is_already_up() throws Exception {
        EnginePaths.Paths p = EnginePaths.resolve(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "3.3.3", null);
        startInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        EngineClient.Handshake hs = EngineClient.ensureRunning(p, "3.3.3");
        assertThat(hs.version()).isEqualTo("3.3.3");

        server.close();
    }
}
