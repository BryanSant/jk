// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkHttpConfig;
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
            new StatusSnapshot("9.9.9-test", 42, 1_000, 1, 0, 1_000, 2_000, 3_000, -1, -1);

    @TempDir
    Path wwwRoot;

    @TempDir
    Path stateDir;

    private Path tokenFile;
    private Path logFile;
    private HttpEvents events;
    private final java.util.List<String> triggeredDirs = new java.util.ArrayList<>();

    /** Rows served by {@code GET /api/metrics} — tests seed this list directly. */
    private final java.util.List<cc.jumpkick.runtime.BuildMetrics.Entry> metricsRows = new java.util.ArrayList<>();

    /** The snapshot served by {@code GET /api/cache} — tests reassign the field directly. */
    private static final CacheSnapshot EMPTY_CACHE = new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private CacheSnapshot cacheSnapshot = EMPTY_CACHE;
    private HttpEngineServer server;
    private HttpClient client;
    private String baseUrl;
    private int port;

    /** A journal rooted under the test's temp state dir — endpoints exist; content is per-test. */
    private cc.jumpkick.engine.journal.BuildJournal testJournal() {
        return new cc.jumpkick.engine.journal.BuildJournal(stateDir.resolve("builds").resolve("journal"));
    }

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
        logFile = stateDir.resolve("e2e.log");
        Files.writeString(logFile, "jk engine: listening\njk engine: http listening\nline three\n");
        events = new HttpEvents();
        JkHttpConfig config = new JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString());
        server = new HttpEngineServer(
                config, wwwRoot, tokenFile, logFile, "9.9.9-test", () -> SNAPSHOT, events, this::stubTrigger,
                testJournal(), () -> metricsRows, () -> cacheSnapshot, null);
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
        assertThat(get("/app.js").body()).contains("Vue.createApp");
        assertThat(get("/fold.js").body()).contains("export function foldEvent");
        assertThat(get("/api.js").body()).contains("bootstrapToken");
        assertThat(get("/jk-logo.svg").headers().firstValue("Content-Type")).contains("image/svg+xml");
        HttpResponse<String> css = get("/style.css");
        assertThat(css.headers().firstValue("Content-Type")).contains("text/css; charset=utf-8");
        // Vue rides the CDN, version-pinned and integrity-locked (docs/webclient.md) — the shell
        // must carry the pin + SRI, and the CSP must allow exactly that one external origin.
        // (Read from the classpath: this test's www-root shadows /index.html with its own.)
        String shell;
        try (var in = getClass().getResourceAsStream("/www/index.html")) {
            shell = new String(in.readAllBytes(), UTF_8);
        }
        assertThat(shell).contains("https://unpkg.com/vue@3.5.39/dist/vue.global.prod.js");
        assertThat(shell).contains("integrity=\"sha384-");
        assertThat(shell).contains("crossorigin=\"anonymous\"");
        HttpResponse<String> js = get("/app.js");
        assertThat(js.headers().firstValue("Content-Security-Policy"))
                .contains("default-src 'self'; script-src 'self' 'unsafe-eval' https://unpkg.com; "
                        + "style-src 'self' https://fonts.googleapis.com; "
                        + "font-src https://fonts.gstatic.com");
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
    void snapshot_versions_revalidate_classpath_assets_every_load() throws Exception {
        // A -SNAPSHOT jar swap doesn't move the version-derived ETag, so snapshot builds must not
        // let the browser cache classpath assets — otherwise an upgraded engine serves last jar's
        // dashboard for up to an hour.
        HttpEngineServer snapshot = new HttpEngineServer(
                new JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString()),
                wwwRoot,
                stateDir.resolve("snap.http-token"),
                stateDir.resolve("snap.log"),
                "0.10.0-SNAPSHOT",
                () -> SNAPSHOT,
                new HttpEvents(),
                this::stubTrigger,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
                null);
        try {
            snapshot.start();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(snapshot.url() + "classpath-only.txt")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Cache-Control")).contains("no-cache");
            assertThat(resp.headers().firstValue("ETag")).isEmpty();
        } finally {
            snapshot.close();
        }
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
                .contains("\"heapMaxBytes\":3000")
                .contains("\"rssBytes\":-1")
                .contains("\"httpUrl\":\"" + baseUrl + "\"");
    }

    @Test
    void api_status_carries_config_and_pipeline_fields() throws Exception {
        String body = get("/api/status").body();
        assertThat(body)
                .contains("\"activePipelines\":0")
                .contains("\"maxConcurrentRequests\":16")
                .contains("\"wwwRoot\":\"" + wwwRoot + "\"");
    }

    @Test
    void api_metrics_reports_aggregate_rows_without_a_token_on_loopback() throws Exception {
        var ok = new cc.jumpkick.runtime.BuildMetrics.Stats(3, 6000, 1000, 3000);
        var empty = cc.jumpkick.runtime.BuildMetrics.Stats.EMPTY;
        metricsRows.add(new cc.jumpkick.runtime.BuildMetrics.Entry("build", "", null, null, ok, empty, empty, 5L));
        metricsRows.add(new cc.jumpkick.runtime.BuildMetrics.Entry("build", "/p", "g:n", null, ok, empty, empty, 5L));
        metricsRows.add(new cc.jumpkick.runtime.BuildMetrics.Entry(
                null, "/other", null, "compile-java", ok, empty, empty, 5L));

        HttpResponse<String> resp = get("/api/metrics");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"scope\":\"global\"")
                .contains("\"scope\":\"project\"")
                .contains("\"scope\":\"project/step\"")
                .contains("\"okCount\":3")
                .contains("\"okAvgMillis\":2000")
                .contains("\"coord\":\"g:n\"");

        // ?dir= keeps the global tiers but drops other projects' rows.
        String filtered = get("/api/metrics?dir=/p").body();
        assertThat(filtered).contains("\"scope\":\"global\"").contains("\"dir\":\"/p\"");
        assertThat(filtered).doesNotContain("/other");
    }

    @Test
    void api_metrics_is_an_empty_array_when_nothing_has_been_recorded() throws Exception {
        assertThat(get("/api/metrics").body()).isEqualTo("[]");
    }

    @Test
    void api_cache_reports_the_cache_breakdown_without_a_token_on_loopback() throws Exception {
        cacheSnapshot = new CacheSnapshot(
                100, 5_000_000, 40, 200_000, 3, 30_000_000, 7, 9_000, 2, 100, 21_474_836_480L, 1_700_000_000_000L);
        HttpResponse<String> resp = get("/api/cache");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("application/json; charset=utf-8");
        assertThat(resp.body())
                .contains("\"casCount\":100")
                .contains("\"casBytes\":5000000")
                .contains("\"actionsCount\":40")
                .contains("\"workerJarsBytes\":30000000")
                .contains("\"totalCount\":152")
                .contains("\"totalBytes\":35209100")
                .contains("\"maxBytes\":21474836480")
                .contains("\"lastPrunedMillis\":1700000000000");
    }

    @Test
    void api_log_tails_the_engine_log() throws Exception {
        HttpResponse<String> resp = get("/api/log");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
        assertThat(resp.body())
                .contains("jk engine: listening")
                .contains("line three");
    }

    @Test
    void api_log_respects_the_lines_parameter() throws Exception {
        assertThat(get("/api/log?lines=1").body()).isEqualTo("line three");
        assertThat(get("/api/log?lines=garbage").statusCode()).isEqualTo(200); // default kicks in
    }

    @Test
    void api_log_of_a_missing_file_is_empty_200() throws Exception {
        Files.delete(logFile);
        HttpResponse<String> resp = get("/api/log");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEmpty();
    }

    @Test
    void fs_listing_requires_the_token_even_on_loopback() throws Exception {
        // It lists the filesystem with the engine owner's permissions — never token-exempt.
        assertThat(get("/api/fs").statusCode()).isEqualTo(401);
    }

    @Test
    void fs_lists_subdirectories_and_flags_jk_toml() throws Exception {
        Files.createDirectories(stateDir.resolve("workspace/module-a"));
        Files.createDirectories(stateDir.resolve("workspace/module-b"));
        Files.createDirectories(stateDir.resolve("workspace/.git")); // hidden: skipped
        Files.writeString(stateDir.resolve("workspace/jk.toml"), "[project]");
        Files.writeString(stateDir.resolve("workspace/README.md"), "not a dir");
        HttpResponse<String> resp = get(
                "/api/fs?dir=" + stateDir.resolve("workspace"), "Authorization", "Bearer " + token());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body())
                .contains("\"dirs\":[\"module-a\",\"module-b\"]")
                .contains("\"hasJkToml\":true")
                .contains("\"parent\":\"" + stateDir + "\"");
    }

    @Test
    void fs_rejects_relative_and_unreadable_paths() throws Exception {
        assertThat(get("/api/fs?dir=relative/path", "Authorization", "Bearer " + token()).statusCode())
                .isEqualTo(400);
        assertThat(get("/api/fs?dir=" + stateDir.resolve("no-such-dir"), "Authorization", "Bearer " + token())
                        .statusCode())
                .isEqualTo(400);
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
    void adopts_an_existing_token_file_across_restarts() throws Exception {
        // The token minted by the @BeforeEach engine must survive that engine stopping and a fresh
        // one starting against the same token file — that's what keeps an open dashboard tab valid.
        String original = token();
        assertThat(original).isNotEmpty();
        server.close();

        HttpEngineServer restarted = new HttpEngineServer(
                new JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString()),
                wwwRoot, tokenFile, logFile, "9.9.9-test", () -> SNAPSHOT, events, this::stubTrigger,
                testJournal(), java.util.List::of, () -> EMPTY_CACHE, null);
        try {
            restarted.start();
            assertThat(token()).isEqualTo(original); // file unchanged
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(restarted.url() + "api/fs?dir=" + stateDir))
                            .header("Authorization", "Bearer " + original)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200); // the pre-restart token is still accepted
        } finally {
            restarted.close();
        }
    }

    @Test
    void mints_a_fresh_token_when_the_file_is_blank() throws Exception {
        // A truncated/blank token file (e.g. after `jk engine rotate-token` deleted it, or a
        // half-written file) is not usable — start must mint rather than serve with an empty secret.
        String original = token();
        server.close();
        Files.writeString(tokenFile, "   \n");

        HttpEngineServer restarted = new HttpEngineServer(
                new JkHttpConfig("127.0.0.1", 0, 16, wwwRoot.toString()),
                wwwRoot, tokenFile, logFile, "9.9.9-test", () -> SNAPSHOT, events, this::stubTrigger,
                testJournal(), java.util.List::of, () -> EMPTY_CACHE, null);
        try {
            restarted.start();
            assertThat(token()).isNotEmpty().isNotEqualTo(original);
        } finally {
            restarted.close();
        }
    }

    @Test
    void non_loopback_bind_gates_api_reads_but_not_static() throws Exception {
        JkHttpConfig config = new JkHttpConfig("0.0.0.0", 0, 16, wwwRoot.toString());
        Path lanTokenFile = stateDir.resolve("lan.http-token");
        HttpEngineServer lan = new HttpEngineServer(
                config, wwwRoot, lanTokenFile, stateDir.resolve("lan.log"), "9.9.9-test", () -> SNAPSHOT, new HttpEvents(), this::stubTrigger, testJournal(), java.util.List::of, () -> EMPTY_CACHE, null);
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
                stateDir.resolve("sse.log"),
                "9.9.9-test",
                () -> SNAPSHOT,
                new HttpEvents(),
                this::stubTrigger,
                testJournal(),
                java.util.List::of,
                () -> EMPTY_CACHE,
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
