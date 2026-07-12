// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.jkbuild.config.JkHttpConfig;
import dev.jkbuild.engine.EngineTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The embedded HTTP server (JDK {@code jdk.httpserver}, zero third-party dependencies), wrapping
 * bind/serve/stop plus the request gates every handler sits behind: Host-header validation
 * (DNS-rebinding defense), the admission semaphore ({@code max-concurrent-requests}), and the
 * bearer-token tiers on {@code /api} (see {@link #authorized}). Static content is {@link
 * StaticContent}; REST endpoints dispatch through {@link ApiRouter}. See {@code docs/http.md}.
 *
 * <p>Owned by {@code EngineServer}: constructed and started only when the {@code [http]} table is
 * present, closed during engine cleanup. {@link #start()} throwing is not fatal to the engine —
 * the caller logs it and continues without HTTP.
 */
public final class HttpEngineServer implements AutoCloseable {

    /** Grace period {@link HttpServer#stop} gives in-flight exchanges before hard-closing. */
    private static final int STOP_GRACE_SECONDS = 1;

    /** How often a quiet SSE stream writes a comment line — dead-client detection + proxy keepalive. */
    private static final long DEFAULT_HEARTBEAT_MILLIS = 15_000;

    /** Cap on a request body ({@code POST /api/build} carries one flat object; 64 KiB is generous). */
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final JkHttpConfig config;
    private final StaticContent staticContent;
    private final Semaphore admission;
    private final Path wwwRoot;
    private final Path tokenFile;
    private final Path logFile;
    private final Supplier<StatusSnapshot> status;
    private final HttpEvents events;
    private final BuildTrigger buildTrigger;
    private final dev.jkbuild.engine.journal.BuildJournal journal;
    private final ApiRouter api = new ApiRouter();
    private final Consumer<String> log;

    private HttpServer server;
    private ExecutorService executor;
    private byte[] token;
    private long heartbeatMillis = DEFAULT_HEARTBEAT_MILLIS;

    /** {@code true} when bound beyond loopback — then even {@code /api} reads require the token. */
    private boolean readsRequireToken;

    /**
     * @param wwwRoot the resolved on-disk static root (the caller resolves {@code www-root} against
     *     the live {@code JkDirs}; tests pass a temp dir) — need not exist
     * @param tokenFile where to persist the minted bearer token (owner-only permissions) so the CLI
     *     can hand the user a tokenized URL — {@code EnginePaths.Paths#httpToken()} in real use
     * @param logFile the engine's own log ({@code EnginePaths.Paths#log()}), tailed by {@code
     *     GET /api/log} for the dashboard's Status view
     * @param version the engine version, used for classpath-asset {@code ETag}s
     * @param status supplies the vitals {@code GET /api/status} reports, fresh per request
     * @param events the hub {@code GET /api/events} streams from ({@code EngineServer} publishes)
     * @param buildTrigger runs {@code POST /api/build}'s build engine-side
     */
    public HttpEngineServer(
            JkHttpConfig config,
            Path wwwRoot,
            Path tokenFile,
            Path logFile,
            String version,
            Supplier<StatusSnapshot> status,
            HttpEvents events,
            BuildTrigger buildTrigger,
            dev.jkbuild.engine.journal.BuildJournal journal,
            Consumer<String> log) {
        this.config = config;
        this.staticContent = new StaticContent(wwwRoot, version);
        this.admission = new Semaphore(config.effectiveMaxConcurrentRequests());
        this.wwwRoot = wwwRoot;
        this.tokenFile = tokenFile;
        this.logFile = logFile;
        this.status = status;
        this.events = events;
        this.buildTrigger = buildTrigger;
        this.journal = journal;
        this.log = log != null ? log : s -> {};
        api.register("GET", "/api/status", this::handleStatus);
        api.register("GET", "/api/events", this::handleEvents);
        api.register("GET", "/api/log", this::handleLog);
        api.register("GET", "/api/fs", this::handleFs);
        api.register("POST", "/api/build", this::handleBuild);
        api.register("GET", "/api/history", this::handleHistory);
        api.register("GET", "/api/history/artifact", this::handleHistoryArtifact);
        api.register("DELETE", "/api/history", this::handleHistoryDelete);
    }

    /**
     * Bind and start serving. Throws on an unusable {@code host} or an already-claimed port — the
     * caller treats that as "continue without HTTP", never as engine failure.
     */
    public void start() throws IOException {
        loadOrMintToken();
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getByName(config.host()), config.port());
        readsRequireToken = !bind.getAddress().isLoopbackAddress();
        server = HttpServer.create(bind, 0);
        server.createContext("/", this::handle);
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jk-http-", 0).factory());
        server.setExecutor(executor);
        server.start();
    }

    /**
     * The bearer token is an opaque random capability — no JWT, nothing to decode ({@code
     * docs/http.md}): 24 random bytes, held in memory for constant-time comparison, and persisted
     * owner-only so the CLI (running as the same user) can print a tokenized URL.
     *
     * <p><b>Persisted, not rotated per start.</b> The token file is keyed by state dir, so it is
     * stable across engine restarts. Adopting an existing token — rather than minting a fresh one
     * every start — is what keeps an open dashboard tab valid across the restarts jk does on its own
     * (a version-skew respawn after a {@code jk} upgrade, a crash, {@code SIGTERM}): the tab's stored
     * token still matches. Per-start rotation was never a real defense here — the {@code 0600} file
     * <i>is</i> the secret, and anyone who can read it already owns the account — so it only cost us
     * every open tab. Rotation is now an explicit action ({@code jk engine rotate-token}), not an
     * accident of restart. Mint (and persist owner-only) only when no usable token file exists.
     */
    private void loadOrMintToken() throws IOException {
        String existing = readPersistedToken();
        if (existing != null) {
            token = existing.getBytes(StandardCharsets.UTF_8);
            return;
        }
        String minted = EngineTransport.newToken();
        token = minted.getBytes(StandardCharsets.UTF_8);
        Files.deleteIfExists(tokenFile);
        try {
            Files.createFile(
                    tokenFile,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tokenFile); // non-POSIX filesystem (Windows): default ACLs are per-user
        }
        Files.writeString(tokenFile, minted);
    }

    /** The persisted token if the file exists and holds a non-blank value, else {@code null}. */
    private String readPersistedToken() {
        try {
            if (!Files.isRegularFile(tokenFile)) return null;
            String value = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            return null; // unreadable — mint a fresh one rather than fail to serve
        }
    }

    /** The served base URL, e.g. {@code http://127.0.0.1:8910/} — actual bound port, so 0 works. */
    public String url() {
        InetSocketAddress addr = server.getAddress();
        String host = addr.getAddress().isAnyLocalAddress()
                ? "127.0.0.1" // a wildcard bind is reachable via loopback; advertise the always-valid form
                : addr.getAddress().getHostAddress();
        if (host.contains(":")) host = "[" + host + "]"; // IPv6 literal
        return "http://" + host + ":" + addr.getPort() + "/";
    }

    @Override
    public void close() {
        if (server != null) server.stop(STOP_GRACE_SECONDS);
        // shutdownNow, not shutdown: an SSE handler quietly parked in Subscription.next() holds no
        // connection anymore after stop() — the interrupt is what tells it to unsubscribe and die.
        if (executor != null) executor.shutdownNow();
    }

    /** Every request funnels through here: gates first, then dispatch. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!HostCheck.allowed(exchange.getRequestHeaders().getFirst("Host"), server.getAddress().getPort())) {
                sendText(exchange, 421, "unrecognized Host header\n");
                return;
            }
            if (!admission.tryAcquire()) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                sendText(exchange, 503, "engine busy\n");
                return;
            }
            try {
                dispatch(exchange);
            } finally {
                admission.release();
            }
        } catch (RuntimeException e) {
            // A handler bug must not kill the virtual thread silently mid-response; best-effort 500.
            log.accept("jk engine: http handler error: " + e);
            try {
                sendText(exchange, 500, "internal error\n");
            } catch (Exception ignored) {
                // response already started (IllegalStateException) or client gone — nothing more to do
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api") || path.startsWith("/api/")) {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendText(exchange, 401, "missing or invalid bearer token\n");
                return;
            }
            api.handle(exchange);
            return;
        }
        staticContent.serve(exchange); // static is never token-gated — the dashboard shell has no secrets
    }

    /**
     * The token tiers ({@code docs/http.md}): mutations require the bearer token always — even on
     * loopback, that's the CSRF defense (a hostile page can fire a no-preflight POST at
     * 127.0.0.1 but can't attach an {@code Authorization} header cross-origin) — and {@code /api}
     * reads require it too when bound beyond loopback. One carve-out: {@code GET /api/events} also
     * accepts {@code ?access_token=…}, because {@code EventSource} cannot send headers.
     */
    private boolean authorized(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        boolean read = method.equals("GET") || method.equals("HEAD");
        // /api/fs lists the filesystem with the engine owner's permissions — on a shared machine
        // another local user must not browse it over loopback, so it is never token-exempt.
        boolean sensitiveRead = exchange.getRequestURI().getPath().equals("/api/fs");
        if (read && !readsRequireToken && !sensitiveRead) return true;
        if (tokenValid(bearerToken(exchange.getRequestHeaders().getFirst("Authorization")))) return true;
        return read
                && exchange.getRequestURI().getPath().equals("/api/events")
                && tokenValid(queryParam(exchange.getRequestURI().getQuery(), "access_token"));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return authorization.substring("Bearer ".length()).trim();
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    private boolean tokenValid(String presented) {
        if (presented == null || presented.isEmpty()) return false;
        // Constant-time, immune to length/prefix probing.
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        StatusSnapshot s = status.get();
        String body = JsonOut.object()
                .put("version", s.version())
                .put("pid", s.pid())
                .put("startedAt", s.startedAtMillis())
                .put("uptimeSeconds", Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000))
                .put("idleMinutes", s.idleMinutes())
                .put("neverIdles", true) // [http] enabled forces never-self-terminate (docs/http.md)
                .put("activeRequests", s.activeRequests())
                .put("activePipelines", s.activePipelines())
                .put("heapUsedBytes", s.heapUsedBytes())
                .put("heapCommittedBytes", s.heapCommittedBytes())
                .put("heapMaxBytes", s.heapMaxBytes())
                .put("rssBytes", s.rssBytes())
                .put("httpUrl", url())
                .put("maxConcurrentRequests", config.effectiveMaxConcurrentRequests())
                .put("wwwRoot", wwwRoot.toString())
                .toString();
        sendJson(exchange, 200, body);
    }

    /**
     * The tail of the engine's own log for the Status view — plain text, newest lines last.
     * Read-tier auth like every {@code /api} GET; IO-shaped (a bounded read of the file's tail).
     */
    private void handleLog(HttpExchange exchange) throws IOException {
        int requested = 120;
        String param = queryParam(exchange.getRequestURI().getQuery(), "lines");
        if (param != null) {
            try {
                requested = Math.max(1, Math.min(400, Integer.parseInt(param)));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        String tail;
        try {
            tail = tailOf(logFile, requested);
        } catch (IOException e) {
            tail = "";
        }
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] bytes = tail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Last {@code lines} lines of {@code file}, reading at most the final 256 KiB of it. */
    private static String tailOf(Path file, int lines) throws IOException {
        if (!Files.isRegularFile(file)) return "";
        long size = Files.size(file);
        long from = Math.max(0, size - 256 * 1024);
        var buf = java.nio.ByteBuffer.allocate((int) (size - from));
        try (var channel = java.nio.channels.FileChannel.open(file)) {
            channel.position(from);
            while (buf.hasRemaining() && channel.read(buf) >= 0) {}
        }
        byte[] bytes = buf.array();
        String[] all = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        int end = all.length > 0 && all[all.length - 1].isEmpty() ? all.length - 1 : all.length;
        int start = Math.max(0, end - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, start, end));
    }

    /**
     * The SSE stream ({@code docs/http.md}): one {@code id:}/{@code event:}/{@code data:} frame per
     * engine event, a comment heartbeat when quiet. Holds its admission slot for the stream's whole
     * life (deliberate — see the failure-modes table). The write of a frame to a dead client is what
     * detects the disconnect; {@link #close()}'s interrupt is what ends an idle stream at shutdown.
     */
    private void handleEvents(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, 0);
        var out = exchange.getResponseBody();
        try (HttpEvents.Subscription subscription = events.subscribe()) {
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String frame = subscription.next(heartbeatMillis);
                out.write((frame != null ? frame : ": heartbeat\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // server shutting down
        } catch (IOException e) {
            // The client closed the tab — routine stream end, not an error.
        }
    }

    /** Directory listings above this are truncated — a picker, not a filesystem dump. */
    private static final int MAX_FS_ENTRIES = 400;

    /**
     * {@code GET /api/fs?dir=…} — the workspace picker behind the dashboard's Browse button:
     * subdirectory names of an absolute path (default: the user's home), whether it holds a
     * {@code jk.toml}, and its parent for the up-navigation. Token-required even on loopback —
     * see {@link #authorized}.
     */
    private void handleFs(HttpExchange exchange) throws IOException {
        String requested = queryParam(exchange.getRequestURI().getQuery(), "dir");
        Path dir = requested == null || requested.isBlank()
                ? Path.of(System.getProperty("user.home"))
                : Path.of(requested);
        if (!dir.isAbsolute()) {
            sendJson(exchange, 400, JsonOut.object().put("error", "dir must be an absolute path").toString());
            return;
        }
        dir = dir.normalize();
        java.util.List<String> subdirs = new java.util.ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".") && Files.isDirectory(entry)) subdirs.add(name);
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) {
            sendJson(exchange, 400, JsonOut.object().put("error", "not a readable directory: " + dir).toString());
            return;
        }
        subdirs.sort(String.CASE_INSENSITIVE_ORDER);
        boolean truncated = subdirs.size() > MAX_FS_ENTRIES;
        if (truncated) subdirs = subdirs.subList(0, MAX_FS_ENTRIES);
        Path parent = dir.getParent();
        sendJson(
                exchange,
                200,
                JsonOut.object()
                        .put("dir", dir.toString())
                        .put("parent", parent != null ? parent.toString() : null)
                        .put("hasJkToml", Files.isRegularFile(dir.resolve("jk.toml")))
                        .put("truncated", truncated)
                        .putStrings("dirs", subdirs)
                        .toString());
    }

    /** {@code POST /api/build} — acknowledge with a request id; progress streams on {@code /api/events}. */
    private void handleBuild(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        String dir = dev.jkbuild.plugin.protocol.Ndjson.str(body, "dir");
        if (dir == null || dir.isBlank()) {
            sendJson(exchange, 400, JsonOut.object().put("error", "missing \"dir\"").toString());
            return;
        }
        long requestId;
        try {
            requestId = buildTrigger.trigger(dir);
        } catch (IllegalStateException e) {
            // Engine is draining (graceful shutdown in progress) — refuse new builds.
            exchange.getResponseHeaders().set("Retry-After", "1");
            sendJson(exchange, 503, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        sendJson(
                exchange,
                202,
                JsonOut.object().put("requestId", requestId).put("events", "/api/events").toString());
    }

    /** Cap on the {@code GET /api/history} list — a picker of recent runs, not a full dump. */
    private static final int HISTORY_LIST_LIMIT = 200;

    /**
     * {@code GET /api/history} — the persisted build journal (survives engine restarts). With no
     * {@code ?id=}, a JSON array of the newest entries' full records; with {@code ?id=}, that one
     * entry's {@code record.json}. Each stored record is already valid JSON, so it streams verbatim
     * (no re-serialization, and {@link JsonOut}'s flat-only shape never has to express the nested
     * arrays). Read-tier auth, like every other GET.
     */
    private void handleHistory(HttpExchange exchange) throws IOException {
        String id = decode(queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id != null && !id.isBlank()) {
            var record = journal.recordFile(id);
            if (record.isEmpty()) {
                sendJson(exchange, 404, JsonOut.object().put("error", "no such build: " + id).toString());
                return;
            }
            sendJson(exchange, 200, Files.readString(record.get(), StandardCharsets.UTF_8));
            return;
        }
        sendJson(exchange, 200, "[" + String.join(",", journal.rawRecords(HISTORY_LIST_LIMIT)) + "]");
    }

    /**
     * {@code GET /api/history/artifact?id=…&name=…} — a snapshot file (test-results markdown, the
     * {@code jk.lock} snapshot, or the diagnostics text) served as plain text. {@code name} is
     * whitelisted by the journal, so a hostile value cannot escape the entry directory.
     */
    private void handleHistoryArtifact(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        var artifact = journal.artifact(decode(queryParam(query, "id")), decode(queryParam(query, "name")));
        if (artifact.isEmpty()) {
            sendJson(exchange, 404, JsonOut.object().put("error", "no such artifact").toString());
            return;
        }
        sendText(exchange, 200, Files.readString(artifact.get(), StandardCharsets.UTF_8));
    }

    /**
     * {@code DELETE /api/history?id=…} — remove one entry, like deleting a CI run. DELETE is a
     * mutation, so {@link #authorized} requires the bearer token even on loopback (CSRF defense).
     */
    private void handleHistoryDelete(HttpExchange exchange) throws IOException {
        String id = decode(queryParam(exchange.getRequestURI().getQuery(), "id"));
        if (id == null || !journal.delete(id)) {
            sendJson(exchange, 404, JsonOut.object().put("error", "no such build").toString());
            return;
        }
        sendJson(exchange, 200, JsonOut.object().put("deleted", true).toString());
    }

    private static String decode(String raw) {
        return raw == null ? null : java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    /** Test seam: shrink the SSE heartbeat so quiet-stream behavior is testable in milliseconds. */
    void heartbeatMillis(long millis) {
        this.heartbeatMillis = millis;
    }

    static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Test seam: the admission gate, so a saturated-server {@code 503} is deterministically testable. */
    Semaphore admission() {
        return admission;
    }
}
