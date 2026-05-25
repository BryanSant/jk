// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTest {

    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void get_returns_body() throws Exception {
        server.createContext("/hello", exchange -> {
            byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        Http http = http();
        HttpResponse<byte[]> response = http.get(base.resolve("/hello"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hi");
    }

    @Test
    void retries_on_503_then_succeeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/flaky", exchange -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().get(base.resolve("/flaky"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void does_not_retry_on_404() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/missing", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().get(base.resolve("/missing"));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void gives_up_after_max_attempts() {
        server.createContext("/dead", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> http().get(base.resolve("/dead")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("503");
    }

    @Test
    void offline_short_circuits_with_offline_exception() {
        var prev = dev.jkbuild.config.ActiveConfig.get();
        dev.jkbuild.config.ActiveConfig.install(prev.mergedWith(new dev.jkbuild.config.JkConfig(
                java.util.Optional.empty(), java.util.Optional.of(true),
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty())));
        try {
            assertThatThrownBy(() -> http().get(base.resolve("/anything")))
                    .isInstanceOf(OfflineException.class)
                    .hasMessageContaining("offline:");
        } finally {
            dev.jkbuild.config.ActiveConfig.install(prev);
        }
    }

    private static Http http() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        // Tight backoff to keep the test fast — three attempts at 1ms each.
        return new Http(client, new Duration[]{
                Duration.ofMillis(1), Duration.ofMillis(1)
        });
    }
}
