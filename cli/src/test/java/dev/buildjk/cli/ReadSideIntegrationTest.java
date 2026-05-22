// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the full pipeline: init -> add -> lock -> tree / why / sync. */
class ReadSideIntegrationTest {

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
    void full_pipeline_init_add_lock_tree_why_sync(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf-jar".getBytes(StandardCharsets.UTF_8));
        registerPom("com.foo", "root", "1.0", pom("com.foo", "root", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>leaf</artifactId>
                  <version>1.0</version>
                </dependency>
                """));
        registerJar("com.foo", "root", "1.0", "root-jar".getBytes(StandardCharsets.UTF_8));

        Path cache = tempDir.resolve("cache");

        run("init", tempDir.toString());
        run("add", "com.foo:root:1.0", "-C", tempDir.toString());
        run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", cache.toString());

        // jk tree
        String tree = captureStdout(() -> run("tree", "-C", tempDir.toString()));
        assertThat(tree).contains("com.foo:root v1.0");
        assertThat(tree).contains("com.foo:leaf v1.0");

        // jk why
        String why = captureStdout(() -> run("why", "com.foo:leaf", "-C", tempDir.toString()));
        assertThat(why).contains("com.foo:leaf v1.0 is pulled in by:");
        assertThat(why).contains("com.foo:root v1.0 -> com.foo:leaf v1.0");

        // jk sync — second time with cache populated should report up-to-date.
        String sync = captureStdout(() -> run("sync",
                "-C", tempDir.toString(),
                "--cache-dir", cache.toString()));
        assertThat(sync).contains("up-to-date");

        // jk sync on a fresh cache should fetch.
        Path freshCache = tempDir.resolve("fresh-cache");
        Files.createDirectories(freshCache);
        String resync = captureStdout(() -> run("sync",
                "-C", tempDir.toString(),
                "--cache-dir", freshCache.toString()));
        assertThat(resync).contains("2 fetched");
    }

    @Test
    void why_returns_1_for_unknown_module(@TempDir Path tempDir) {
        // init writes an empty jk.lock; the queried module isn't in it.
        run("init", tempDir.toString());
        int exit = run("why", "com.foo:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void tree_without_lockfile_errors(@TempDir Path tempDir) throws IOException {
        // Write a build.jk by hand so no jk.lock is created.
        Files.writeString(tempDir.resolve("build.jk"),
                "project { group = \"com.example\" artifact = \"a\" version = \"0.1.0\" }\n");
        int exit = run("tree", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void fetch_accepts_offline_prepare_flag(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "leaf", "1.0", pom("com.foo", "leaf", "1.0", ""));
        registerJar("com.foo", "leaf", "1.0", "leaf".getBytes(StandardCharsets.UTF_8));

        run("init", tempDir.toString());
        run("add", "com.foo:leaf:1.0", "-C", tempDir.toString());
        run("lock", "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());

        int exit = run("fetch",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("fresh").toString(),
                "--offline-prepare");
        assertThat(exit).isEqualTo(0);
    }

    // --- helpers -----------------------------------------------------------

    private static int run(String... args) {
        return new CommandLine(new Jk()).execute(args);
    }

    private static String captureStdout(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
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
