// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.git;

import build.jumpkick.model.GitSource;
import build.jumpkick.plugin.Extension;
import build.jumpkick.plugin.build.Phase;
import java.io.IOException;
import java.util.Set;

/**
 * Uniform git-operation SPI. jk prefers a locally installed {@code git} binary and only falls back
 * to a bundled JGit implementation when none is found — both run in-process in the engine JVM (no
 * forked worker). The two implementations are:
 *
 * <ul>
 *   <li>{@link GitCliExtension} — shells out to the {@code git} command via {@code ProcessBuilder}.
 *   <li>{@link JGitExtension} — pure-Java JGit; always available, the fallback.
 * </ul>
 *
 * <p>{@link GitFetcher} is the public facade: it picks a backend (honouring {@code JK_GIT_BACKEND})
 * and delegates. The record/exception types returned here live on {@link GitFetcher} so callers
 * that predate the SPI are unchanged.
 */
public interface GitBackend extends Extension {

    /** Git backends participate in the {@link Phase#RESOLVE} phase (fetching git-sourced deps). */
    @Override
    default Set<Phase> phases() {
        return Set.of(Phase.RESOLVE);
    }

    /** Enumerate the remote's tags + {@code HEAD} sha via {@code ls-remote} — no clone. */
    GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException;

    /** Resolve the ref to a SHA and materialize a checkout, using (or bypassing) the cache. */
    GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException;

    /** Re-resolve the ref and fail with {@link GitFetcher.TagRewriteException} if the SHA changed. */
    void verifyLocked(GitSource source, String expectedSha) throws IOException;

    /** Resolve the ref to a SHA plus commit time and nearest tag, for git-source versioning. */
    GitFetcher.RefInfo resolveRef(GitSource source) throws IOException;
}
