// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkHttpConfig;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a real {@link HttpEngineServer} bound to an OS-assigned loopback port with the JDK's
 * {@link HttpClient} — plus a raw socket where the client won't cooperate (forged {@code Host}
 * headers, literal {@code ..} request targets).
 */
class HttpEngineServerTest {

    private static final StatusSnapshot SNAPSHOT =
            new StatusSnapshot("9.9.9-test", 42, 1_000, 120, 1, 1_000, 2_000, 3_000, -1);

    @TempDir
    Path wwwRoot;

    @TempDir
    Path stateDir;

    private Path tokenFile;
    private HttpEvents events;
    private final java.util.List<String> triggeredDirs = new java.util.ArrayList<>();
    private HttpEngineServer server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    /** The stub {@link BuildTrigger}: records the dir, returns a fixed id, rejects "reject me". */
    private long stubTrigger(String dir) {
        if (dir.contains("reject")) throw new IllegalArgumentException("no jk.toml in " + dir);
        triggeredDirs.add(dir);
        return 7;
    }

    @BeforeEach
    void start() throws IOException {
        Files.writeString(wwwRoot.resolve("hello.txt"), "hi from disk");
        Files.writeString(wwwRoot.resolve("index.html"), "<html>dash</html>");
        Files.writeString(wwwRoot.resolve("shared.txt"), "from disk");
        // The token file lives OUTSIDE www-root (a token inside it would be served as content).
        tokenFile = stateDir.resolve("e2e.http-token");
        events = new HttpEvents();
        JkHttpConfig config = new JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString());
        server = new HttpEngineServer(
                config, wwwRoot, tokenFile, "9.9.9-test", () -> SNAPSHOT, events, this::stubTrigger, null);
        server.start();
        baseUrl = server.url();
        port = Integer.parseInt(baseUrl.replaceAll(".*:(\\d+)/$", "$1"));
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private String token() throws IOException {
        return Files.readString(tokenFile).trim();
    }

