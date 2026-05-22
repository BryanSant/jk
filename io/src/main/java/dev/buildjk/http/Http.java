// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();

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

    private static long jittered(Duration base) {
        long ms = base.toMillis();
        // +/- 10% jitter
        return ms + (long) ((Math.random() - 0.5) * 0.2 * ms);
    }
}
