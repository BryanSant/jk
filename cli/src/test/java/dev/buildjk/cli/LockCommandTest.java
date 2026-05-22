// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import com.sun.net.httpserver.HttpServer;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.util.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LockCommandTest {

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
    }

    @Test
    void init_add_lock_full_pipeline(@TempDir Path tempDir) throws Exception {
        // Set up a tiny graph: root -> leaf.
        registerMetadata("com.foo", "leaf", "1.0");
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        byte[] leafJar = "leaf-jar-bytes".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "leaf", "1.0", leafJar);

        registerMetadata("com.foo", "root", "1.0");
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        byte[] rootJar = "root-jar-bytes".getBytes(StandardCharsets.UTF_8);
        registerJar("com.foo", "root", "1.0", rootJar);

        // Run the verbs against the test repo.
        int exit;
        exit = run("init", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        exit = run("lock",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        // Inspect the lockfile.
        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).hasSize(2);
        assertThat(lock.packages()).extracting(Lockfile.Package::name)
                .containsExactly("com.foo:leaf", "com.foo:root"); // writer sorts by name

        Lockfile.Package leaf = pkg(lock, "com.foo:leaf");
        Lockfile.Package root = pkg(lock, "com.foo:root");

        assertThat(leaf.version()).isEqualTo("1.0");
        assertThat(leaf.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(leafJar));
        assertThat(leaf.source()).startsWith("central+").endsWith("/");
        assertThat(leaf.deps()).isEmpty();

        assertThat(root.deps()).containsExactly("com.foo:leaf@1.0");
        assertThat(root.checksum()).isEqualTo("sha256:" + Hashing.sha256Hex(rootJar));
    }

    @Test
    void lock_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void lock_with_no_dependencies_writes_empty_lockfile(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        int exit = run("lock",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Lockfile lock = LockfileReader.read(tempDir.resolve("jk.lock"));
        assertThat(lock.packages()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static Lockfile.Package pkg(Lockfile lock, String module) {
        return lock.packages().stream()
                .filter(p -> p.name().equals(module))
                .findFirst().orElseThrow();
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
