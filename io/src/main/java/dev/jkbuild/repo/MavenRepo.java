// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A single Maven-style repository. Fetches POMs and artifacts over HTTP,
 * deposits them into a {@link Cas}, and returns {@link Fetched} records
 * describing both the on-disk path and the SHA-256 of the bytes — the
 * resolver needs both to populate {@code jk.lock}.
 *
 * <p>Every successful fetch is also recorded in the {@link Journal} so the
 * blob is later addressable by coordinate. When {@code --offline} is in
 * effect, fetches are served from the journal + CAS instead of the network;
 * a coordinate that isn't journaled (or whose blob has been GC'd) surfaces as
 * {@link ArtifactNotFoundException}, so {@link RepoGroup}'s try-each and the
 * resolver get a clean "not found" rather than a network error.
 *
 * <p>HTTPS only by default per PRD §10.5; the constructor will reject
 * {@code http://} URIs unless explicitly marked insecure (deferred).
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final Http http;
    private final Cas cas;
    private final Journal journal;

    /** Without a journal — offline resolve is unavailable through this repo. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, Journal.NONE);
    }

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, Journal journal) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.cas = Objects.requireNonNull(cas, "cas");
        this.journal = Objects.requireNonNull(journal, "journal");
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
        return fetch(coord, "pom", MavenLayout.pomPath(coord), true);
    }

    public Fetched fetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, artifactKind(coord), MavenLayout.artifactPath(coord), true);
    }

    public Fetched fetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        // maven-metadata.xml has no version key and is stale offline, so it's
        // never journaled; offline version enumeration uses availableVersions().
        return fetch(coord, "metadata", MavenLayout.metadataPath(coord), false);
    }

    /**
     * Versions of this coordinate's {@code group:artifact} available here.
     * Online: parses {@code maven-metadata.xml}. Offline: lists what the
     * journal holds locally. A missing artifact yields an empty list rather
     * than an error so {@link RepoGroup} can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (ActiveConfig.get().offlineOr(false)) {
            return journal.versions(coord.group(), coord.artifact());
        }
        try {
            Fetched f = fetchMetadata(coord);
            return MavenMetadata.parse(Files.readAllBytes(f.cachePath())).versions();
        } catch (ArtifactNotFoundException notFound) {
            return List.of();
        }
    }

    private Fetched fetch(Coordinate coord, String kind, String relativePath, boolean journalable)
            throws IOException, InterruptedException {
        if (ActiveConfig.get().offlineOr(false)) {
            return fetchOffline(coord, kind);
        }
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
        String sha = Hashing.sha256Hex(body);
        if (journalable) {
            journal.record(coord, kind, sha, body.length, name, uri.toString());
        }
        return new Fetched(uri, path, sha, body.length);
    }

    /**
     * Serve a fetch from the local journal + CAS. The journal pointer is only
     * trusted when its blob is actually present in the CAS — a pointer to a
     * GC'd blob is treated as not-found, so the resolver falls through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String kind) throws ArtifactNotFoundException {
        Optional<Journal.Blob> found = journal.lookup(coord, kind);
        if (found.isEmpty() || !cas.contains(found.get().sha256())) {
            throw new ArtifactNotFoundException(
                    "offline: " + coord + " (" + kind + ") not in local cache for " + name);
        }
        Journal.Blob blob = found.get();
        Path path = cas.pathFor(blob.sha256());
        URI uri = blob.url() != null ? URI.create(blob.url()) : path.toUri();
        return new Fetched(uri, path, blob.sha256(), blob.size());
    }

    /** Journal key for a primary/secondary artifact: {@code <type>[:<classifier>]}. */
    private static String artifactKind(Coordinate coord) {
        return coord.classifier() == null ? coord.type() : coord.type() + ":" + coord.classifier();
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
