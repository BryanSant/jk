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
 * A single Maven-style repository. Fetches POMs and artifacts over HTTP, writes them to
 * {@code ~/.m2/repository} (via {@link M2CompatWriter}) and records a {@code .sha256} sidecar in
 * {@code repos/<name>/} (via {@link RepoArtifactStore}). Also writes Maven-compatible {@code .sha1}
 * and {@code .md5} sidecars and a {@code _remote.repositories} hint so Maven, Gradle, and IDEs
 * find the artifacts without a separate download step.
 *
 * <p>The CAS is still written for backward compatibility: old lockfiles whose
 * {@code ClasspathResolver} resolves via {@code sha256/AB/CD/…} paths continue to work until the
 * project is re-locked. CAS blobs for Maven artifacts become unreferenced as lockfiles are
 * regenerated and are collected by {@code jk cache prune --sweep}.
 *
 * <p>When {@code --offline} is in effect, fetches are served from {@code ~/.m2/repository} via the
 * {@link RepoArtifactStore} index; a coordinate that isn't present surfaces as
 * {@link ArtifactNotFoundException} so {@link RepoGroup}'s try-each gives a clean "not found".
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;

    @SuppressWarnings("deprecation")
    private final JkMavenLocalRepo localRepo; // legacy m2 mirror; kept for backward compat GC

    private final RepoArtifactStore repoStore; // index-only: .sha256 sidecars, JARs in ~/.m2
    private final RepoCredential credential;

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final MavenMetadataCache metadataCache;

    /** Without a local repo — offline resolve is unavailable through this repo. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, JkMavenLocalRepo.NONE);
    }

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, JkMavenLocalRepo localRepo) {
        this(name, baseUrl, http, cas, localRepo, RepoCredential.ANONYMOUS);
    }

    /**
     * HTTP convenience constructor: selects an {@link HttpTransport} for the URL's scheme via {@link
     * RepoTransports} (which rejects non-http(s)), and authenticates with {@code credential}
     * (anonymous repos pass {@link RepoCredential#ANONYMOUS}).
     */
    public MavenRepo(
            String name, URI baseUrl, Http http, Cas cas, JkMavenLocalRepo localRepo, RepoCredential credential) {
        this(
                name,
                baseUrl,
                RepoTransports.forUrl(baseUrl, Objects.requireNonNull(http, "http")),
                cas,
                localRepo,
                credential,
                http);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for non-HTTP backends
     * (s3://, file://, …) selected by the caller. These don't get the HTTP metadata cache (it has no
     * status/headers to revalidate against).
     */
    public MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            JkMavenLocalRepo localRepo,
            RepoCredential credential) {
        this(name, baseUrl, transport, cas, localRepo, credential, null);
    }

    /**
     * Field-setting constructor. {@code httpOrNull} is the HTTP client when the repo is http(s)
     * (enabling the metadata cache), or {@code null} for a non-HTTP transport.
     */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            JkMavenLocalRepo localRepo,
            RepoCredential credential,
            Http httpOrNull) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        this.localRepo = Objects.requireNonNull(localRepo, "localRepo");
        // Index-only store for non-local repos: sidecars in repos/<name>/, JARs in ~/.m2.
        this.repoStore = RepoArtifactStore.forRepoName(cas.root(), name);
        this.credential = Objects.requireNonNull(credential, "credential");
        // The metadata cache speaks HTTP directly (conditional GET), so it only
        // applies to http(s) repos — a file:// (or other) baseUrl can be paired
        // with an Http client but must keep enumerating via the transport.
        this.metadataCache = (httpOrNull != null && isHttp(this.baseUrl))
                ? new MavenMetadataCache(httpOrNull, cas.root().resolve("metadata"), MavenMetadataCache.DEFAULT_TTL)
                : null;
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
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
     * Versions of this coordinate's {@code group:artifact} available here. Online: parses {@code
     * maven-metadata.xml}, served through the {@link MavenMetadataCache} (TTL + conditional GET) for
     * HTTP repos so back-to-back resolves don't re-download the index. Offline: lists what the local
     * repo holds. A missing artifact yields an empty list rather than an error so {@link RepoGroup}
     * can union across repos.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        if (ActiveConfig.get().offlineOr(false)) {
            // Prefer the new per-named-repo store; fall back to the legacy mirror.
            List<String> versions = repoStore.versions(coord.group(), coord.artifact());
            return versions.isEmpty() ? localRepo.versions(coord.group(), coord.artifact()) : versions;
        }
        try {
            byte[] xml = metadataCache != null
                    ? metadataCache.fetch(baseUrl.resolve(MavenLayout.metadataPath(coord)), credential)
                    : Files.readAllBytes(fetchMetadata(coord).cachePath());
            return MavenMetadata.parse(xml).versions();
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
        // Stream the body straight into the CAS, hashing as it flows, so a
        // multi-hundred-MB JAR never sits in the heap as a single byte[] —
        // the difference between a cold-cache resolve fitting under the CLI's
        // heap cap and OOMing on it.
        Cas.Stored stored;
        try (var in = transport
                .fetchStream(uri, credential)
                .orElseThrow(() -> new ArtifactNotFoundException("not found in " + name + ": " + uri))) {
            stored = cas.putStream(in);
        }
        if (mirror) {
            // Write to ~/.m2 (primary store) and record the index sidecar in repos/<name>/.
            Path m2Target = M2Dirs.localRepository().resolve(relativePath);
            try {
                M2CompatWriter.MavenHashes mavenHashes = M2CompatWriter.copyToM2AndHash(stored.path(), m2Target);
                M2CompatWriter.writeMavenSidecars(m2Target, mavenHashes.sha1(), mavenHashes.md5());
                M2CompatWriter.writeRemoteRepositories(
                        m2Target.getParent(), name, m2Target.getFileName().toString());
            } catch (IOException ignored) {
                // Best-effort: the CAS blob is already written; a ~/.m2 failure is non-fatal.
            }
            repoStore.recordIndex(relativePath, stored.sha256());
            // Legacy mirror: maintain the hard-link at repo/ so old lockfiles that reference
            // CAS paths continue to work until re-locked.
            localRepo.materialize(relativePath, stored.path());
        }
        return new Fetched(uri, stored.path(), stored.sha256(), stored.size());
    }

    /**
     * Serve a fetch from the local repo. The mirrored file <em>is</em> the artifact (a hard link to
     * the CAS blob), so a present entry is served directly; a coordinate that was never mirrored is
     * treated as not-found and the resolver falls through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String relativePath) throws IOException {
        // Prefer the new named repo store (repos/<name>/…); fall back to the legacy mirror.
        Optional<Path> found = repoStore.locate(relativePath);
        if (found.isEmpty()) found = localRepo.locate(relativePath);
        if (found.isEmpty()) {
            throw new ArtifactNotFoundException(
                    "offline: " + coord + " (" + relativePath + ") not in local index for " + name);
        }
        Path path = found.get();
        // Hash by streaming the file rather than reading it whole — keeps an
        // offline resolve of a large mirrored artifact under the heap cap too.
        return new Fetched(path.toUri(), path, Hashing.sha256Hex(path), Files.size(path));
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
        public ArtifactNotFoundException(String message) {
            super(message);
        }
    }
}
