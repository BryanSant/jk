// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        var prev = cc.jumpkick.config.SessionContext.current().config();
        cc.jumpkick.config.SessionContext.installConfig(prev.mergedWith(new cc.jumpkick.config.JkConfig(
                Optional.empty(), Optional.of(true),
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty())));
        try {
            assertThatThrownBy(() -> http().get(base.resolve("/anything")))
                    .isInstanceOf(OfflineException.class)
                    .hasMessageContaining("offline:");
        } finally {
            cc.jumpkick.config.SessionContext.installConfig(prev);
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
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello, gzip world");
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

        http().get(base.resolve("/hello"), Map.of("Accept-Encoding", "identity"));
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
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("streamed payload");
        }
    }

    @Test
    void post_form_sends_urlencoded_body_and_accepts_json() throws Exception {
        AtomicReference<String> seenContentType = new AtomicReference<>();
        AtomicReference<String> seenAccept = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        server.createContext("/device/code", exchange -> {
            seenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        // LinkedHashMap to keep the encoded order deterministic for the assertion.
        var form = new LinkedHashMap<String, String>();
        form.put("client_id", "Iv1.abc");
        form.put("scope", "read:packages");
        HttpResponse<byte[]> response = http().postForm(base.resolve("/device/code"), form);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(seenContentType.get()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(seenAccept.get()).isEqualTo("application/json");
        assertThat(seenBody.get()).isEqualTo("client_id=Iv1.abc&scope=read%3Apackages");
    }

    @Test
    void post_form_returns_4xx_body_without_throwing() throws Exception {
        // The device-flow poll loop depends on this: GitHub signals
        // authorization_pending with a non-2xx status + JSON error body.
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/device/token", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"authorization_pending\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpResponse<byte[]> response =
                http().postForm(base.resolve("/device/token"), Map.of("grant_type", "device_code"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("authorization_pending");
        assertThat(calls.get()).isEqualTo(1); // 4xx is not retried
    }

    @Test
    void post_form_retries_on_503_then_succeeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/flaky-post", exchange -> {
            int n = calls.incrementAndGet();
            // Drain the request body so the connection can be reused.
            exchange.getRequestBody().readAllBytes();
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"access_token\":\"gho_x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().postForm(base.resolve("/flaky-post"), Map.of("client_id", "Iv1.abc"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void put_sends_body_and_headers() throws Exception {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        AtomicReference<String> seenMethod = new AtomicReference<>();
        AtomicReference<byte[]> seenBody = new AtomicReference<>();
        server.createContext("/repo/artifact.jar", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        byte[] payload = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> response = http().put(
                        base.resolve("/repo/artifact.jar"),
                        payload,
                        Map.of("Authorization", "Basic dTpw", "Content-Type", "application/java-archive"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(seenMethod.get()).isEqualTo("PUT");
        assertThat(seenAuth.get()).isEqualTo("Basic dTpw");
        assertThat(seenBody.get()).isEqualTo(payload);
    }

    @Test
    void put_returns_4xx_without_throwing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/repo/denied.jar", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().put(base.resolve("/repo/denied.jar"), new byte[] {1, 2, 3}, Map.of());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(calls.get()).isEqualTo(1); // 4xx is not retried
    }

    @Test
    void put_retries_on_503_then_succeeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/repo/flaky.jar", exchange -> {
            int n = calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (n < 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });

        HttpResponse<byte[]> response = http().put(base.resolve("/repo/flaky.jar"), new byte[] {9}, Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(calls.get()).isEqualTo(3);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(buf)) {
            gz.write(raw);
        }
        return buf.toByteArray();
    }

    private static Http http() {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        // Tight backoff to keep the test fast — three attempts at 1ms each.
        return new Http(client, new Duration[] {Duration.ofMillis(1), Duration.ofMillis(1)});
    }
}
