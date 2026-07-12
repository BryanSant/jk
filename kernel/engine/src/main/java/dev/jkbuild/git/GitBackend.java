// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.model.GitSource;
import java.io.IOException;

/**
 * Uniform git-operation SPI. jk prefers a locally installed {@code git} binary and only falls back
 * to a bundled JGit implementation when none is found — both run in-process in the engine JVM (no
 * forked worker). The two implementations are:
 *
 * <ul>
 *   <li>{@link GitCliBackend} — shells out to the {@code git} command via {@code ProcessBuilder}.
 *   <li>{@link JGitBackend} — pure-Java JGit; always available, the fallback.
 * </ul>
 *
 * <p>{@link GitFetcher} is the public facade: it picks a backend (honouring {@code JK_GIT_BACKEND})
 * and delegates. The record/exception types returned here live on {@link GitFetcher} so callers
 * that predate the SPI are unchanged.
 */
public interface GitBackend {

    /** Enumerate the remote's tags + {@code HEAD} sha via {@code ls-remote} — no clone. */
    GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException;

    /** Resolve the ref to a SHA and materialize a checkout, using (or bypassing) the cache. */
    GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException;

    /** Re-resolve the ref and fail with {@link GitFetcher.TagRewriteException} if the SHA changed. */
    void verifyLocked(GitSource source, String expectedSha) throws IOException;

    /** Resolve the ref to a SHA plus commit time and nearest tag, for git-source versioning. */
    GitFetcher.RefInfo resolveRef(GitSource source) throws IOException;
}
