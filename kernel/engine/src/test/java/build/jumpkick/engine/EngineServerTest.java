// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.JkEngineConfig;
import build.jumpkick.engine.protocol.EngineProtocol;
import build.jumpkick.plugin.protocol.Ndjson;
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
        build.jumpkick.worker.JvmOptions.resetSharedPlanForTests();
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

        /** Fire-and-forget send, for request types that reply with an event stream (not one line). */
        void sendLine(String line) throws IOException {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        }

        /** Read the next event line off the stream ({@code null} on EOF). */
        String readLine() throws IOException {
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
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
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
    void status_ack_tracks_the_sidecar_aot_trainer_while_it_lives() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "9.9.9-test", null);
        // A stand-in trainer: any real child process the server can track and reap. The server
        // must invoke this factory only after winning its election and starting to serve.
        Process[] trainer = new Process[1];
        server.aotTrainerSpawner(() -> {
            try {
                trainer[0] = new ProcessBuilder("sleep", "30")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return trainer[0];
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        waitUntil(Duration.ofSeconds(5), () -> trainer[0] != null && trainer[0].isAlive());

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.send(EngineProtocol.hello("9.9.9-test"));
            String status = c.send(EngineProtocol.statusRequest());
            assertThat(Ndjson.longValue(status, "aotTrainingPid", -99)).isEqualTo(trainer[0].pid());

            // Trainer exits (self-terminates in real life) → the pid leaves the status snapshot.
            trainer[0].destroy();
            waitUntil(Duration.ofSeconds(5), () -> {
                try {
                    return Ndjson.longValue(c.send(EngineProtocol.statusRequest()), "aotTrainingPid", -99) == -1;
                } catch (IOException e) {
                    return false;
                }
            });
        } finally {
            if (trainer[0] != null) trainer[0].destroyForcibly();
            server.close();
        }
    }

    @Test
    void metrics_request_streams_aggregate_rows_and_a_terminal() throws Exception {
        Path stateDir = shortTempDir();
        Path metricsFile = stateDir.resolve("metrics.json");
        // Seed the store the same way a finished build/test does at request-finish.
        build.jumpkick.runtime.BuildMetrics.record(
                metricsFile,
                new build.jumpkick.runtime.BuildMetrics.Outcome(
                        "build",
                        "/proj/a",
                        "g:a",
                        true,
                        false,
                        1200,
                        List.of(new build.jumpkick.runtime.BuildMetrics.StepSample(
                                "/proj/a", "compile-java", "SUCCESS", 700))),
                1_700_000_000_000L);
        build.jumpkick.runtime.BuildMetrics.record(
                metricsFile,
                new build.jumpkick.runtime.BuildMetrics.Outcome("build", "/proj/b", "g:b", false, false, 400, List.of()),
                1_700_000_000_001L);

        EnginePaths.Paths p = paths(stateDir);
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        server.metricsFileForTests(metricsFile);
        runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            // Unfiltered: global build row + both project rows + global/project step rows.
            c.sendLine(EngineProtocol.metricsRequest(null));
            List<String> rows = new ArrayList<>();
            String line;
            while ((line = c.readLine()) != null
                    && EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(line))) {
                rows.add(line);
            }
            assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.METRICS_DONE);
            assertThat(Ndjson.intValue(line, "count", -1)).isEqualTo(rows.size());
            assertThat(rows).hasSize(5);

            String global = rows.stream()
                    .filter(r -> "global".equals(Ndjson.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Ndjson.longValue(global, "okCount", -1)).isEqualTo(1);
            assertThat(Ndjson.longValue(global, "failCount", -1)).isEqualTo(1); // the /proj/b failure
            assertThat(Ndjson.longValue(global, "okAvgMillis", -1)).isEqualTo(1200);

            String failedProject = rows.stream()
                    .filter(r -> "/proj/b".equals(Ndjson.str(r, "dir")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Ndjson.longValue(failedProject, "okCount", -1)).isZero();
            assertThat(Ndjson.longValue(failedProject, "failTotalMillis", -1)).isEqualTo(400);

            // Filtered: /proj/a's rows plus the always-included global tiers; /proj/b drops out.
            c.sendLine(EngineProtocol.metricsRequest("/proj/a"));
            List<String> filtered = new ArrayList<>();
            while ((line = c.readLine()) != null
                    && EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(line))) {
                filtered.add(line);
            }
            assertThat(EngineProtocol.typeOf(line)).isEqualTo(EngineProtocol.METRICS_DONE);
            assertThat(filtered).hasSize(4);
            assertThat(filtered).noneMatch(r -> "/proj/b".equals(Ndjson.str(r, "dir")));
            String stepRow = filtered.stream()
                    .filter(r -> "project/step".equals(Ndjson.str(r, "scope")))
                    .findFirst()
                    .orElseThrow();
            assertThat(Ndjson.str(stepRow, "step")).isEqualTo("compile-java");
            assertThat(Ndjson.longValue(stepRow, "okTotalMillis", -1)).isEqualTo(700);
        }
        server.close();
    }

    @Test
    void a_second_instance_loses_the_election_and_returns_false() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer first = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        runInBackground(first);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        EngineServer second = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        assertThat(second.run()).isFalse(); // loses the tryLock() race immediately, does not block

        first.close();
    }

    @Test
    void shutdown_message_stops_the_server() throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String bye = c.send(EngineProtocol.shutdown());
            assertThat(EngineProtocol.typeOf(bye)).isEqualTo(EngineProtocol.BYE);
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(EnginePaths.endpoint(p))).isFalse(); // endpoint retired at exit
        assertThat(Files.exists(p.lock())).isFalse();
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
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            Path liveSocket = EnginePaths.activeSocket(p);
            int port = Integer.parseInt(Files.readString(liveSocket).trim());
            String token = Files.readString(EnginePaths.tokenFor(liveSocket)).trim();
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
                // Typed refusal, then close: distinguishable from a crash.
                String refusal = r.readLine();
                assertThat(EngineProtocol.typeOf(refusal)).isEqualTo(EngineProtocol.ERROR);
                assertThat(Ndjson.str(refusal, "code")).isEqualTo(EngineProtocol.ERR_AUTH);
                assertThat(r.readLine()).isNull(); // and nothing was dispatched
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
            assertThat(Files.exists(EnginePaths.tokenFor(liveSocket))).isFalse(); // cleaned up on shutdown
        } finally {
            if (previousOsName != null) System.setProperty("os.name", previousOsName);
            else System.clearProperty("os.name");
        }
    }

    /**
     * Engine-hosted {@code jk lock} round-trip (Wave 1 of the slim-client migration): a real server
     * over the socket, a tiny fixture project, and a mock Maven repo standing in for every remote
     * (the request's {@code repoUrl} override). Asserts the full wire conversation — {@code
     * lock-module} → plan burst → {@code lock-package} stream → count-carrying {@code pipeline-finish}
     * → {@code lock-finish} — and that the engine actually wrote {@code jk.lock}.
     */
    @Test
    void lock_request_resolves_and_writes_the_lockfile_over_the_socket() throws Exception {
        String previousM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        com.sun.net.httpserver.HttpServer repo =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            java.util.Map<String, byte[]> served = new java.util.HashMap<>();
            // jk injects the latest-stable JUnit Platform into every project's TEST scope, so the
            // mock repo must offer those coords (dependency-free stubs) alongside the project dep.
            seedArtifact(served, "org.junit.jupiter", "junit-jupiter", "6.1.0");
            seedArtifact(served, "org.junit.platform", "junit-platform-launcher", "6.1.0");
            seedArtifact(served, "com.foo", "leaf", "1.0");
            repo.createContext("/", exchange -> {
                byte[] body = served.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            repo.start();
            String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();

            Path project = shortTempDir();
            Files.writeString(project.resolve("jk.toml"), """
                    [project]
                    group   = "com.example"
                    name    = "app"
                    version = "1.0.0"
                    jdk     = 21
                    java    = 21

                    [dependencies]
                    leaf = { group = "com.foo", name = "leaf", version = "1.0" }
                    """);
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String lockModule = null;
            String pipelineFinish = null;
            String lockFinish = null;
            String buildError = null;
            boolean sawLeafPackage = false;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(EngineProtocol.lockRequest(
                        project.toString(),
                        cache.toString(),
                        List.of(),
                        false,
                        false,
                        repoUrl,
                        false,
                        false,
                        false));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.LOCK_MODULE -> lockModule = line;
                        case EngineProtocol.LOCK_PACKAGE ->
                            sawLeafPackage |= "com.foo:leaf".equals(Ndjson.str(line, "name"));
                        case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                        case EngineProtocol.LOCK_FINISH -> lockFinish = line;
                        // Terminal too (a pre-pipeline failure): break instead of waiting forever for a
                        // lock-finish that will never come — otherwise the server (reading this
                        // connection for a cancel/EOF) and this loop mutually wait, and the test
                        // hangs to timeout. The real client (EngineResolveAdapter) does the same.
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (lockFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-pipeline error instead of hosting the lock")
                    .isNull();
            assertThat(lockModule).isNotNull();
            assertThat(Ndjson.str(lockModule, "dir")).isEqualTo(project.toString());
            assertThat(Ndjson.str(lockModule, "coord")).isEqualTo("com.example:app");
            assertThat(types).contains(EngineProtocol.PLAN_STEP, EngineProtocol.PLAN_DONE);
            assertThat(sawLeafPackage).as("lock-package event for com.foo:leaf").isTrue();

            assertThat(pipelineFinish).isNotNull();
            assertThat(Ndjson.bool(pipelineFinish, "success", false)).isTrue();
            assertThat(Ndjson.longValue(pipelineFinish, "lockPackages", -1)).isEqualTo(3); // leaf + 2 junit defaults

            assertThat(lockFinish).isNotNull();
            assertThat(Ndjson.bool(lockFinish, "success", false)).isTrue();
            assertThat(Ndjson.intValue(lockFinish, "exitCode", -1)).isEqualTo(0);

            // The engine (not the client) wrote the lockfile.
            assertThat(Files.isRegularFile(project.resolve("jk.lock"))).isTrue();
            var lock = build.jumpkick.lock.LockfileReader.read(project.resolve("jk.lock"));
            assertThat(lock.artifacts().stream().map(a -> a.name())).contains("com.foo:leaf");

            server.close();
            serverThread.join(5_000);
        } finally {
            repo.stop(0);
            if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
            else System.clearProperty("jk.m2.local");
            build.jumpkick.lock.LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk audit} round-trip (Wave 2 of the slim-client migration — the hosted
     * worker commands): a real server over the socket forks a real {@code jk-auditor} worker JVM
     * (located via {@code -Djk.auditor.worker.jar}, wired by the Gradle build) against a mock OSV
     * API. Asserts the single-pipeline wire conversation — plan burst → pipeline events → structured
     * {@code audit-finding} stream → terminal {@code pipeline-finish} — carrying the mock vulnerability.
     */
    @Test
    void audit_request_forks_the_worker_and_streams_findings_over_the_socket() throws Exception {
        com.sun.net.httpserver.HttpServer osv =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            osv.createContext("/querybatch", exchange -> {
                byte[] body = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-test-1\"}]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            osv.createContext("/vulns/", exchange -> {
                byte[] body = "{\"summary\":\"Stub vulnerability\",\"database_specific\":{\"severity\":\"HIGH\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            osv.start();
            String base = "http://127.0.0.1:" + osv.getAddress().getPort();

            Path project = shortTempDir();
            Files.writeString(
                    project.resolve("jk.lock"),
                    """
                    version = 1
                    generated-by = "jk test"
                    resolution-algorithm = "pubgrub-v1"

                    [[artifact]]
                    name     = "com.foo:leaf"
                    version  = "1.0"
                    source   = "central+https://repo.maven.apache.org/maven2/"
                    checksum = "sha256:d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"
                    scopes   = ["main"]
                    """);
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String finding = null;
            String pipelineFinish = null;
            String buildError = null;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(EngineProtocol.auditRequest(
                        project.toString(), cache.toString(), "LOW", base + "/querybatch", base + "/vulns/"));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.AUDIT_FINDING -> finding = line;
                        case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                        // Terminal too (e.g. the worker jar wasn't locatable): break instead of
                        // waiting for a pipeline-finish that will never come — the real client
                        // (EngineWorkerAdapter) treats build-error the same way.
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (pipelineFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-pipeline error instead of hosting the audit")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_STEP, EngineProtocol.PLAN_DONE);
            assertThat(finding).as("audit-finding event for the mock vulnerability").isNotNull();
            assertThat(Ndjson.str(finding, "module")).isEqualTo("com.foo:leaf");
            assertThat(Ndjson.str(finding, "version")).isEqualTo("1.0");
            assertThat(Ndjson.str(finding, "vulnId")).isEqualTo("GHSA-test-1");
            assertThat(Ndjson.str(finding, "summary")).isEqualTo("Stub vulnerability");

            assertThat(pipelineFinish).isNotNull();
            assertThat(Ndjson.bool(pipelineFinish, "success", false)).isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            osv.stop(0);
            build.jumpkick.lock.LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk compile} round-trip (Wave 3 of the slim-client migration — the
     * in-process {@code BuildPipelines} stragglers): a real server over the socket runs the shared
     * pipeline in compile-only mode against a tiny dependency-free fixture (a fresh empty {@code
     * jk.lock}, so no network resolve). Asserts the single-pipeline wire conversation — plan burst →
     * pipeline events → terminal {@code pipeline-finish} — and that the engine actually compiled the class.
     */
    /**
     * The session envelope's {@code rebuild} flag must defeat every engine-side freshness fast
     * path: after a first build populates the stamps and action cache, a second request with
     * {@code withSession(..., rebuild=true)} must genuinely re-run the compile — never label it
     * "up to date". (Regression: --rebuild once vanished between the CLI and the stamp checks.)
     */
    @Test
    void rebuild_in_the_session_envelope_defeats_the_freshness_fast_path() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 21
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        Files.writeString(project.resolve("jk.lock"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path cache = shortTempDir();

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));
        try {
            String plain = EngineProtocol.singleBuildRequest(
                    project.toString(), cache.toString(), null, 1, null, true, false, false, false);

            // First build: real compile, stamps + caches populated.
            assertThat(runToPipelineFinish(p, plain)).doesNotContain("\"buildOutcome\":\"up-to-date\"");
            // Sanity: a plain second build IS the fast path.
            assertThat(runToPipelineFinish(p, plain)).contains("\"buildOutcome\":\"up-to-date\"");
            // The envelope's rebuild defeats it.
            String distrust = EngineProtocol.withSession(plain, null, null, null, true);
            assertThat(runToPipelineFinish(p, distrust))
                    .as("rebuild must reach the engine's stamp checks")
                    .doesNotContain("\"buildOutcome\":\"up-to-date\"");
        } finally {
            server.close();
            serverThread.join(10_000);
        }
    }

    /** Drive one request to its pipeline-finish and return that terminal line. */
    private static String runToPipelineFinish(EnginePaths.Paths p, String request) throws IOException {
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(request);
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                if (EngineProtocol.PIPELINE_FINISH.equals(type)) return line;
                if (EngineProtocol.ERROR.equals(type)) throw new IOException("request failed: " + line);
            }
        }
        throw new IOException("disconnected before pipeline-finish");
    }

    @Test
    void compile_request_compiles_the_project_over_the_socket() throws Exception {
        Path project = shortTempDir();
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 21
                """);
        Path src = project.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        // A fresh empty lock (newer than jk.toml) stands in for "already locked" — the pipeline's
        // parse-build then uses it verbatim instead of resolving over the network.
        Files.writeString(project.resolve("jk.lock"), """
                version = 1
                generated-by = "jk test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path cache = shortTempDir();

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        List<String> types = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String pipelineFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(EngineProtocol.compileRequest(
                    project.toString(), cache.toString(), null, false, false, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                    case EngineProtocol.PIPELINE_DIAGNOSTIC -> diagnostics.add(line);
                    // Terminal too — break instead of waiting for a pipeline-finish that will never
                    // come (the mutual-wait shape the audit test also guards against).
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (pipelineFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-pipeline error instead of hosting the compile")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_STEP, EngineProtocol.PLAN_DONE);
        assertThat(pipelineFinish).isNotNull();
        assertThat(Ndjson.bool(pipelineFinish, "success", false))
                .as("hosted compile succeeded; diagnostics: " + diagnostics)
                .isTrue();
        // The engine (not the client) ran the compile.
        assertThat(Files.isRegularFile(project.resolve("target/classes/main/example/Hello.class")))
                .isTrue();

        server.close();
        serverThread.join(5_000);
        build.jumpkick.lock.LockfileReader.clearCache();
    }

    /**
     * Engine-hosted {@code jk tool install}/{@code tool run} resolution round-trip (Wave 4 of the
     * slim-client migration): a real server over the socket resolves a Maven-published tool against
     * a mock repo (the {@code --main} override skips manifest reading — the served jar is a stub).
     * Asserts the single-pipeline wire conversation ends in a {@code pipeline-finish} carrying the resolved
     * main class + classpath, and that the engine actually fetched the jar into the CAS.
     */
    @Test
    void tool_resolve_request_resolves_and_fetches_over_the_socket() throws Exception {
        String previousM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", shortTempDir().toString()); // never touch the real ~/.m2
        com.sun.net.httpserver.HttpServer repo =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        try {
            java.util.Map<String, byte[]> served = new java.util.HashMap<>();
            seedArtifact(served, "com.example", "widget-cli", "1.0.0");
            repo.createContext("/", exchange -> {
                byte[] body = served.get(exchange.getRequestURI().getPath());
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            repo.start();
            String repoUrl = "http://127.0.0.1:" + repo.getAddress().getPort();
            Path cache = shortTempDir();

            EnginePaths.Paths p = paths(shortTempDir());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            List<String> types = new ArrayList<>();
            String pipelineFinish = null;
            String buildError = null;
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                c.sendLine(EngineProtocol.toolResolveRequest(
                        "com.example:widget-cli:1.0.0",
                        List.of(),
                        "widget",
                        "com.example.Main",
                        repoUrl,
                        cache.toString()));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                        case EngineProtocol.ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (pipelineFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-pipeline error instead of hosting the resolve")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_STEP, EngineProtocol.PLAN_DONE);
            assertThat(pipelineFinish).isNotNull();
            assertThat(Ndjson.bool(pipelineFinish, "success", false)).isTrue();
            assertThat(Ndjson.str(pipelineFinish, "toolMainClass")).isEqualTo("com.example.Main");
            List<String> classpath = Ndjson.strArray(pipelineFinish, "toolClasspath");
            assertThat(classpath).hasSize(1);
            // The engine (not the client) fetched the jar — the classpath entry exists on disk.
            assertThat(Files.isRegularFile(Path.of(classpath.get(0)))).isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            repo.stop(0);
            if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
            else System.clearProperty("jk.m2.local");
        }
    }

    /**
     * Engine-hosted {@code jk cache prune} round-trip (Wave 4 — the idle-boundary cache job): a
     * real server over the socket sweeps a fixture cache holding a stale action key and a leftover
     * CAS temp file. Asserts the single-pipeline wire conversation ends in a summary-carrying {@code
     * pipeline-finish}, that the stale files are gone, and that the {@code .prune.lock} cross-process
     * guard was created (the hosted path always takes it — the Wave-3 finding's fix).
     */
    @Test
    void cache_prune_request_sweeps_the_cache_over_the_socket() throws Exception {
        Path cache = shortTempDir();
        Path staleKey = cache.resolve("actions/keys/stale");
        Files.createDirectories(staleKey.getParent());
        Files.writeString(staleKey, "INPUT deadbeef /x");
        Files.setLastModifiedTime(
                staleKey,
                java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(90).toMillis()));
        Path putTmp = cache.resolve("sha256/.put-1234");
        Files.createDirectories(putTmp.getParent());
        Files.writeString(putTmp, "partial");

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        List<String> types = new ArrayList<>();
        String pipelineFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(EngineProtocol.cachePruneRequest("prune", cache.toString(), 30, false, false, null, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (pipelineFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-pipeline error instead of hosting the prune")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_STEP, EngineProtocol.PLAN_DONE);
        assertThat(pipelineFinish).isNotNull();
        assertThat(Ndjson.bool(pipelineFinish, "success", false)).isTrue();
        assertThat(Ndjson.longValue(pipelineFinish, "cacheFiles", -1)).isEqualTo(2);
        assertThat(Ndjson.longValue(pipelineFinish, "cacheBytes", -1)).isPositive();
        // The engine (not the client) swept the cache.
        assertThat(Files.exists(staleKey)).isFalse();
        assertThat(Files.exists(putTmp)).isFalse();
        // The cross-process guard exists: hosted maintenance always takes .prune.lock.
        assertThat(Files.exists(cache.resolve(".prune.lock"))).isTrue();

        server.close();
        serverThread.join(5_000);
    }

    /**
     * Engine-hosted {@code jk cache clear} round-trip: a real server over the socket invalidates the
     * action-cache entries for a fixture project, matching a record by its qualified-task tag while
     * leaving an unrelated project's record untouched. Also asserts the {@code .prune.lock} guard.
     */
    @Test
    void cache_clear_request_invalidates_the_projects_entries_over_the_socket() throws Exception {
        Path cache = shortTempDir();
        Path project = shortTempDir();
        Files.writeString(
                project.resolve("jk.toml"),
                """
                [project]
                group = "com.example"
                name  = "proj"
                version = "0.1.0"
                java = 25
                """);
        String tag = build.jumpkick.task.ActionKey.taskTag(
                build.jumpkick.layout.BuildLayout.of(project, build.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml")))
                        .classesDir());
        Path mine = cache.resolve("actions/keys/mine");
        Files.createDirectories(mine.getParent());
        Files.writeString(mine, "TASK compile-main@" + tag + "\nKEY mine\nOUTPUT deadbeef foo.class\n");
        Files.createDirectories(cache.resolve("actions/tasks"));
        Files.writeString(cache.resolve("actions/tasks/compile-main@" + tag), "mine");
        Path other = cache.resolve("actions/keys/other");
        Files.writeString(other, "TASK compile-main@ffffffffffff\nKEY other\nOUTPUT deadbeef foo.class\n");

        EnginePaths.Paths p = paths(shortTempDir());
        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

        String pipelineFinish = null;
        String buildError = null;
        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            c.sendLine(EngineProtocol.cacheClearRequest(cache.toString(), project.toString(), false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                switch (type) {
                    case EngineProtocol.PIPELINE_FINISH -> pipelineFinish = line;
                    case EngineProtocol.ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events */
                    }
                }
                if (pipelineFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError).isNull();
        assertThat(pipelineFinish).isNotNull();
        assertThat(Ndjson.bool(pipelineFinish, "success", false)).isTrue();
        assertThat(Ndjson.longValue(pipelineFinish, "cacheFiles", -1)).isEqualTo(2); // record + pointer
        assertThat(Files.exists(mine)).isFalse();
        assertThat(Files.exists(cache.resolve("actions/tasks/compile-main@" + tag))).isFalse();
        assertThat(Files.exists(other)).isTrue();
        assertThat(Files.exists(cache.resolve(".prune.lock"))).isTrue();

        server.close();
        serverThread.join(5_000);
    }

    /** Minimal metadata + dependency-free POM + stub jar for one coordinate on the mock repo. */
    private static void seedArtifact(java.util.Map<String, byte[]> served, String group, String artifact, String version) {
        String base = "/" + group.replace('.', '/') + "/" + artifact;
        served.put(
                base + "/maven-metadata.xml",
                ("<metadata><groupId>" + group + "</groupId><artifactId>" + artifact
                                + "</artifactId><versioning><versions><version>" + version
                                + "</version></versions></versioning></metadata>")
                        .getBytes(StandardCharsets.UTF_8));
        String dir = base + "/" + version + "/" + artifact + "-" + version;
        served.put(
                dir + ".pom",
                ("<project><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId><version>"
                                + version + "</version></project>")
                        .getBytes(StandardCharsets.UTF_8));
        served.put(dir + ".jar", (artifact + "-stub").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void a_stale_socket_file_from_a_killed_engine_does_not_block_a_fresh_start()
            throws Exception {
        EnginePaths.Paths p = paths(shortTempDir());
        Files.createDirectories(p.dir());
        // Simulate a kill -9'd engine's leftovers: a dead generation socket file plus an
        // endpoint that still names it.
        EnginePaths.Paths gen1 = EnginePaths.generation(p, 1);
        Files.createFile(gen1.socket());
        EnginePaths.writeEndpoint(p, gen1.socket());

        EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> {
            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                return EngineProtocol.PONG.equals(EngineProtocol.typeOf(c.send(EngineProtocol.ping())));
            } catch (IOException e) {
                return false;
            }
        });
        server.close();
        serverThread.join(5_000);
    }

    // ---- embedded HTTP server (docs/http.md) ----------------------------------------------------

    private static build.jumpkick.config.JkHttpConfig httpOnEphemeralPort(Path wwwRoot) {
        return new build.jumpkick.config.JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString());
    }

    @Test
    void http_enabled_serves_writes_url_file_and_reports_in_status() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        Path www = Files.createDirectories(stateDir.resolve("www"));
        Files.writeString(www.resolve("hello.txt"), "hi from the engine");

        EngineServer server =
                new EngineServer(p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(www), "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.http()));
        String url = Files.readString(p.http());
        assertThat(url).startsWith("http://127.0.0.1:").endsWith("/");

        var httpClient = java.net.http.HttpClient.newHttpClient();
        var response = httpClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "hello.txt")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hi from the engine");

        // The REST surface serves the same vitals the socket status-ack carries.
        var apiStatus = httpClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "api/status")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(apiStatus.statusCode()).isEqualTo(200);
        assertThat(apiStatus.body()).contains("\"version\":\"1.0\"").contains("\"httpUrl\":\"" + url + "\"");

        assertThat(Files.readString(p.httpToken()).trim()).isNotEmpty(); // minted alongside the URL file

        try (Client c = new Client(EnginePaths.activeSocket(p))) {
            String ack = c.send(EngineProtocol.statusRequest());
            assertThat(Ndjson.str(ack, "httpUrl")).isEqualTo(url);
            assertThat(Ndjson.str(ack, "httpError")).isNull();
            assertThat(c.send(EngineProtocol.shutdown())).isNotNull(); // bye
        }
        serverThread.join(5_000);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(Files.exists(p.http())).isFalse(); // cleaned up with the other engine files
        // The token deliberately survives shutdown so an open dashboard tab stays valid across a
        // restart (docs/http.md); only `jk engine rotate-token` removes it.
        assertThat(Files.exists(p.httpToken())).isTrue();
    }

    @Test
    void http_bind_failure_is_advisory_not_fatal() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        try (var blocker = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var http = new build.jumpkick.config.JkHttpConfig(
                    "127.0.0.1", blocker.getLocalPort(), 16, stateDir.resolve("www").toString());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, http, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(EnginePaths.endpoint(p)));

            try (Client c = new Client(EnginePaths.activeSocket(p))) {
                // The engine's primary role is unharmed...
                assertThat(EngineProtocol.typeOf(c.send(EngineProtocol.ping()))).isEqualTo(EngineProtocol.PONG);
                // ...and status reports the bind failure instead of a URL.
                String ack = c.send(EngineProtocol.statusRequest());
                assertThat(Ndjson.str(ack, "httpUrl")).isNull();
                assertThat(Ndjson.str(ack, "httpError")).isNotEmpty();
            }
            assertThat(Files.exists(p.http())).isFalse(); // no URL file for a server that isn't up
            server.close();
            serverThread.join(5_000);
        }
    }

    @Test
    void http_build_trigger_streams_lifecycle_events_over_sse() throws Exception {
        Path stateDir = shortTempDir();
        EnginePaths.Paths p = paths(stateDir);
        EngineServer server = new EngineServer(
                p, JkEngineConfig.DEFAULTS, httpOnEphemeralPort(stateDir.resolve("www")), "1.0", null);
        Thread serverThread = runInBackground(server);
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.http()) && Files.exists(p.httpToken()));
        String url = Files.readString(p.http());
        String token = Files.readString(p.httpToken()).trim();
        var httpClient = java.net.http.HttpClient.newHttpClient();

        // Subscribe to the event stream first, so the request events can't race past us.
        var sse = httpClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "api/events")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines());
        assertThat(sse.statusCode()).isEqualTo(200);
        var lines = sse.body().iterator();

        // A dir whose jk.toml exists but won't parse: the trigger accepts it (202), the build fails
        // fast and deterministically, and both lifecycle events flow — exactly the plumbing under test.
        Path project = Files.createDirectories(stateDir.resolve("broken-project"));
        Files.writeString(project.resolve("jk.toml"), "this is [not] valid = toml =");

        var rejected = httpClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "api/build"))
                        .header("Authorization", "Bearer " + token)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"dir\":\"" + stateDir.resolve("no-such-project") + "\"}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(400); // validation runs before any thread forks

        var accepted = httpClient.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "api/build"))
                        .header("Authorization", "Bearer " + token)
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "{\"dir\":\"" + project + "\"}"))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(accepted.body()).contains("\"requestId\":");

        String startData = awaitSseData(lines, "request-start");
        assertThat(startData).contains("\"kind\":\"build\"").contains(project.toString());
        String finishData = awaitSseData(lines, "request-finish");
        assertThat(finishData).contains("\"success\":false").contains("\"millis\":");

        server.close();
        serverThread.join(5_000);
    }

    /** Read SSE lines until an {@code event: <type>} frame, returning its {@code data:} payload. */
    private static String awaitSseData(java.util.Iterator<String> lines, String type) throws Exception {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    while (lines.hasNext()) {
                        if (lines.next().equals("event: " + type)) {
                            String data = lines.next();
                            return data.startsWith("data: ") ? data.substring("data: ".length()) : data;
                        }
                    }
                    throw new AssertionError("stream ended without an 'event: " + type + "' frame");
                })
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
