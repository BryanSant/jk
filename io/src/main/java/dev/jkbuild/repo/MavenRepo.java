// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A single Maven-style repository. Fetches POMs and artifacts over HTTP,
 * deposits them into a {@link Cas}, and returns {@link Fetched} records
 * describing both the on-disk path and the SHA-256 of the bytes — the
 * resolver needs both to populate {@code jk.lock}.
 *
 * <p>HTTPS only by default per PRD §10.5; the constructor will reject
 * {@code http://} URIs unless explicitly marked insecure (deferred).
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final Http http;
    private final Cas cas;

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.cas = Objects.requireNonNull(cas, "cas");
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                && !"http".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException("Maven repo must be http(s): " + baseUrl);
        }
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Fetched fetchPom(Coordinate coord) throws IOException, InterruptedException {
        return fetch(MavenLayout.pomPath(coord));
    }

    public Fetched fetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return fetch(MavenLayout.artifactPath(coord));
    }

    public Fetched fetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        return fetch(MavenLayout.metadataPath(coord));
    }

    private Fetched fetch(String relativePath) throws IOException, InterruptedException {
        URI uri = baseUrl.resolve(relativePath);
        HttpResponse<byte[]> response = http.get(uri);
        int status = response.statusCode();
        if (status == 404) {
            throw new ArtifactNotFoundException("404 from " + name + ": " + uri);
        }
        if (status >= 400) {
            throw new IOException("HTTP " + status + " from " + name + ": " + uri);
        }
        byte[] body = response.body();
        Path path = cas.put(body);
        return new Fetched(uri, path, Hashing.sha256Hex(body), body.length);
    }

    private static URI normalize(URI uri) {
        String s = uri.toString();
        if (!s.endsWith("/")) {
            return URI.create(s + "/");
        }
        return uri;
    }

    public record Fetched(URI url, Path cachePath, String sha256, long size) {}

    /** Thrown when the requested artifact returns 404 from this repo. */
    public static final class ArtifactNotFoundException extends IOException {
        public ArtifactNotFoundException(String message) { super(message); }
    }
}
