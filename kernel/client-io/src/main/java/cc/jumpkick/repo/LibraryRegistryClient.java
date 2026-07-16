// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.http.Http;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Conditional-GET fetch of the jk library registry (the source {@code jk library update} pulls and
 * {@code jk lock} revalidates). Shared by both callers so there's one place that knows how to
 * validate a cached copy against the upstream {@code ETag}.
 *
 * <p>Read-only with respect to the cache: {@link #fetch} only reads the {@code etagFile} sidecar to
 * build the {@code If-None-Match} header. Writing the fetched body (and the sidecar) back to disk is
 * the caller's job, done only after the caller has validated the payload — {@code jk library update}
 * refuses to replace a good cache with a malformed one, so persistence can't happen inside a shared
 * fetch-only helper.
 */
public final class LibraryRegistryClient {

    public static final URI DEFAULT_SOURCE = URI.create(
            "https://raw.githubusercontent.com/jkbuild/jk-library-registry/refs/heads/main/libraries.toml");

    private final Http http;

    public LibraryRegistryClient(Http http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /** Outcome of a {@link #fetch}. */
    public sealed interface Result {
        /** The upstream {@code ETag} matched (304) — the caller's cache is already current. */
        record Unchanged() implements Result {}

        /** A 200 response with a fresh body and (if the server sent one) its {@code ETag}. */
        record Updated(byte[] body, String etag) implements Result {}
    }

    /**
     * GET {@code source}, sending {@code If-None-Match} when {@code etagFile} holds a prior ETag.
     * Throws on any network error or non-{200,304} status — callers decide whether that's fatal
     * ({@code jk library update}) or something to swallow and fall back from ({@code jk lock}).
     */
    public Result fetch(URI source, Path etagFile) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        String etag = readEtag(etagFile);
        if (etag != null) headers.put("If-None-Match", etag);

        HttpResponse<byte[]> response = http.get(source, headers);
        int status = response.statusCode();
        if (status == 304) return new Result.Unchanged();
        if (status != 200) {
            throw new IOException("library registry " + source + " returned HTTP " + status);
        }
        String newEtag = response.headers().firstValue("ETag").orElse(null);
        return new Result.Updated(response.body(), newEtag);
    }

    private static String readEtag(Path etagFile) throws IOException {
        if (!Files.isRegularFile(etagFile)) return null;
        String value = Files.readString(etagFile, StandardCharsets.UTF_8).strip();
        return value.isBlank() ? null : value;
    }
}
