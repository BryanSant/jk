// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateCommandTest {

    // These tests drive the real fetch pipeline against a mock Maven server; fetched
    // artifacts mirror into the Maven local repo. Point that at a throwaway dir (see
    // M2Dirs) so stub artifacts never overwrite the developer's real ~/.m2 — the
    // fixture reuses real coordinates (junit-jupiter et al).
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = served.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        DefaultTestDepsFixture.seed(served);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        cc.jumpkick.config.SessionContext.reset();
        LockfileReader.clearCache();
    }

    @Test
    void update_offline_resolves_from_journal(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));
        Path cache = tempDir.resolve("cache");

        // Warm cache + journal online.
        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        assertThat(run(
                        "update",
                        "-C",
                        tempDir.toString(),
                        "--repo-url",
                        base.toString(),
                        "--cache-dir",
                        cache.toString()))
                .isEqualTo(0);
        Files.delete(tempDir.resolve("jk.lock"));

        // Offline re-solve must come entirely from the journal.
        server.stop(0);
        int exit = run("update", "--offline", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo:leaf");
    }

    @Test
    void update_rewrites_lockfile_after_dep_added(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        // Initial state: no deps.
        run("new", tempDir.toString());
        run(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                base.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        Lockfile initial = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(DefaultTestDepsFixture.projectCoords(initial)).isEmpty();

        // Add a dep, then update.
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                base.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile updated = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(DefaultTestDepsFixture.projectCoords(updated)).containsExactly("com.foo:leaf");
    }

    @Test
    void update_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run(
                "update",
                "-C",
                tempDir.toString(),
                "--repo-url",
                base.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void update_from_module_dir_locks_module_only(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "app"
                version = "0.1.0"

                [dependencies]
                libb = { group = "com.acme", name = "libb", version = "0.1.0" }
                leaf = { group = "com.foo",  name = "leaf", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                name     = "libb"
                version = "0.1.0"
                """);

        // Invoke from module — updates only that module's lock.
        int exit = run(
                "update",
                "-C",
                app.toString(),
                "--repo-url",
                base.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Module owns its own lock; sibling dep filtered out.
        Lockfile lock = LockfileReader.read(app.resolve("jk.lock"));
        assertThat(DefaultTestDepsFixture.projectCoords(lock)).containsExactly("com.foo:leaf");

        // Workspace root lock NOT created by this invocation.
        assertThat(Files.exists(tempDir.resolve("jk.lock"))).isFalse();
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".pom";
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".jar";
        served.put(path, bytes);
    }

    private void registerMetadata(String group, String artifact, String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>")
                .append(group)
                .append("</groupId><artifactId>")
                .append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served.put(
                "/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
                xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String pom(String group, String artifact, String version, String depBlock) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>%s</dependencies>
                </project>
                """.formatted(group, artifact, version, depBlock);
    }
}
