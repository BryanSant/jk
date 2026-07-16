// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * Static file serving from two sources, checked in order (see {@code docs/http.md}):
 *
 * <ol>
 *   <li><strong>{@code www-root} on disk</strong> — user assets and server-rendered/appended
 *       content (a build may be appending to a {@code .json} here right now). Served {@code
 *       Cache-Control: no-cache} with {@code Last-Modified} revalidation, and a GET snapshots the
 *       file's size at open and serves exactly that many bytes — a concurrent append can never
 *       corrupt response framing.
 *   <li><strong>Classpath {@code /www}</strong> — the dashboard SPA shipped inside the engine jar.
 *       Immutable per engine version: {@code ETag} derived from the version, {@code max-age=3600}.
 * </ol>
 *
 * <p>Disk wins so users can override shipped assets (front-end iteration without an engine
 * rebuild) and generated data is always current. Both sources sit behind the same traversal gate;
 * there are no directory listings, ever.
 */
final class StaticContent {

    private static final String CLASSPATH_PREFIX = "/www/";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneOffset.UTC);

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("woff", "font/woff"),
            Map.entry("wasm", "application/wasm"));

    private final Path root;
    private final String classpathEtag;

    /**
     * Snapshot builds revalidate classpath assets on every load: the version-derived ETag never
     * moves between {@code -SNAPSHOT} jars, so an hour of {@code max-age} would keep serving the
     * previous jar's dashboard from the browser cache after an upgrade. Releases bump the
     * version, so they keep real caching.
     */
    private final boolean snapshotVersion;

    /** @param root the resolved {@code www-root} — need not exist (classpath still serves) */
    StaticContent(Path root, String version) {
        this.root = root.normalize();
        this.classpathEtag = "\"jk-" + version + "\"";
        this.snapshotVersion = version.endsWith("-SNAPSHOT");
    }

    void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        boolean head = method.equals("HEAD");
        if (!head && !method.equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            HttpEngineServer.sendText(exchange, 405, "method not allowed\n");
            return;
        }
        String rel = relativize(exchange.getRequestURI().getPath());
        if (rel == null) {
            HttpEngineServer.sendText(exchange, 404, "not found\n");
            return;
        }
        if (serveFromDisk(exchange, rel, head)) return;
        if (serveFromClasspath(exchange, rel, head)) return;
        HttpEngineServer.sendText(exchange, 404, "not found\n");
    }

    /**
     * Map a request path (already percent-decoded by {@link java.net.URI#getPath}) to a safe
     * root-relative file path, or {@code null} for anything that smells like traversal. Percent
     * decoding happens before this sees the path, so {@code %2e%2e} arrives as literal {@code ..}
     * and is caught by the segment check like any other spelling.
     */
    private static String relativize(String requestPath) {
        if (requestPath == null || requestPath.indexOf('\0') >= 0 || requestPath.indexOf('\\') >= 0) return null;
        String path = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (path.isEmpty() || path.endsWith("/")) path = path + "index.html";
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
        }
        return path;
    }

    private boolean serveFromDisk(HttpExchange exchange, String rel, boolean head) throws IOException {
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return false;

        Instant lastModified;
        try {
            lastModified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (IOException e) {
            return false; // deleted between the check and now — fall through to classpath/404
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Last-Modified", HTTP_DATE.format(lastModified));
        if (notModifiedSince(exchange.getRequestHeaders().getFirst("If-Modified-Since"), lastModified)) {
            exchange.sendResponseHeaders(304, -1);
            return true;
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(rel));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size(); // the snapshot: a concurrent append only ever grows the file
            if (head) {
                // The JDK's own FileServerHandler convention: an explicit Content-Length plus a
                // -1 response length is the sanctioned "headers only, no body" shape for HEAD.
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, -1);
            } else if (size == 0) {
                exchange.sendResponseHeaders(200, -1); // a 0 length arg would mean chunked, not empty
            } else {
                exchange.sendResponseHeaders(200, size);
                copyExactly(channel, exchange.getResponseBody(), size);
            }
        }
        return true;
    }

    private boolean serveFromClasspath(HttpExchange exchange, String rel, boolean head) throws IOException {
        URL resource = StaticContent.class.getResource(CLASSPATH_PREFIX + rel);
        if (resource == null) return false;
        // On an exploded classpath (dev/test) a directory resolves to a file: URL — never serve
        // those; in a jar, directory entries don't resolve to streams of content the same way, and
        // the regular-file check below covers the exploded case.
        if ("file".equals(resource.getProtocol())) {
            try {
                if (!Files.isRegularFile(Path.of(resource.toURI()))) return false;
            } catch (URISyntaxException e) {
                return false;
            }
        }
        // The shipped SPA's only external resources: Vue + ECharts from unpkg (version-pinned + SRI
        // in index.html) and the JetBrains Mono + Material Icons webfonts from Google Fonts (CSS on
        // fonts.googleapis.com, font files on fonts.gstatic.com) — see docs/webclient.md.
        // 'unsafe-eval' is Vue's runtime template compiler. Disk content (user reports, possibly
        // with inline styles/scripts of their own) is deliberately not CSP-gated.
        exchange.getResponseHeaders()
                .set(
                        "Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-eval' https://unpkg.com; "
                                + "style-src 'self' https://fonts.googleapis.com; "
                                + "font-src https://fonts.gstatic.com");
        if (snapshotVersion) {
            exchange.getResponseHeaders().set("Cache-Control", "no-cache"); // see snapshotVersion javadoc
        } else {
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            exchange.getResponseHeaders().set("ETag", classpathEtag);
            if (classpathEtag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return true;
            }
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(rel));
        if (head) {
            exchange.sendResponseHeaders(200, -1); // length unknowable without buffering the asset
            return true;
        }
        try (InputStream in = resource.openStream()) {
            exchange.sendResponseHeaders(200, 0); // chunked: length unknowable without buffering the asset
            in.transferTo(exchange.getResponseBody());
        }
        return true;
    }

    private static boolean notModifiedSince(String ifModifiedSince, Instant lastModified) {
        if (ifModifiedSince == null) return false;
        try {
            Instant since = Instant.from(HTTP_DATE.parse(ifModifiedSince.trim()));
            return !lastModified.isAfter(since);
        } catch (RuntimeException e) {
            return false; // unparseable header → serve the full response
        }
    }

    private static void copyExactly(FileChannel channel, OutputStream out, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long remaining = size;
        var target = Channels.newChannel(out);
        while (remaining > 0) {
            buffer.clear();
            if (remaining < buffer.capacity()) buffer.limit((int) remaining);
            int read = channel.read(buffer);
            // A shrunk file (this dir only ever expects appends) leaves the response short of its
            // Content-Length; the server then closes the connection rather than hanging the client.
            if (read < 0) break;
            buffer.flip();
            while (buffer.hasRemaining()) target.write(buffer);
            remaining -= read;
        }
    }

    private static String contentType(String rel) {
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_TYPES.getOrDefault(ext, "application/octet-stream");
    }
}
