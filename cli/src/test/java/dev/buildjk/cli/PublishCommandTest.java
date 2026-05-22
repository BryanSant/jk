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
    void signed_publish_uploads_asc_files(@TempDir Path tempDir) throws Exception {
        // Generate a throwaway secret key via the supply-chain test fixture.
        var key = dev.buildjk.publish.GpgTestFixture.generate(tempDir, "pass");

        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--no-sources",
                "--sign",
                "--key-file", key.secretKeyFile().toString(),
                "--key-passphrase", "pass");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(stem + ".jar.asc", stem + ".pom.asc");
        assertThat(new String(received.get(stem + ".jar.asc"), StandardCharsets.UTF_8))
                .startsWith("-----BEGIN PGP SIGNATURE-----");
    }

    @Test
    void sign_without_key_file_errors(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));
        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--no-sources",
                "--sign");
        // CommandLine propagates the runtime error as a non-zero exit.
        assertThat(exit).isNotZero();
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

    @Test
    void slsa_emits_intoto_provenance_for_the_main_jar(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--no-sources",
                "--slsa");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKey(stem + ".intoto.json");
        String provenance = new String(received.get(stem + ".intoto.json"), StandardCharsets.UTF_8);
        assertThat(provenance).contains("\"_type\":\"https://in-toto.io/Statement/v1\"");
        assertThat(provenance).contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"");
        assertThat(provenance).contains("\"name\":\"widget-1.0.0.jar\"");
    }

    @Test
    void sbom_emits_cyclonedx_and_spdx_sidecars(@TempDir Path tempDir) throws Exception {
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--no-sources",
                "--sbom");
        assertThat(exit).isEqualTo(0);

        String stem = "/repo/com/example/widget/1.0.0/widget-1.0.0";
        assertThat(received).containsKeys(
                stem + "-cyclonedx.json",
                stem + "-cyclonedx.json.sha256",
                stem + "-spdx.json",
                stem + "-spdx.json.sha256");
        assertThat(new String(received.get(stem + "-cyclonedx.json"), StandardCharsets.UTF_8))
                .contains("\"bomFormat\":\"CycloneDX\"")
                .contains("pkg:maven/com.example/widget@1.0.0");
        assertThat(new String(received.get(stem + "-spdx.json"), StandardCharsets.UTF_8))
                .contains("\"spdxVersion\":\"SPDX-2.3\"")
                .contains("\"documentDescribes\":[\"SPDXRef-Package-Root\"]");
    }

    @Test
    void sigstore_dry_run_does_not_call_fulcio(@TempDir Path tempDir) throws Exception {
        // --dry-run must not attempt to initialise the keyless signer, which
        // would otherwise need network + OIDC. Same goes for --sign without a
        // key file — dry-run is the path users hit while exploring the verb.
        writeBuildJk(tempDir);
        writeJar(tempDir.resolve("target/widget-1.0.0.jar"));

        int exit = run("publish",
                "-C", tempDir.toString(),
                "--repo-url", base.toString(),
                "--sigstore",
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
