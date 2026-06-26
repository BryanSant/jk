// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link RepoTransport} over HTTP(S), wrapping the shared {@link Http} client (so it inherits the
 * retry / offline / backoff policy). Renders the {@link RepoCredential} to an {@code Authorization}
 * header via {@link AuthHeaders}. Covers Maven Central, Nexus, Artifactory, WebDAV, and the forge
 * package registries.
 */
public final class HttpTransport implements RepoTransport {

    private final Http http;

    public HttpTransport(Http http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public Optional<byte[]> fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.get(uri, AuthHeaders.of(credential));
        int status = response.statusCode();
        if (status == 404) return Optional.empty();
        if (status >= 400) {
            throw new IOException("HTTP " + status + " fetching " + uri);
        }
        return Optional.of(response.body());
    }

    @Override
    public Optional<InputStream> fetchStream(URI uri, RepoCredential credential)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.getStream(uri, AuthHeaders.of(credential));
        int status = response.statusCode();
        if (status == 404) {
            drain(response.body());
            return Optional.empty();
        }
        if (status >= 400) {
            drain(response.body());
            throw new IOException("HTTP " + status + " fetching " + uri);
        }
        return Optional.of(response.body());
    }

    /** Consume and discard a non-success body so the connection can be reused. */
    private static void drain(InputStream body) throws IOException {
        try (body) {
            body.transferTo(OutputStream.nullOutputStream());
        }
    }

    @Override
    public int put(URI uri, byte[] body, String contentType, RepoCredential credential)
            throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>(AuthHeaders.of(credential));
        if (contentType != null && !contentType.isBlank()) {
            headers.put("Content-Type", contentType);
        }
        return http.put(uri, body, headers).statusCode();
    }
}
