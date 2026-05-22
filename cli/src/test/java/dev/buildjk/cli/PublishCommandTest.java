// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import com.sun.net.httpserver.HttpServer;
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

class PublishCommandTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> received = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                received.put(exchange.getRequestURI().getPath(),
                        exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(201, -1);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/repo/");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void publishes_jar_pom_sources_and_checksums(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));
        writeSource(tempDir.resolve("src/main/java/com/example/Widget.java"),
                "package com.example; public class Widget {}");

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString());
        assertThat(exit).isEqualTo(0);

        String prefix = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        // Main jar, POM, sources jar — each with four checksum files.
        assertThat(received).containsKeys(
                prefix + ".jar",
                prefix + ".jar.sha256",
                prefix + ".pom",
                prefix + ".pom.sha256",
                prefix + "-sources.jar",
                prefix + "-sources.jar.sha256");
        String pom = new String(received.get(prefix + ".pom"), StandardCharsets.UTF_8);
        assertThat(pom).contains("<artifactId>widget</artifactId>");
        assertThat(pom).contains("<version>1.0.0</version>");
    }

    @Test
    void snapshot_version_refused_by_default(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.jk"), """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "1.0.0-SNAPSHOT"
                  jdk      = "21"
                }
                """);
        writeJar(tempDir.resolve("target/widget-1.0.0-SNAPSHOT.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString());
        assertThat(exit).isEqualTo(65); // EX_DATAERR
        assertThat(received).isEmpty();
    }

    @Test
    void snapshot_version_allowed_with_flag(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("build.jk"), """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "1.0.0-SNAPSHOT"
                  jdk      = "21"
                }
                """);
        writeJar(tempDir.resolve("target/widget-1.0.0-SNAPSHOT.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--allow-snapshot",
                "--no-sources");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void no_sources_flag_skips_sources_jar(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--no-sources");
        assertThat(exit).isEqualTo(0);
        assertThat(received.keySet()).noneMatch(k -> k.contains("-sources.jar"));
    }

    @Test
    void missing_jar_returns_no_input(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString());
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void dry_run_makes_no_http_requests(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--dry-run");
        assertThat(exit).isEqualTo(0);
        assertThat(received).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private static void writeBuildJk(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("build.jk"), """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "1.0.0"
                  jdk      = "21"
                }
                """);
    }

    private static void writeJar(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        // The publisher just streams bytes — content doesn't need to be a real jar.
        Files.write(path, "pretend-jar".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSource(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static int run(String... args) {
        CommandLine cmd = Jk.newCommandLine();
        return cmd.execute(args);
    }
}
