// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import com.sun.net.httpserver.HttpServer;
import dev.buildjk.http.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoClientTest {

    private HttpServer server;
    private URI base;
    private final Map<String, byte[]> served = new HashMap<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String pathAndQuery = exchange.getRequestURI().getPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                pathAndQuery += "?" + exchange.getRequestURI().getRawQuery();
            }
            lastRequestPath.set(pathAndQuery);
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
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/disco/");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void search_returns_packages_from_fixture() throws Exception {
        servePath("/disco/packages", """
                {
                  "result": [
                    {
                      "distribution": "temurin",
                      "java_version": "21.0.5",
                      "architecture": "x64",
                      "operating_system": "linux",
                      "archive_type": "tar.gz",
                      "filename": "OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz",
                      "size": 207258112,
                      "sha256": "abc123",
                      "links": {
                        "pkg_download_redirect": "https://example.com/openjdk21.tar.gz"
                      }
                    }
                  ]
                }
                """);

        DiscoClient client = new DiscoClient(new Http(), base);
        List<JdkPackage> packages = client.search(DiscoClient.SearchQuery.builder()
                .distribution("temurin").version("21").build());

        assertThat(packages).singleElement().satisfies(p -> {
            assertThat(p.distribution()).isEqualTo("temurin");
            assertThat(p.version()).isEqualTo("21.0.5");
            assertThat(p.sha256()).isEqualTo("abc123");
            assertThat(p.downloadUri().toString()).isEqualTo("https://example.com/openjdk21.tar.gz");
            assertThat(p.size()).isEqualTo(207258112L);
        });
    }

    @Test
    void search_forwards_query_parameters_with_canonical_defaults() throws Exception {
        servePath("/disco/packages", """
                { "result": [] }
                """);
        DiscoClient client = new DiscoClient(new Http(), base);
        client.search(DiscoClient.SearchQuery.builder()
                .distribution("temurin")
                .version("21")
                .architecture("x64")
                .operatingSystem("linux")
                .build());

        String request = lastRequestPath.get();
        assertThat(request).contains("distribution=temurin");
        assertThat(request).contains("version=21");
        assertThat(request).contains("architecture=x64");
        assertThat(request).contains("operating_system=linux");
        // Canonical defaults applied automatically.
        assertThat(request).contains("release_status=ga");
        assertThat(request).contains("package_type=jdk");
    }

    @Test
    void non_200_status_throws() {
        // Nothing served — server returns 404 for /disco/packages.
        DiscoClient client = new DiscoClient(new Http(), base);
        assertThatThrownBy(() -> client.search(DiscoClient.SearchQuery.builder().build()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void sdkman_identifier_uses_canonical_distribution_abbreviation() {
        JdkPackage temurin = new JdkPackage("temurin", "21.0.5", "x64", "linux",
                "tar.gz", "x.tar.gz", URI.create("https://x"), "abc", 100);
        assertThat(temurin.sdkmanIdentifier()).isEqualTo("21.0.5-tem");

        JdkPackage graalvm = new JdkPackage("graalvm_ce", "21.0.2", "x64", "linux",
                "tar.gz", "x.tar.gz", URI.create("https://x"), "abc", 100);
        assertThat(graalvm.sdkmanIdentifier()).isEqualTo("21.0.2-graalce");

        JdkPackage corretto = new JdkPackage("corretto", "21.0.5", "x64", "linux",
                "tar.gz", "x.tar.gz", URI.create("https://x"), "abc", 100);
        assertThat(corretto.sdkmanIdentifier()).isEqualTo("21.0.5-amzn");
    }

    private void servePath(String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
