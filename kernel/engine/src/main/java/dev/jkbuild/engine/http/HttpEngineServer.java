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
    private final Path tokenFile;
    private final Supplier<StatusSnapshot> status;
    private final HttpEvents events;
    private final BuildTrigger buildTrigger;
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
     * @param version the engine version, used for classpath-asset {@code ETag}s
     * @param status supplies the vitals {@code GET /api/status} reports, fresh per request
     * @param events the hub {@code GET /api/events} streams from ({@code EngineServer} publishes)
     * @param buildTrigger runs {@code POST /api/build}'s build engine-side
     */
    public HttpEngineServer(
            JkHttpConfig config,
            Path wwwRoot,
            Path tokenFile,
            String version,
            Supplier<StatusSnapshot> status,
            HttpEvents events,
            BuildTrigger buildTrigger,
            Consumer<String> log) {
        this.config = config;
        this.staticContent = new StaticContent(wwwRoot, version);
        this.admission = new Semaphore(config.effectiveMaxConcurrentRequests());
        this.tokenFile = tokenFile;
        this.status = status;
        this.events = events;
        this.buildTrigger = buildTrigger;
        this.log = log != null ? log : s -> {};
        api.register("GET", "/api/status", this::handleStatus);
        api.register("GET", "/api/events", this::handleEvents);
        api.register("POST", "/api/build", this::handleBuild);
    }

    /**
     * Bind and start serving. Throws on an unusable {@code host} or an already-claimed port — the
     * caller treats that as "continue without HTTP", never as engine failure.
     */
    public void start() throws IOException {
        mintToken();
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
     * docs/http.md}): minted per engine start exactly like the Windows transport's connection
     * secret, held in memory for constant-time comparison, and persisted owner-only so the CLI
     * (running as the same user) can print a tokenized URL.
     */
    private void mintToken() throws IOException {
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
        if (read && !readsRequireToken) return true;
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
                .put("heapUsedBytes", s.heapUsedBytes())
                .put("heapCommittedBytes", s.heapCommittedBytes())
                .put("heapMaxBytes", s.heapMaxBytes())
                .put("rssBytes", s.rssBytes())
                .put("httpUrl", url())
                .toString();
        sendJson(exchange, 200, body);
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
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, JsonOut.object().put("error", e.getMessage()).toString());
            return;
        }
        sendJson(
                exchange,
                202,
                JsonOut.object().put("requestId", requestId).put("events", "/api/events").toString());
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
