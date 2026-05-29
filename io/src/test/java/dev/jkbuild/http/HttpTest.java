// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

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
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty())));
        try {
            assertThatThrownBy(() -> http().get(base.resolve("/anything")))
                    .isInstanceOf(OfflineException.class)
                    .hasMessageContaining("offline:");
        } finally {
            dev.jkbuild.config.ActiveConfig.install(prev);
        }
    }

    @Test
    void get_sends_accept_encoding_gzip_by_default() throws Exception {
        AtomicReference<String> seenAcceptEncoding = new AtomicReference<>();
        server.createContext("/hello", exchange -> {
            seenAcceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        http().get(base.resolve("/hello"));
        assertThat(seenAcceptEncoding.get()).isEqualTo("gzip");
    }

    @Test
    void get_transparently_decompresses_gzip_response() throws Exception {
        byte[] payload = "hello, gzip world".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(payload);

        server.createContext("/gz", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gzipped.length);
            exchange.getResponseBody().write(gzipped);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().get(base.resolve("/gz"));
        assertThat(response.statusCode()).isEqualTo(200);
        // Body is the *decompressed* payload, not the wire bytes.
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo("hello, gzip world");
    }

    @Test
    void get_passes_plain_response_through_unchanged() throws Exception {
        // No Content-Encoding on the response → we hand the bytes back as-is
        // even though we sent Accept-Encoding: gzip.
        server.createContext("/plain", exchange -> {
            byte[] body = "plain text".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().get(base.resolve("/plain"));
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("plain text");
    }

    @Test
    void caller_supplied_accept_encoding_overrides_default() throws Exception {
        AtomicReference<String> seenAcceptEncoding = new AtomicReference<>();
        server.createContext("/hello", exchange -> {
            seenAcceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        http().get(base.resolve("/hello"), java.util.Map.of("Accept-Encoding", "identity"));
        assertThat(seenAcceptEncoding.get()).isEqualTo("identity");
    }

    @Test
    void get_stream_decompresses_gzip_response() throws Exception {
        byte[] payload = "streamed payload".getBytes(StandardCharsets.UTF_8);
        byte[] gzipped = gzip(payload);

        server.createContext("/gz-stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, gzipped.length);
            exchange.getResponseBody().write(gzipped);
            exchange.close();
        });

        HttpResponse<InputStream> response = http().getStream(base.resolve("/gz-stream"));
        try (var in = response.body()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("streamed payload");
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(buf)) {
            gz.write(raw);
        }
        return buf.toByteArray();
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
