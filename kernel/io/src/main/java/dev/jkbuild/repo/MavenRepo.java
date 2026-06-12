// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.net.URI;
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
 * <p>Every successful fetch is also mirrored into the {@link JkMavenLocalRepo}
 * (an m2 hard-link back to the CAS blob) so the artifact is later addressable
 * by coordinate. When {@code --offline} is in effect, fetches are served from
 * that local repo instead of the network; a coordinate that isn't mirrored
 * surfaces as {@link ArtifactNotFoundException}, so {@link RepoGroup}'s
 * try-each and the resolver get a clean "not found" rather than a network
 * error.
 *
 * <p>HTTPS only by default per PRD §10.5; the constructor will reject
 * {@code http://} URIs unless explicitly marked insecure (deferred).
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;
    private final JkMavenLocalRepo localRepo;
    private final RepoCredential credential;

    /** Without a local repo — offline resolve is unavailable through this repo. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, JkMavenLocalRepo.NONE);
    }

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, JkMavenLocalRepo localRepo) {
        this(name, baseUrl, http, cas, localRepo, RepoCredential.ANONYMOUS);
    }

    /**
     * HTTP convenience constructor: selects an {@link HttpTransport} for the
     * URL's scheme via {@link RepoTransports} (which rejects non-http(s)), and
     * authenticates with {@code credential} (anonymous repos pass
     * {@link RepoCredential#ANONYMOUS}).
     */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, JkMavenLocalRepo localRepo,
                     RepoCredential credential) {
        this(name, baseUrl, RepoTransports.forUrl(baseUrl, Objects.requireNonNull(http, "http")),
                cas, localRepo, credential);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for
     * non-HTTP backends (s3://, file://, …) selected by the caller.
     */
    public MavenRepo(String name, URI baseUrl, RepoTransport transport, Cas cas,
                     JkMavenLocalRepo localRepo, RepoCredential credential) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        this.localRepo = Objects.requireNonNull(localRepo, "localRepo");
        this.credential = Objects.requireNonNull(credential, "credential");
    }

    public String name() {
        return name;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    public Fetched fetchPom(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.pomPath(coord), true);
    }

    public Fetched fetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return fetch(coord, MavenLayout.artifactPath(coord), true);
    }

    public Fetched fetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        // maven-metadata.xml has no version key and is stale offline, so it's
        // never mirrored; offline version enumeration uses availableVersions().
        return fetch(coord, MavenLayout.metadataPath(coord), false);
    }

    /**
     * Versions of this coordinate's {@code group:artifact} available here.
     * Online: parses {@code maven-metadata.xml}. Offline: lists what the local
     * repo holds. A missing artifact yields an empty list rather than an error
     * so {@link RepoGroup} can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (ActiveConfig.get().offlineOr(false)) {
            return localRepo.versions(coord.group(), coord.artifact());
        }
        try {
            Fetched f = fetchMetadata(coord);
            return MavenMetadata.parse(Files.readAllBytes(f.cachePath())).versions();
        } catch (ArtifactNotFoundException notFound) {
            return List.of();
        }
    }

    private Fetched fetch(Coordinate coord, String relativePath, boolean mirror)
            throws IOException, InterruptedException {
        if (ActiveConfig.get().offlineOr(false)) {
            return fetchOffline(coord, relativePath);
        }
        URI uri = baseUrl.resolve(relativePath);
        Optional<byte[]> fetched = transport.fetch(uri, credential);
        if (fetched.isEmpty()) {
            throw new ArtifactNotFoundException("not found in " + name + ": " + uri);
        }
        byte[] body = fetched.get();
        Path path = cas.put(body);
        String sha = Hashing.sha256Hex(body);
        if (mirror) {
            localRepo.materialize(relativePath, path);
        }
        return new Fetched(uri, path, sha, body.length);
    }

    /**
     * Serve a fetch from the local repo. The mirrored file <em>is</em> the
     * artifact (a hard link to the CAS blob), so a present entry is served
     * directly; a coordinate that was never mirrored is treated as not-found
     * and the resolver falls through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String relativePath) throws IOException {
        Optional<Path> found = localRepo.locate(relativePath);
        if (found.isEmpty()) {
            throw new ArtifactNotFoundException(
                    "offline: " + coord + " (" + relativePath + ") not in local repo for " + name);
        }
        Path path = found.get();
        byte[] body = Files.readAllBytes(path);
        return new Fetched(path.toUri(), path, Hashing.sha256Hex(body), body.length);
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