    private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path.substring(1)));
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A raw HTTP/1.1 exchange, for requests {@link HttpClient} refuses to send. */
    private String raw(String target, String hostHeader) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = "GET " + target + " HTTP/1.1\r\n"
                    + (hostHeader != null ? "Host: " + hostHeader + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(UTF_8));
            return new String(socket.getInputStream().readAllBytes(), UTF_8);
        }
    }

    @Test
    void url_reports_the_bound_loopback_address() {
        assertThat(baseUrl).startsWith("http://127.0.0.1:").endsWith("/");
        assertThat(port).isGreaterThan(0);
    }

    @Test
    void serves_disk_content_with_revalidation_headers() throws Exception {
        HttpResponse<String> resp = get("/hello.txt");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("hi from disk");
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(resp.headers().firstValue("Last-Modified")).isPresent();
    }

    @Test
    void serves_index_html_for_directory_requests() throws Exception {
        HttpResponse<String> resp = get("/");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("<html>dash</html>");
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
    }

    @Test
    void disk_overrides_classpath() throws Exception {
        // shared.txt exists in both this test's www-root and the test classpath's /www.
        assertThat(get("/shared.txt").body()).isEqualTo("from disk");
    }

    @Test
    void ships_the_dashboard_spa_on_the_classpath() throws Exception {
        // The real SPA (kernel/engine/src/main/resources/www) rides the same classpath fallback the
        // test resources exercise — a bare [http] table gives a working dashboard with no file copying.
        assertThat(get("/app.js").body()).contains("PetiteVue.createApp");
        assertThat(get("/fold.js").body()).contains("export function foldEvent");
        assertThat(get("/api.js").body()).contains("bootstrapToken");
        assertThat(get("/vendor/petite-vue.iife.js").body()).startsWith("/*! petite-vue v0.4.1");
        assertThat(get("/favicon.svg").headers().firstValue("Content-Type")).contains("image/svg+xml");
        HttpResponse<String> css = get("/style.css");
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
        HttpResponse<String> js = get("/app.js");
        assertThat(js.headers().firstValue("Content-Security-Policy"))
                .contains("default-src 'self'; script-src 'self' 'unsafe-eval'");
    }

    @Test
    void falls_back_to_classpath_with_version_etag() throws Exception {
        HttpResponse<String> resp = get("/classpath-only.txt");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("from classpath\n");
        assertThat(resp.headers().firstValue("ETag")).contains("\"jk-9.9.9-test\"");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("max-age=3600");
    }

    @Test
    void matching_etag_yields_304() throws Exception {
        HttpResponse<String> resp = get("/classpath-only.txt", "If-None-Match", "\"jk-9.9.9-test\"");
        assertThat(resp.statusCode()).isEqualTo(304);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void if_modified_since_yields_304_for_unchanged_disk_file() throws Exception {
        String lastModified = get("/hello.txt").headers().firstValue("Last-Modified").orElseThrow();
        HttpResponse<String> resp = get("/hello.txt", "If-Modified-Since", lastModified);
        assertThat(resp.statusCode()).isEqualTo(304);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void missing_content_is_404() throws Exception {
        assertThat(get("/no-such-file.txt").statusCode()).isEqualTo(404);
    }

    @Test
    void empty_disk_file_serves_as_empty_200() throws Exception {
        Files.writeString(wwwRoot.resolve("empty.json"), "");
        HttpResponse<String> resp = get("/empty.json");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void growing_file_serves_current_bytes_on_each_fetch() throws Exception {
        Path log = wwwRoot.resolve("build-log.json");
        Files.writeString(log, "{\"lines\":1}");
        assertThat(get("/build-log.json").body()).isEqualTo("{\"lines\":1}");
        Files.writeString(log, "{\"lines\":1}{\"lines\":2}");
        assertThat(get("/build-log.json").body()).isEqualTo("{\"lines\":1}{\"lines\":2}");
    }

    @Test
    void head_reports_length_without_body() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "hello.txt"))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Length")).contains(String.valueOf("hi from disk".length()));
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void mutating_methods_are_405_on_static_content() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "hello.txt"))
                        .POST(HttpRequest.BodyPublishers.ofString("x"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET, HEAD");
    }

    @Test
    void traversal_attempts_cannot_escape_the_root() throws Exception {
        // A secret outside www-root that a traversal would reach if the gate leaked.
        Files.writeString(wwwRoot.resolveSibling("secret.txt"), "top secret");
        // Encoded dots arrive decoded as ".." and are rejected by the segment gate.
        assertThat(get("/%2e%2e/secret.txt").statusCode()).isEqualTo(404);
        assertThat(get("/sub/%2e%2e/%2e%2e/secret.txt").statusCode()).isEqualTo(404);
        // A literal ".." request target (HttpClient normalizes these away; send raw). The JDK
        // server may reject it before our handler runs — any non-200 without the payload is a pass.
        String rawResponse = raw("/../secret.txt", "127.0.0.1:" + port);
        assertThat(rawResponse).doesNotContain("top secret");
        assertThat(rawResponse).doesNotContain("HTTP/1.1 200");
    }

    @Test
    void forged_host_header_is_421() throws Exception {
        String response = raw("/hello.txt", "evil.example.com");
        assertThat(response).startsWith("HTTP/1.1 421");
        assertThat(response).doesNotContain("hi from disk");
    }

    @Test
    void legitimate_host_forms_are_accepted() throws Exception {
        assertThat(raw("/hello.txt", "127.0.0.1:" + port)).contains("hi from disk");
        assertThat(raw("/hello.txt", "localhost:" + port)).contains("hi from disk");
    }

    @Test
    void host_with_wrong_port_is_421() throws Exception {
        assertThat(raw("/hello.txt", "127.0.0.1:1")).startsWith("HTTP/1.1 421");
    }

    @Test
    void api_status_reports_engine_vitals_without_a_token_on_loopback() throws Exception {
        HttpResponse<String> resp = get("/api/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(resp.body())
                .contains("\"version\":\"9.9.9-test\"")
                .contains("\"pid\":42")
                .contains("\"idleMinutes\":120")
                .contains("\"neverIdles\":true")
                .contains("\"heapMaxBytes\":3000")
                .contains("\"rssBytes\":-1")
                .contains("\"httpUrl\":\"" + baseUrl + "\"");
    }

    @Test
    void unknown_api_endpoint_is_404() throws Exception {
        assertThat(get("/api/no-such-thing").statusCode()).isEqualTo(404);
    }

    @Test
    void mutation_without_token_is_401_even_on_loopback() throws Exception {
        // The CSRF defense: a hostile page can fire a no-preflight POST at 127.0.0.1, but can't
        // attach an Authorization header cross-origin.
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(resp.headers().firstValue("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    void mutation_with_wrong_token_is_401() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .header("Authorization", "Bearer not-the-token")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    void mutation_with_valid_token_reaches_the_router() throws Exception {
        // No mutating routes exist yet, so a correctly authorized POST to a GET-only path is the
        // router's 405 — proving the token was accepted (401 would mean it wasn't).
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/status"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(405);
        assertThat(resp.headers().firstValue("Allow")).contains("GET");
    }

    @Test
    void token_file_is_owner_only() throws IOException {
        assertThat(token()).isNotEmpty();
        assertThat(java.nio.file.Files.getPosixFilePermissions(tokenFile))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void non_loopback_bind_gates_api_reads_but_not_static() throws Exception {
        JkHttpConfig config = new JkHttpConfig("0.0.0.0", 0, 16, wwwRoot.toString());
        Path lanTokenFile = stateDir.resolve("lan.http-token");
        HttpEngineServer lan = new HttpEngineServer(
                config, wwwRoot, lanTokenFile, "9.9.9-test", () -> SNAPSHOT, new HttpEvents(), this::stubTrigger, null);
        try {
            lan.start();
            String lanUrl = lan.url(); // advertises the always-valid loopback form for a wildcard bind
            String lanToken = Files.readString(lanTokenFile).trim();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            HttpResponse<String> authorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/status"))
                            .header("Authorization", "Bearer " + lanToken)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(authorized.statusCode()).isEqualTo(200);

            HttpResponse<String> staticContent = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "hello.txt")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(staticContent.statusCode()).isEqualTo(200); // the shell must load by plain navigation
        } finally {
            lan.close();
        }
    }

    @Test
    void saturated_admission_gate_yields_503_with_retry_after() throws Exception {
        int permits = server.admission().drainPermits();
        try {
            HttpResponse<String> resp = get("/hello.txt");
            assertThat(resp.statusCode()).isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).contains("1");
        } finally {
            server.admission().release(permits);
        }
        assertThat(get("/hello.txt").statusCode()).isEqualTo(200);
    }

    // ---- /api/events (SSE) ----------------------------------------------------------------------

    /** Open the SSE stream and return a line iterator (the JDK client de-chunks for us). */
    private java.util.Iterator<String> openEvents(String query) throws Exception {
        HttpResponse<java.util.stream.Stream<String>> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/events" + query)).build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/event-stream; charset=utf-8");
        return resp.body().iterator();
    }

    /** Read the next line with a timeout — a hung stream must fail the test, not the build. */
    private static String nextLine(java.util.Iterator<String> lines) throws Exception {
        return java.util.concurrent.CompletableFuture.supplyAsync(lines::next)
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void events_stream_delivers_published_frames_in_sse_format() throws Exception {
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        events.publish("request-start", JsonOut.object().put("requestId", 1).put("kind", "build"));
        assertThat(nextLine(lines)).isEqualTo(""); // blank line terminating the connected comment
        assertThat(nextLine(lines)).startsWith("id: ");
        assertThat(nextLine(lines)).isEqualTo("event: request-start");
        assertThat(nextLine(lines)).isEqualTo("data: {\"requestId\":1,\"kind\":\"build\"}");
    }

    @Test
    void quiet_events_stream_heartbeats() throws Exception {
        server.heartbeatMillis(50);
        var lines = openEvents("");
        assertThat(nextLine(lines)).isEqualTo(": connected");
        assertThat(nextLine(lines)).isEqualTo("");
        assertThat(nextLine(lines)).isEqualTo(": heartbeat"); // no events published — comment keepalive
    }

    @Test
    void events_accepts_access_token_query_param_on_non_loopback_binds() throws Exception {
        JkHttpConfig config = new JkHttpConfig("0.0.0.0", 0, 16, wwwRoot.toString());
        HttpEngineServer lan = new HttpEngineServer(
                config,
                wwwRoot,
                stateDir.resolve("sse.http-token"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                this::stubTrigger,
                null);
        try {
            lan.start();
            String lanToken = Files.readString(stateDir.resolve("sse.http-token")).trim();
            String lanUrl = lan.url();

            HttpResponse<String> unauthorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/events")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401); // EventSource can't send headers...

            HttpResponse<java.util.stream.Stream<String>> authorized = client.send(
                    HttpRequest.newBuilder(URI.create(lanUrl + "api/events?access_token=" + lanToken))
                            .build(),
                    HttpResponse.BodyHandlers.ofLines()); // ...so the query param is its way in
            assertThat(authorized.statusCode()).isEqualTo(200);
            authorized.body().close();
        } finally {
            lan.close();
        }
    }

    // ---- POST /api/build ------------------------------------------------------------------------

    private HttpResponse<String> postBuild(String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "api/build"))
                        .header("Authorization", "Bearer " + token())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void build_trigger_acknowledges_with_request_id() throws Exception {
        HttpResponse<String> resp = postBuild("{\"dir\":\"/some/workspace\"}");
        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(resp.body()).contains("\"requestId\":7").contains("\"events\":\"/api/events\"");
        assertThat(triggeredDirs).containsExactly("/some/workspace");
    }

    @Test
    void build_without_dir_is_400() throws Exception {
        assertThat(postBuild("{}").statusCode()).isEqualTo(400);
        assertThat(triggeredDirs).isEmpty();
    }

    @Test
    void build_of_an_unbuildable_dir_relays_the_trigger_error_as_400() throws Exception {
        HttpResponse<String> resp = postBuild("{\"dir\":\"/reject/me\"}");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("no jk.toml in /reject/me");
    }
}
