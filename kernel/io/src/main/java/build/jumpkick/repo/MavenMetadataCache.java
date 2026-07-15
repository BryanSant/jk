// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

import build.jumpkick.credential.RepoCredential;
import build.jumpkick.http.Http;
import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * On-disk cache for {@code maven-metadata.xml} — the version-enumeration counterpart to the
 * artifact {@link build.jumpkick.cache.Cas}.
 *
 * <p>Metadata is a <em>mutable</em> index (it grows as new versions are published), so unlike
 * artifacts it can't be content-addressed and reused forever. Instead this caches the bytes keyed
 * by URL and freshness-checks them the way the JDK feed client does: served straight from disk
 * within a {@linkplain #DEFAULT_TTL TTL}, and beyond that revalidated with a conditional GET
 * ({@code If-None-Match} on the stored {@code ETag}, {@code If-Modified-Since} on the stored {@code
 * Last-Modified}). A {@code 304 Not Modified} costs only response headers, so repeat resolves no
 * longer re-download the index — and within the TTL they make no request at all, which is what was
 * hammering Maven Central (HTTP 429) across back-to-back builds.
 *
 * <p>No SHA-1/checksum comparison: the conditional GET already signals change, and metadata (unlike
 * artifacts) isn't hash-pinned in {@code jk.lock}. When the server can't be reached or refuses a
 * refresh (network error, 429, …) a previously-cached copy is reused rather than failing the
 * resolve; a genuine 404 surfaces as {@link MavenRepo.ArtifactNotFoundException} so {@link
 * RepoGroup} can fall through to the next repo.
 *
 * <p>Rooted under the active cache dir (passed by {@link MavenRepo} as {@code <cache>/metadata}),
 * so {@code --cache-dir} / per-invocation isolation is preserved.
 */
public final class MavenMetadataCache {

    /** Maven's own default release-metadata update policy is daily; match it. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final Http http;
    private final Path dir;
    private final Duration ttl;

    public MavenMetadataCache(Http http, Path dir, Duration ttl) {
        this.http = Objects.requireNonNull(http, "http");
        this.dir = Objects.requireNonNull(dir, "dir");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    /**
     * The {@code maven-metadata.xml} bytes for {@code uri} — from the on-disk cache within the TTL,
     * otherwise revalidated with a conditional GET. Throws {@link
     * MavenRepo.ArtifactNotFoundException} on a 404 (no such coordinate here).
     */
    public byte[] fetch(URI uri, RepoCredential credential) throws IOException, InterruptedException {
        Path body = dir.resolve(Hashing.sha256Hex(uri.toString()));
        Path meta = body.resolveSibling(body.getFileName() + ".h");

        // --force skips the freshness window: the conditional GET below still makes an
        // unchanged index cheap (304), but a moved `latest` is picked up immediately.
        boolean force = build.jumpkick.config.SessionContext.current().config().forceOr(false);
        if (!force && fresh(body)) {
            return Files.readAllBytes(body);
        }
        Map<String, String> headers = new LinkedHashMap<>(AuthHeaders.of(credential));
        addValidators(meta, headers);
        try {
            HttpResponse<byte[]> resp = http.get(uri, headers);
            int status = resp.statusCode();
            if (status == 304 && Files.isRegularFile(body)) {
                touch(body); // revalidated: restart the TTL
                return Files.readAllBytes(body);
            }
            if (status == 200) {
                store(body, meta, resp);
                return resp.body();
            }
            if (status == 404) {
                throw new MavenRepo.ArtifactNotFoundException("not found: " + uri);
            }
            // 4xx (incl. 429) / other: reuse a stale copy rather than fail the
            // resolve when the server won't hand us a fresh index right now.
            if (Files.isRegularFile(body)) {
                return Files.readAllBytes(body);
            }
            throw new IOException("HTTP " + status + " fetching " + uri);
        } catch (MavenRepo.ArtifactNotFoundException notFound) {
            throw notFound; // a real miss, not a transport hiccup
        } catch (IOException networkError) {
            if (Files.isRegularFile(body)) {
                return Files.readAllBytes(body); // offline / unreachable: stale-but-usable
            }
            throw networkError;
        }
    }

    private boolean fresh(Path body) throws IOException {
        if (ttl.isZero() || ttl.isNegative()) return false;
        if (!Files.isRegularFile(body) || Files.size(body) == 0) return false;
        Instant mtime = Files.getLastModifiedTime(body).toInstant();
        return Duration.between(mtime, Instant.now()).compareTo(ttl) < 0;
    }

    /** Echo back the validators stored alongside a cached body (ETag, then Last-Modified). */
    private static void addValidators(Path meta, Map<String, String> headers) throws IOException {
        if (!Files.isRegularFile(meta)) return;
        List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
        String etag = lines.isEmpty() ? "" : lines.get(0);
        String lastModified = lines.size() > 1 ? lines.get(1) : "";
        if (!etag.isBlank()) headers.put("If-None-Match", etag);
        if (!lastModified.isBlank()) headers.put("If-Modified-Since", lastModified);
    }

    private void store(Path body, Path meta, HttpResponse<byte[]> resp) throws IOException {
        Files.createDirectories(dir);
        writeAtomic(body, resp.body());
        String etag = resp.headers().firstValue("ETag").orElse("");
        String lastModified = resp.headers().firstValue("Last-Modified").orElse("");
        writeAtomic(meta, (etag + "\n" + lastModified).getBytes(StandardCharsets.UTF_8));
    }

    private void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = Files.createTempFile(dir, ".meta-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private static void touch(Path body) throws IOException {
        Files.setLastModifiedTime(body, FileTime.from(Instant.now()));
    }
}
