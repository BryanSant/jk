// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkCatalogClientTest {

    private HttpServer server;
    private URI feed;
    private byte[] jsonBody;
    private AtomicInteger hits;

    @BeforeEach
    void start() throws IOException {
        jsonBody = SAMPLE.getBytes(StandardCharsets.UTF_8);
        hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed/jdks.json", exchange -> {
            hits.incrementAndGet();
            String ifModSince = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            if (ifModSince != null) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.sendResponseHeaders(200, jsonBody.length);
                exchange.getResponseBody().write(jsonBody);
            }
            exchange.close();
        });
        server.start();
        feed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed/jdks.json");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void parses_json_feed(@TempDir Path tempDir) throws Exception {
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, tempDir.resolve("jdks.json"), Duration.ZERO);
        JdkCatalog catalog = client.fetch();

        assertThat(catalog.entries())
                .extracting(
                        JdkCatalog.Entry::vendor,
                        JdkCatalog.Entry::product,
                        JdkCatalog.Entry::version,
                        JdkCatalog.Entry::os,
                        JdkCatalog.Entry::arch,
                        JdkCatalog.Entry::installFolderName,
                        JdkCatalog.Entry::javaHomeSubpath)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(
                                "Eclipse", "Temurin", "21.0.5", "linux", "x86_64", "temurin-21.0.5", ""),
                        org.assertj.core.api.Assertions.tuple(
                                "Eclipse", "Temurin", "21.0.5", "macOS", "aarch64", "temurin-21.0.5", "Contents/Home"));
    }

    @Test
    void cache_hit_within_ttl_skips_network(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("jdks.json");
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, cache, Duration.ofHours(24));
        client.fetch();
        int after1 = hits.get();
        client.fetch();
        assertThat(hits.get()).isEqualTo(after1);
    }

    @Test
    void second_fetch_after_ttl_revalidates_with_if_modified_since(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("jdks.json");
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, cache, Duration.ZERO);
        client.fetch();
        int after1 = hits.get();
        // Second call hits the server (TTL=0); server returns 304.
        JdkCatalog catalog = client.fetch();
        assertThat(hits.get()).isGreaterThan(after1);
        assertThat(catalog.entries()).isNotEmpty();
    }

    @Test
    void first_class_fetch_keeps_only_lts_and_latest_majors(@TempDir Path tempDir) throws Exception {
        // Feed spans below-floor (11), interim non-LTS (19), LTS (21), latest (26).
        jsonBody = multiMajorFeed().getBytes(StandardCharsets.UTF_8);
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, tempDir.resolve("jdks.json"), Duration.ZERO);

        assertThat(client.fetch().entries())
                .extracting(JdkCatalog.Entry::majorVersion)
                .containsExactlyInAnyOrder(21, 26); // 11 below floor, 19 not LTS/latest
    }

    @Test
    void all_supported_fetch_keeps_every_major_at_or_above_the_floor(@TempDir Path tempDir) throws Exception {
        jsonBody = multiMajorFeed().getBytes(StandardCharsets.UTF_8);
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, tempDir.resolve("jdks.json"), Duration.ZERO);

        assertThat(client.fetch(false, /* firstClassOnly= */ false).entries())
                .extracting(JdkCatalog.Entry::majorVersion)
                .containsExactlyInAnyOrder(19, 21, 26) // 11 still dropped (below 17)
                .doesNotContain(11);
    }

    @Test
    void first_class_latest_ignores_preview_only_majors(@TempDir Path tempDir) throws Exception {
        // 26 is the newest GA; 27 exists only as an early-access build. The
        // latest-major slot must go to 26 (and 27 must be dropped), not the
        // other way around.
        jsonBody = ("{\n  \"jdks\": [\n"
                        + String.join(
                                ",\n",
                                jdkBlock(21, "21.0.5"), // LTS
                                jdkBlock(26, "26.0.1"), // newest GA
                                previewBlock(27, "27-ea+22")) // early access only
                        + "\n  ]\n}\n")
                .getBytes(StandardCharsets.UTF_8);
        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, tempDir.resolve("jdks.json"), Duration.ZERO);

        assertThat(client.fetch().entries())
                .extracting(JdkCatalog.Entry::majorVersion)
                .containsExactlyInAnyOrder(21, 26) // 26 kept as latest GA; 27 EA dropped
                .doesNotContain(27);
    }

    @Test
    void offline_falls_back_to_cache(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("jdks.json");
        Files.createDirectories(cache.getParent());
        Files.write(cache, jsonBody);
        // Stop the server so the next fetch fails on the wire.
        server.stop(0);

        JdkCatalogClient client = new JdkCatalogClient(new Http(), feed, cache, Duration.ZERO);
        JdkCatalog catalog = client.fetch();
        assertThat(catalog.entries()).isNotEmpty();
    }

    /** A feed with one Temurin entry per major in {11, 19, 21, 26}. */
    private static String multiMajorFeed() {
        return "{\n  \"jdks\": [\n"
                + String.join(
                        ",\n",
                        jdkBlock(11, "11.0.25"),
                        jdkBlock(19, "19.0.2"),
                        jdkBlock(21, "21.0.5"),
                        jdkBlock(26, "26.0.1"))
                + "\n  ]\n}\n";
    }

    /** A preview/early-access JDK block (carries {@code "preview": true}). */
    private static String previewBlock(int major, String version) {
        return jdkBlock(major, version)
                .replaceFirst(
                        "\"jdk_version_major\": " + major + ",",
                        "\"jdk_version_major\": " + major + ",\n      \"preview\": true,");
    }

    private static String jdkBlock(int major, String version) {
        return """
                    {
                      "vendor": "Eclipse",
                      "product": "Temurin",
                      "jdk_version_major": %d,
                      "jdk_version": "%s",
                      "suggested_sdk_name": "temurin-%d",
                      "packages": [
                        {
                          "os": "linux",
                          "arch": "x86_64",
                          "version": "%s",
                          "url": "https://example.invalid/temurin-%s-linux-x64.tar.gz",
                          "package_type": "targz",
                          "package_to_java_home_prefix": "",
                          "install_folder_name": "temurin-%s",
                          "archive_size": 2048,
                          "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                        }
                      ]
                    }""".formatted(major, version, major, version, version, version);
    }

    private static final String SAMPLE = """
            {
              "jdks": [
                {
                  "vendor": "Eclipse",
                  "product": "Temurin",
                  "default": true,
                  "jdk_version_major": 21,
                  "jdk_version": "21.0.5",
                  "suggested_sdk_name": "temurin-21",
                  "shared_index_aliases": ["temurin-21.0.5", "temurin-21", "21.0.5", "21"],
                  "packages": [
                    {
                      "os": "linux",
                      "arch": "x86_64",
                      "version": "21.0.5",
                      "url": "https://example.invalid/temurin-21.0.5-linux-x64.tar.gz",
                      "package_type": "targz",
                      "unpack_prefix_filter": "jdk-21.0.5",
                      "package_root_prefix": "jdk-21.0.5",
                      "package_to_java_home_prefix": "",
                      "archive_file_name": "temurin-21.0.5_linux-x64.tar.gz",
                      "install_folder_name": "temurin-21.0.5",
                      "archive_size": 2048,
                      "unpacked_size": 4096,
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                    }
                  ]
                },
                {
                  "vendor": "Eclipse",
                  "product": "Temurin",
                  "default": true,
                  "jdk_version_major": 21,
                  "jdk_version": "21.0.5",
                  "suggested_sdk_name": "temurin-21",
                  "shared_index_aliases": ["temurin-21.0.5", "temurin-21", "21.0.5", "21"],
                  "packages": [
                    {
                      "os": "macOS",
                      "arch": "aarch64",
                      "version": "21.0.5",
                      "url": "https://example.invalid/temurin-21.0.5-macos-aarch64.tar.gz",
                      "package_type": "targz",
                      "unpack_prefix_filter": "./jdk-21.0.5.jdk/Contents/Home",
                      "package_root_prefix": "./jdk-21.0.5.jdk",
                      "package_to_java_home_prefix": "Contents/Home",
                      "archive_file_name": "temurin-21.0.5_macos-aarch64.tar.gz",
                      "install_folder_name": "temurin-21.0.5",
                      "archive_size": 2048,
                      "unpacked_size": 4096,
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                    }
                  ]
                }
              ]
            }
            """;
}
