// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCommandTest {

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
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ActiveConfig.reset();
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
        assertThat(run("update", "-C", tempDir.toString(),
                "--repo-url", base.toString(), "--cache-dir", cache.toString())).isEqualTo(0);
        Files.delete(tempDir.resolve("jk.lock"));

        // Offline re-solve must come entirely from the journal.
        server.stop(0);
        int exit = run("update", "--offline", "-C", tempDir.toString(),
                "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactly("com.foo:leaf");
    }

    @Test
    void update_rewrites_lockfile_after_dep_added(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        // Initial state: no deps.
        run("new", tempDir.toString());
        run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        Lockfile initial = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(initial.packages()).isEmpty();

        // Add a dep, then update.
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        int exit = run("update",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile updated = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(updated.packages()).hasSize(1);
        assertThat(updated.packages().getFirst().name()).isEqualTo("com.foo:leaf");
    }

    @Test
    void update_accepts_precise_flag_as_noop(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("new", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        int exit = run("update",
                "-C", tempDir.toString(),
                "--precise", "com.foo:leaf@1.0",
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void update_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("update", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void update_from_member_dir_rewrites_workspace_root_lock(@TempDir Path tempDir) throws Exception {
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                artifact = "ws"
                version = "0.1.0"

                [workspace]
                members = ["app", "libb"]
                """);
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                artifact = "app"
                version = "0.1.0"

                [dependencies.main]
                libb = { group = "com.acme", artifact = "libb", version = "0.1.0" }
                leaf = { group = "com.foo",  artifact = "leaf", version = "1.0" }
                """);
        Path libb = Files.createDirectories(tempDir.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                [project]
                group = "com.acme"
                artifact = "libb"
                version = "0.1.0"
                """);

        int exit = run("update",
                "-C", app.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        assertThat(Files.exists(app.resolve("jk.lock"))).isFalse();
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactly("com.foo:leaf");
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".pom";
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private void registerJar(String group, String artifact, String version, byte[] bytes) {
        String path = "/" + group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".jar";
        served.put(path, bytes);
    }

    private void registerMetadata(String group, String artifact, String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><groupId>").append(group)
                .append("</groupId><artifactId>").append(artifact)
                .append("</artifactId><versioning><versions>");
        for (String v : versions) xml.append("<version>").append(v).append("</version>");
        xml.append("</versions></versioning></metadata>");
        served.put("/" + group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml",
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
