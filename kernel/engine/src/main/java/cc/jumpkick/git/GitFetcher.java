// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.model.GitSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Git resolver (PRD §11). Public facade over the {@link GitBackend} SPI: git work runs in-process
 * in the engine JVM, preferring a locally installed {@code git} command and falling back to bundled
 * JGit when none is found.
 *
 * <p>Backend selection honours the {@code JK_GIT_BACKEND} environment variable:
 *
 * <ul>
 *   <li>{@code auto} (default) — {@link GitCliExtension} if a usable {@code git} is on {@code PATH},
 *       else {@link JGitExtension}.
 *   <li>{@code cli} — force {@link GitCliExtension}; fail loudly if {@code git} is absent.
 *   <li>{@code jgit} — force {@link JGitExtension}.
 * </ul>
 *
 * <p>Every ref type (tag, branch, rev) is fetched and pinned in {@code jk.lock} like any other
 * coordinate — there is no local freshness-window cache standing in for that pin. An ordinary
 * build never calls {@link #fetch} for a git dep that's already resolved in the lockfile; only
 * {@code jk update --git} / {@code jk fetch} force a fresh resolve.
 *
 * <p>Cache layout (shared by both backends):
 *
 * <pre>
 *   &lt;root&gt;/db/&lt;sha256(canonical-url)&gt;/             # bare clone per URL
 *   &lt;root&gt;/co/&lt;sha256(canonical-url)&gt;/&lt;sha&gt;/   # checkout per resolved SHA
 * </pre>
 */
public final class GitFetcher {

    /** Environment variable that overrides backend selection. */
    public static final String BACKEND_ENV = "JK_GIT_BACKEND";

    private final GitBackend backend;

    public GitFetcher(Path gitRoot) {
        this(gitRoot, new ForgeGitCredentials());
    }

    /** Test seam: inject a custom credentials resolver. */
    public GitFetcher(Path gitRoot, ForgeGitCredentials credentials) {
        Objects.requireNonNull(gitRoot, "gitRoot");
        Objects.requireNonNull(credentials, "credentials");
        this.backend = select(System.getenv(BACKEND_ENV), gitRoot, credentials);
    }

    static GitBackend select(String mode, Path gitRoot, ForgeGitCredentials credentials) {
        String m = (mode == null || mode.isBlank()) ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "jgit" -> new JGitExtension(gitRoot, credentials);
            case "cli" ->
                new GitCliExtension(
                        gitRoot,
                        credentials,
                        GitCliExtension.detect()
                                .orElseThrow(() -> new IllegalStateException(
                                        BACKEND_ENV + "=cli but no usable git command was found on PATH")));
            case "auto" ->
                GitCliExtension.detect()
                        .<GitBackend>map(git -> new GitCliExtension(gitRoot, credentials, git))
                        .orElseGet(() -> new JGitExtension(gitRoot, credentials));
            default ->
                throw new IllegalArgumentException("invalid " + BACKEND_ENV + "=" + mode + " (expected auto|cli|jgit)");
        };
    }

    public record Fetched(String sha, Path checkoutPath) {
        public Fetched {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(checkoutPath, "checkoutPath");
        }
    }

    public record RefInfo(String sha, Instant commitTime, Optional<String> nearestTag) {
        public RefInfo {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(commitTime, "commitTime");
            Objects.requireNonNull(nearestTag, "nearestTag");
        }
    }

    /** A remote's advertised refs: tag names + the {@code HEAD} sha (null when the remote has none). */
    public record RemoteRefs(List<String> tags, String headSha) {
        public RemoteRefs {
            tags = List.copyOf(Objects.requireNonNull(tags, "tags"));
        }
    }

    /** Resolve and checkout, using the cache. */
    public Fetched fetch(GitSource source) throws IOException {
        return fetch(source, false);
    }

    /** Resolve and checkout, optionally bypassing the checkout cache. */
    public Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Objects.requireNonNull(source, "source");
        return backend.fetch(source, noCache);
    }

    /** Tag-rewrite check: re-resolve and fail if the SHA changed. */
    public void verifyLocked(GitSource source, String expectedSha) throws IOException {
        Objects.requireNonNull(source, "source");
        backend.verifyLocked(source, expectedSha);
    }

    /** Resolve ref to SHA + commit metadata for git-source versioning. */
    public RefInfo resolveRef(GitSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        return backend.resolveRef(source);
    }

    /**
     * Enumerate the remote's tags + HEAD via {@code ls-remote} — no clone. Used by {@code jk
     * outdated} to find newer tags for a git dependency without materializing the repo.
     */
    public RemoteRefs listRefs(GitSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        return backend.listRefs(source);
    }

    /**
     * Raised when a git tag's SHA no longer matches the lockfile — indicates the tag was force-pushed
     * (PRD §11.3).
     */
    public static final class TagRewriteException extends IOException {
        private final GitSource source;
        private final String expectedSha;
        private final String actualSha;

        public TagRewriteException(GitSource source, String expectedSha, String actualSha) {
            super("git tag/branch rewrite detected for "
                    + source.canonicalUrl()
                    + " "
                    + source.ref().token()
                    + ": lock says "
                    + expectedSha
                    + ", upstream now resolves to "
                    + actualSha
                    + " (re-run with `jk update` to accept).");
            this.source = source;
            this.expectedSha = expectedSha;
            this.actualSha = actualSha;
        }

        public GitSource source() {
            return source;
        }

        public String expectedSha() {
            return expectedSha;
        }

        public String actualSha() {
            return actualSha;
        }
    }
}
