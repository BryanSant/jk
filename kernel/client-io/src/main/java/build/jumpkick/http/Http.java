// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Thin wrapper over {@link HttpClient} that adds the retry/backoff schedule the PRD §8.3 calls for:
 * exponential backoff with jitter on 5xx and on network {@link IOException}s; never retries on 4xx;
 * max 5 attempts.
 *
 * <p>ETag / If-Modified-Since / range requests / negative caching arrive once the resolver actually
 * wants them.
 */
public final class Http {

    private static final Duration[] BACKOFFS = {
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofMillis(400),
        Duration.ofMillis(800),
        Duration.ofMillis(1600),
    };

    private final HttpClient client;
    private final Duration[] backoffs;

    public Http() {
        this(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_2)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build(),
                BACKOFFS);
    }

    /** Visible for tests — lets the caller shrink the backoff schedule. */
    Http(HttpClient client, Duration[] backoffs) {
        this.client = client;
        this.backoffs = backoffs;
    }

    public HttpResponse<byte[]> get(URI uri) throws IOException, InterruptedException {
        return get(uri, Map.of());
    }

    /**
     * GET with extra request headers — used for conditional GET ({@code If-Modified-Since}, {@code
     * If-None-Match}). 304 responses are returned to the caller as-is.
     */
    public HttpResponse<byte[]> get(URI uri, Map<String, String> headers) throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(60));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        // Opt into transport-level gzip unless the caller already set
        // their own Accept-Encoding (e.g. testing without compression).
        // The response body is transparently decompressed by
        // `gzipAwareByteArray()` when the server replies with
        // Content-Encoding: gzip.
        if (!hasHeaderIgnoreCase(headers, "Accept-Encoding")) {
            builder.header("Accept-Encoding", "gzip");
        }
        HttpRequest request = builder.build();

        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            try {
                HttpResponse<byte[]> response = client.send(request, gzipAwareByteArray());
                int status = response.statusCode();
                if (status < 500) {
                    return response;
                }
                lastStatus = status;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new IOException("GET " + uri + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException(
                "GET " + uri + " returned " + lastStatus + " after " + (backoffs.length + 1) + " attempts");
    }

    /**
     * Streaming GET — returns the response with the body as an {@link InputStream} so the caller can
     * pump bytes through a hash / progress / file sink without buffering the whole payload in memory.
     * Same retry policy as {@link #get(URI)} for connect failures and 5xx; mid-stream failures
     * propagate to the caller (no resume).
     *
     * <p>The per-request timeout is generous (15 min) because JDK archives commonly run 100–250 MB
     * and the standard {@code .get()} 60s ceiling would cut them off on slow links.
     */
    public HttpResponse<InputStream> getStream(URI uri) throws IOException, InterruptedException {
        return getStream(uri, Map.of());
    }

    /**
     * Streaming GET with extra request headers (e.g. {@code Authorization} for an authenticated
     * repository). Otherwise identical to {@link #getStream(URI)}: same generous timeout and retry
     * policy, body delivered as an {@link InputStream} the caller pumps to a sink.
     */
    public HttpResponse<InputStream> getStream(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofMinutes(15));
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        if (!hasHeaderIgnoreCase(headers, "Accept-Encoding")) {
            builder.header("Accept-Encoding", "gzip");
        }
        HttpRequest request = builder.build();
        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            try {
                HttpResponse<InputStream> response = client.send(request, gzipAwareInputStream());
                int status = response.statusCode();
                if (status < 500) {
                    return response;
                }
                // Drain the body before retrying so the connection can be reused.
                try (var body = response.body()) {
                    body.transferTo(java.io.OutputStream.nullOutputStream());
                }
                lastStatus = status;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new IOException("GET " + uri + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException(
                "GET " + uri + " returned " + lastStatus + " after " + (backoffs.length + 1) + " attempts");
    }

    /**
     * POST an {@code application/x-www-form-urlencoded} body and ask for a JSON reply. Same
     * retry/offline policy as {@link #get(URI)}: retries on connect failures and 5xx, never on 4xx,
     * max 5 attempts.
     *
     * <p>Responses below 500 are returned to the caller as-is — including 4xx. The OAuth device flow
     * (docs/gh-integration.md) relies on this: GitHub signals {@code authorization_pending} / {@code
     * slow_down} with a non-2xx status plus a JSON {@code error} field, so the poll loop needs the
     * body, not an exception. The form values ride the wire URL-encoded; callers must use https URIs
     * for anything sensitive.
     */
    public HttpResponse<byte[]> postForm(URI uri, Map<String, String> form) throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .build();

        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            try {
                HttpResponse<byte[]> response = client.send(request, gzipAwareByteArray());
                int status = response.statusCode();
                if (status < 500) {
                    return response;
                }
                lastStatus = status;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new IOException("POST " + uri + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException(
                "POST " + uri + " returned " + lastStatus + " after " + (backoffs.length + 1) + " attempts");
    }

    private static String urlEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * PUT a byte body with caller-supplied headers (e.g. {@code Authorization}, {@code Content-Type})
     * — the upload primitive for publishing to Maven repositories and object stores
     * (docs/artifact-repos.md). Same offline guard and retry policy as {@link #get(URI)}: PUT is
     * idempotent, so retrying on connect failures and 5xx is safe; 4xx is returned to the caller
     * as-is (auth failures, 409 conflicts, etc.).
     */
    public HttpResponse<byte[]> put(URI uri, byte[] body, Map<String, String> headers)
            throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofMinutes(15)); // large artifacts on slow links
        for (Map.Entry<String, String> e : headers.entrySet()) {
            builder.header(e.getKey(), e.getValue());
        }
        HttpRequest request = builder.build();

        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status < 500) {
                    return response;
                }
                lastStatus = status;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        if (lastIo != null) {
            throw new IOException("PUT " + uri + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException(
                "PUT " + uri + " returned " + lastStatus + " after " + (backoffs.length + 1) + " attempts");
    }

    private static long jittered(Duration base) {
        long ms = base.toMillis();
        // +/- 10% jitter
        return ms + (long) ((Math.random() - 0.5) * 0.2 * ms);
    }

    /**
     * Body handler that returns the response as a {@code byte[]} and transparently inflates the
     * payload when the server set {@code Content-Encoding: gzip}. Java's {@link HttpClient} never
     * decompresses on its own (unlike curl), so without this every gzip-aware caller would have to
     * wrap manually.
     *
     * <p>Callers should treat the returned bytes as the canonical body. The response object retains
     * the original {@code Content-Encoding} and {@code Content-Length} headers — those describe the
     * wire, not the decoded payload, so reading them is now a footgun. In practice jk callers don't,
     * so the tradeoff is acceptable.
     */
    static BodyHandler<byte[]> gzipAwareByteArray() {
        return responseInfo -> {
            var upstream = HttpResponse.BodyHandlers.ofByteArray().apply(responseInfo);
            if (!isGzipped(responseInfo)) return upstream;
            return BodySubscribers.mapping(upstream, raw -> {
                try (var in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        };
    }

    /**
     * Streaming counterpart of {@link #gzipAwareByteArray()}. Wraps the incoming {@link InputStream}
     * in a {@link GZIPInputStream} when the response is gzip-encoded; the caller's {@code
     * try-with-resources} closes the gzip stream, which in turn releases the underlying connection.
     */
    static BodyHandler<InputStream> gzipAwareInputStream() {
        return responseInfo -> {
            var upstream = HttpResponse.BodyHandlers.ofInputStream().apply(responseInfo);
            if (!isGzipped(responseInfo)) return upstream;
            return BodySubscribers.mapping(upstream, in -> {
                try {
                    return new GZIPInputStream(in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        };
    }

    private static boolean isGzipped(HttpResponse.ResponseInfo info) {
        return info.headers()
                .firstValue("Content-Encoding")
                .map(v -> v.trim().equalsIgnoreCase("gzip"))
                .orElse(false);
    }

    private static boolean hasHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (String k : headers.keySet()) {
            if (k.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /**
     * Fail fast when the user passed {@code --offline} (or set {@code JK_OFFLINE} / {@code
     * config.offline = true}). Any outbound HTTP is short-circuited with a clear error before the
     * request is even constructed; callers that need to fall back to cached data should either avoid
     * calling Http entirely in offline mode or catch this and substitute a cache lookup.
     */
    private static void checkOffline(URI uri) throws OfflineException {
        if (build.jumpkick.config.SessionContext.current().config().offlineOr(false)) {
            throw new OfflineException(uri);
        }
    }
}
