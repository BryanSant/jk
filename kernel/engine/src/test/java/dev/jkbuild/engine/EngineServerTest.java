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

    /**
     * Engine-hosted {@code jk lock} round-trip (Wave 1 of the slim-client migration): a real server
     * over the socket, a tiny fixture project, and a mock Maven repo standing in for every remote
     * (the request's {@code repoUrl} override). Asserts the full wire conversation — {@code
     * lock-module} → plan burst → {@code lock-package} stream → count-carrying {@code goal-finish}
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
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

            List<String> types = new ArrayList<>();
            String lockModule = null;
            String goalFinish = null;
            String lockFinish = null;
            String buildError = null;
            boolean sawLeafPackage = false;
            try (Client c = new Client(p.socket())) {
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
                        case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                        case EngineProtocol.LOCK_FINISH -> lockFinish = line;
                        // Terminal too (a pre-goal failure): break instead of waiting forever for a
                        // lock-finish that will never come — otherwise the server (reading this
                        // connection for a cancel/EOF) and this loop mutually wait, and the test
                        // hangs to timeout. The real client (EngineResolveAdapter) does the same.
                        case EngineProtocol.BUILD_ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (lockFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-goal error instead of hosting the lock")
                    .isNull();
            assertThat(lockModule).isNotNull();
            assertThat(Ndjson.str(lockModule, "dir")).isEqualTo(project.toString());
            assertThat(Ndjson.str(lockModule, "coord")).isEqualTo("com.example:app");
            assertThat(types).contains(EngineProtocol.PLAN_PHASE, EngineProtocol.PLAN_DONE);
            assertThat(sawLeafPackage).as("lock-package event for com.foo:leaf").isTrue();

            assertThat(goalFinish).isNotNull();
            assertThat(Ndjson.bool(goalFinish, "success", false)).isTrue();
            assertThat(Ndjson.longValue(goalFinish, "lockPackages", -1)).isEqualTo(3); // leaf + 2 junit defaults

            assertThat(lockFinish).isNotNull();
            assertThat(Ndjson.bool(lockFinish, "success", false)).isTrue();
            assertThat(Ndjson.intValue(lockFinish, "exitCode", -1)).isEqualTo(0);

            // The engine (not the client) wrote the lockfile.
            assertThat(Files.isRegularFile(project.resolve("jk.lock"))).isTrue();
            var lock = dev.jkbuild.lock.LockfileReader.read(project.resolve("jk.lock"));
            assertThat(lock.artifacts().stream().map(a -> a.name())).contains("com.foo:leaf");

            server.close();
            serverThread.join(5_000);
        } finally {
            repo.stop(0);
            if (previousM2 != null) System.setProperty("jk.m2.local", previousM2);
            else System.clearProperty("jk.m2.local");
            dev.jkbuild.lock.LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk audit} round-trip (Wave 2 of the slim-client migration — the hosted
     * worker verbs): a real server over the socket forks a real {@code jk-auditor} worker JVM
     * (located via {@code -Djk.auditor.worker.jar}, wired by the Gradle build) against a mock OSV
     * API. Asserts the single-goal wire conversation — plan burst → goal events → structured
     * {@code audit-finding} stream → terminal {@code goal-finish} — carrying the mock vulnerability.
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
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

            List<String> types = new ArrayList<>();
            String finding = null;
            String goalFinish = null;
            String buildError = null;
            try (Client c = new Client(p.socket())) {
                c.sendLine(EngineProtocol.auditRequest(
                        project.toString(), cache.toString(), "LOW", base + "/querybatch", base + "/vulns/"));
                String line;
                while ((line = c.readLine()) != null) {
                    String type = EngineProtocol.typeOf(line);
                    types.add(type);
                    switch (type) {
                        case EngineProtocol.AUDIT_FINDING -> finding = line;
                        case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                        // Terminal too (e.g. the worker jar wasn't locatable): break instead of
                        // waiting for a goal-finish that will never come — the real client
                        // (EngineWorkerAdapter) treats build-error the same way.
                        case EngineProtocol.BUILD_ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (goalFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-goal error instead of hosting the audit")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_PHASE, EngineProtocol.PLAN_DONE);
            assertThat(finding).as("audit-finding event for the mock vulnerability").isNotNull();
            assertThat(Ndjson.str(finding, "module")).isEqualTo("com.foo:leaf");
            assertThat(Ndjson.str(finding, "version")).isEqualTo("1.0");
            assertThat(Ndjson.str(finding, "vulnId")).isEqualTo("GHSA-test-1");
            assertThat(Ndjson.str(finding, "summary")).isEqualTo("Stub vulnerability");

            assertThat(goalFinish).isNotNull();
            assertThat(Ndjson.bool(goalFinish, "success", false)).isTrue();

            server.close();
            serverThread.join(5_000);
        } finally {
            osv.stop(0);
            dev.jkbuild.lock.LockfileReader.clearCache();
        }
    }

    /**
     * Engine-hosted {@code jk compile} round-trip (Wave 3 of the slim-client migration — the
     * in-process {@code BuildPipeline} stragglers): a real server over the socket runs the shared
     * pipeline in compile-only mode against a tiny dependency-free fixture (a fresh empty {@code
     * jk.lock}, so no network resolve). Asserts the single-goal wire conversation — plan burst →
     * goal events → terminal {@code goal-finish} — and that the engine actually compiled the class.
     */
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
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        List<String> types = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        String goalFinish = null;
        String buildError = null;
        try (Client c = new Client(p.socket())) {
            c.sendLine(EngineProtocol.compileRequest(
                    project.toString(), cache.toString(), null, false, false, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                    case EngineProtocol.GOAL_DIAGNOSTIC -> diagnostics.add(line);
                    // Terminal too — break instead of waiting for a goal-finish that will never
                    // come (the mutual-wait shape the audit test also guards against).
                    case EngineProtocol.BUILD_ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (goalFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-goal error instead of hosting the compile")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_PHASE, EngineProtocol.PLAN_DONE);
        assertThat(goalFinish).isNotNull();
        assertThat(Ndjson.bool(goalFinish, "success", false))
                .as("hosted compile succeeded; diagnostics: " + diagnostics)
                .isTrue();
        // The engine (not the client) ran the compile.
        assertThat(Files.isRegularFile(project.resolve("target/classes/main/example/Hello.class")))
                .isTrue();

        server.close();
        serverThread.join(5_000);
        dev.jkbuild.lock.LockfileReader.clearCache();
    }

    /**
     * Engine-hosted {@code jk tool install}/{@code tool run} resolution round-trip (Wave 4 of the
     * slim-client migration): a real server over the socket resolves a Maven-published tool against
     * a mock repo (the {@code --main} override skips manifest reading — the served jar is a stub).
     * Asserts the single-goal wire conversation ends in a {@code goal-finish} carrying the resolved
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
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

            List<String> types = new ArrayList<>();
            String goalFinish = null;
            String buildError = null;
            try (Client c = new Client(p.socket())) {
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
                        case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                        case EngineProtocol.BUILD_ERROR -> buildError = line;
                        default -> {
                            /* plan/progress events — presence asserted via `types` below */
                        }
                    }
                    if (goalFinish != null || buildError != null) break;
                }
            }

            assertThat(buildError)
                    .as("engine reported a pre-goal error instead of hosting the resolve")
                    .isNull();
            assertThat(types).contains(EngineProtocol.PLAN_PHASE, EngineProtocol.PLAN_DONE);
            assertThat(goalFinish).isNotNull();
            assertThat(Ndjson.bool(goalFinish, "success", false)).isTrue();
            assertThat(Ndjson.str(goalFinish, "toolMainClass")).isEqualTo("com.example.Main");
            List<String> classpath = Ndjson.strArray(goalFinish, "toolClasspath");
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
     * CAS temp file. Asserts the single-goal wire conversation ends in a summary-carrying {@code
     * goal-finish}, that the stale files are gone, and that the {@code .prune.lock} cross-process
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
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        List<String> types = new ArrayList<>();
        String goalFinish = null;
        String buildError = null;
        try (Client c = new Client(p.socket())) {
            c.sendLine(EngineProtocol.cachePruneRequest("prune", cache.toString(), 30, false, false, null, false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                types.add(type);
                switch (type) {
                    case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                    case EngineProtocol.BUILD_ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events — presence asserted via `types` below */
                    }
                }
                if (goalFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError)
                .as("engine reported a pre-goal error instead of hosting the prune")
                .isNull();
        assertThat(types).contains(EngineProtocol.PLAN_PHASE, EngineProtocol.PLAN_DONE);
        assertThat(goalFinish).isNotNull();
        assertThat(Ndjson.bool(goalFinish, "success", false)).isTrue();
        assertThat(Ndjson.longValue(goalFinish, "cacheFiles", -1)).isEqualTo(2);
        assertThat(Ndjson.longValue(goalFinish, "cacheBytes", -1)).isPositive();
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
        String tag = dev.jkbuild.task.ActionKey.taskTag(
                dev.jkbuild.layout.BuildLayout.of(project, dev.jkbuild.config.JkBuildParser.parse(project.resolve("jk.toml")))
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
        waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

        String goalFinish = null;
        String buildError = null;
        try (Client c = new Client(p.socket())) {
            c.sendLine(EngineProtocol.cacheClearRequest(cache.toString(), project.toString(), false));
            String line;
            while ((line = c.readLine()) != null) {
                String type = EngineProtocol.typeOf(line);
                switch (type) {
                    case EngineProtocol.GOAL_FINISH -> goalFinish = line;
                    case EngineProtocol.BUILD_ERROR -> buildError = line;
                    default -> {
                        /* plan/progress events */
                    }
                }
                if (goalFinish != null || buildError != null) break;
            }
        }

        assertThat(buildError).isNull();
        assertThat(goalFinish).isNotNull();
        assertThat(Ndjson.bool(goalFinish, "success", false)).isTrue();
        assertThat(Ndjson.longValue(goalFinish, "cacheFiles", -1)).isEqualTo(2); // record + pointer
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

    // ---- embedded HTTP server (docs/http.md) ----------------------------------------------------

    private static dev.jkbuild.config.JkHttpConfig httpOnEphemeralPort(Path wwwRoot) {
        return new dev.jkbuild.config.JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString());
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

        try (Client c = new Client(p.socket())) {
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
            var http = new dev.jkbuild.config.JkHttpConfig(
                    "127.0.0.1", blocker.getLocalPort(), 16, stateDir.resolve("www").toString());
            EngineServer server = new EngineServer(p, JkEngineConfig.DEFAULTS, http, "1.0", null);
            Thread serverThread = runInBackground(server);
            waitUntil(Duration.ofSeconds(5), () -> Files.exists(p.socket()));

            try (Client c = new Client(p.socket())) {
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
