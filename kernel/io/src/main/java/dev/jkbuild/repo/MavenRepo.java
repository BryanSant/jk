// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Cas;
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
 * A single Maven-style repository. Fetches POMs and artifacts over HTTP, streams them into the
 * content-addressed {@link Cas}, and materialises a human-readable copy — hard-linked from the CAS
 * blob — under {@code repos/<name>/} (via {@link RepoArtifactStore#materialize}). This is the
 * primary, permanent store; nothing outside jk writes there.
 *
 * <p>When {@code mirrorToM2} is enabled (a project opted in via {@code project.m2install}),
 * fetched artifacts are additionally copied — never hard-linked — into {@code ~/.m2/repository}
 * (via {@link M2CompatWriter}), with Maven-compatible {@code .sha1}/{@code .md5} sidecars and a
 * {@code _remote.repositories} hint, so Maven, Gradle, and IDEs can use them without going through
 * jk. This mirror is a one-way, best-effort convenience: jk never reads it back.
 *
 * <p>When {@code --offline} is in effect, fetches are served from {@code repos/<name>/} via the
 * {@link RepoArtifactStore} index; a coordinate that isn't present surfaces as
 * {@link ArtifactNotFoundException} so {@link RepoGroup}'s try-each gives a clean "not found".
 */
public final class MavenRepo {

    private final String name;
    private final URI baseUrl;
    private final RepoTransport transport;
    private final Cas cas;
    private final RepoArtifactStore repoStore; // full store: artifact + .sha256 sidecar under repos/<name>/
    private final RepoCredential credential;
    private final boolean mirrorToM2; // project.m2install: also copy fetched artifacts into ~/.m2

    /** TTL + conditional-GET cache for maven-metadata.xml; null for non-HTTP transports. */
    private final MavenMetadataCache metadataCache;

    public MavenRepo(String name, URI baseUrl, Http http, Cas cas) {
        this(name, baseUrl, http, cas, RepoCredential.ANONYMOUS);
    }

    /**
     * HTTP convenience constructor: selects an {@link HttpTransport} for the URL's scheme via {@link
     * RepoTransports} (which rejects non-http(s)), and authenticates with {@code credential}
     * (anonymous repos pass {@link RepoCredential#ANONYMOUS}). {@code mirrorToM2} defaults to
     * {@code false} — use {@link #MavenRepo(String, URI, Http, Cas, RepoCredential, boolean)} to
     * opt a project's resolve into the {@code ~/.m2} mirror.
     */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential) {
        this(name, baseUrl, http, cas, credential, false);
    }

    /** As above, with an explicit {@code mirrorToM2}. */
    public MavenRepo(String name, URI baseUrl, Http http, Cas cas, RepoCredential credential, boolean mirrorToM2) {
        this(
                name,
                baseUrl,
                RepoTransports.forUrl(baseUrl, Objects.requireNonNull(http, "http")),
                cas,
                credential,
                http,
                mirrorToM2);
    }

    /**
     * General constructor over any {@link RepoTransport} — the entry point for non-HTTP backends
     * (s3://, file://, …) selected by the caller. These don't get the HTTP metadata cache (it has no
     * status/headers to revalidate against). {@code mirrorToM2} defaults to {@code false}.
     */
    public MavenRepo(String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential) {
        this(name, baseUrl, transport, cas, credential, null, false);
    }

    /** As above, with an explicit {@code mirrorToM2}. */
    public MavenRepo(
            String name, URI baseUrl, RepoTransport transport, Cas cas, RepoCredential credential, boolean mirrorToM2) {
        this(name, baseUrl, transport, cas, credential, null, mirrorToM2);
    }

    /**
     * Field-setting constructor. {@code httpOrNull} is the HTTP client when the repo is http(s)
     * (enabling the metadata cache), or {@code null} for a non-HTTP transport. {@code mirrorToM2}
     * is the resolving project's {@code project.m2install} value — {@code false} for resolvers not
     * tied to a specific project's declared dependencies (tool/plugin/script/git resolution).
     */
    private MavenRepo(
            String name,
            URI baseUrl,
            RepoTransport transport,
            Cas cas,
            RepoCredential credential,
            Http httpOrNull,
            boolean mirrorToM2) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.transport = Objects.requireNonNull(transport, "transport");
        this.cas = Objects.requireNonNull(cas, "cas");
        // Full store for every repo: artifact + .sha256 sidecar under repos/<name>/.
        this.repoStore = RepoArtifactStore.forRepoName(cas.root(), name);
        this.credential = Objects.requireNonNull(credential, "credential");
        this.mirrorToM2 = mirrorToM2;
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
        if (dev.jkbuild.config.SessionContext.current().config().offlineOr(false)) {
            return repoStore.versions(coord.group(), coord.artifact());
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
        if (dev.jkbuild.config.SessionContext.current().config().offlineOr(false)) {
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
            // Primary store: materialise a human-readable, hard-linked copy under repos/<name>/.
            repoStore.materialize(relativePath, stored.path(), stored.sha256());
            if (mirrorToM2) {
                // Opt-in mirror: copy (never hard-link — jk doesn't control writes to ~/.m2).
                Path m2Target = M2Dirs.localRepository().resolve(relativePath);
                try {
                    M2CompatWriter.MavenHashes mavenHashes = M2CompatWriter.copyToM2AndHash(stored.path(), m2Target);
                    M2CompatWriter.writeMavenSidecars(m2Target, mavenHashes.sha1(), mavenHashes.md5());
                    M2CompatWriter.writeRemoteRepositories(
                            m2Target.getParent(), name, m2Target.getFileName().toString());
                } catch (IOException ignored) {
                    // Best-effort: the CAS blob and repos/<name>/ copy are already written; a
                    // ~/.m2 mirror failure is non-fatal.
                }
            }
        }
        return new Fetched(uri, stored.path(), stored.sha256(), stored.size());
    }

    /**
     * Serve a fetch from the named repo's full store under {@code repos/<name>/}; a coordinate
     * that was never fetched (or mirrored) here is treated as not-found and the resolver falls
     * through cleanly.
     */
    private Fetched fetchOffline(Coordinate coord, String relativePath) throws IOException {
        Optional<Path> found = repoStore.locate(relativePath);
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
