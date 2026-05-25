// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper over {@link HttpClient} that adds the retry/backoff schedule
 * the PRD §8.3 calls for: exponential backoff with jitter on 5xx and on
 * network {@link IOException}s; never retries on 4xx; max 5 attempts.
 *
 * <p>ETag / If-Modified-Since / range requests / negative caching arrive
 * once the resolver actually wants them.
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
        this(HttpClient.newBuilder()
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
     * GET with extra request headers — used for conditional GET
     * ({@code If-Modified-Since}, {@code If-None-Match}). 304 responses
     * are returned to the caller as-is.
     */
    public HttpResponse<byte[]> get(URI uri, Map<String, String> headers)
            throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(60));
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
            throw new IOException("GET " + uri + " failed after " + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException("GET " + uri + " returned " + lastStatus
                + " after " + (backoffs.length + 1) + " attempts");
    }

    /**
     * Streaming GET — returns the response with the body as an
     * {@link InputStream} so the caller can pump bytes through a hash /
     * progress / file sink without buffering the whole payload in memory.
     * Same retry policy as {@link #get(URI)} for connect failures and
     * 5xx; mid-stream failures propagate to the caller (no resume).
     *
     * <p>The per-request timeout is generous (15 min) because JDK archives
     * commonly run 100–250 MB and the standard {@code .get()} 60s ceiling
     * would cut them off on slow links.
     */
    public HttpResponse<InputStream> getStream(URI uri) throws IOException, InterruptedException {
        checkOffline(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofMinutes(15))
                .build();
        IOException lastIo = null;
        int lastStatus = -1;
        for (int attempt = 0; attempt < backoffs.length + 1; attempt++) {
            if (attempt > 0) {
                Thread.sleep(jittered(backoffs[attempt - 1]));
            }
            try {
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
            throw new IOException("GET " + uri + " failed after "
                    + (backoffs.length + 1) + " attempts", lastIo);
        }
        throw new IOException("GET " + uri + " returned " + lastStatus
                + " after " + (backoffs.length + 1) + " attempts");
    }

    private static long jittered(Duration base) {
        long ms = base.toMillis();
        // +/- 10% jitter
        return ms + (long) ((Math.random() - 0.5) * 0.2 * ms);
    }

    /**
     * Fail fast when the user passed {@code --offline} (or set
     * {@code JK_OFFLINE} / {@code config.offline = true}). Any outbound HTTP
     * is short-circuited with a clear error before the request is even
     * constructed; callers that need to fall back to cached data should
     * either avoid calling Http entirely in offline mode or catch this and
     * substitute a cache lookup.
     */
    private static void checkOffline(URI uri) throws OfflineException {
        if (dev.jkbuild.config.ActiveConfig.get().offlineOr(false)) {
            throw new OfflineException(uri);
        }
    }
}
