// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.lock.RepoSource;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single owner of the lockfile {@code "<name>+<url>"} source-string format and of "find (or
 * materialise) an artifact on disk by its Maven-layout path". Consolidates parsing + locate logic
 * that was hand-copied across {@code CacheSync}, {@code ClasspathResolver}, and the CLI's IDE export.
 */
public final class RepoArtifactResolver {

    /**
     * Repo-source-name prefix for a synthetic git-materialized repo ({@code git:<coord>:<version>}).
     * This is a <em>repo source name</em> concept — distinct from {@code Dependency.GIT_PREFIX}, which
     * is a module-coordinate placeholder. Written by the git-source materializer when it registers the
     * repo, read back here to exclude such repos from the named-remote fast path.
     */
    public static final String GIT_SOURCE_PREFIX = "git:";

    private RepoArtifactResolver() {}

    /**
     * The {@code <name>} before the {@code '+'} in a lockfile source ({@code "central+https://…"}),
     * or {@code null} when the source is absent/malformed. Delegates to the shared {@link RepoSource}
     * parser (in {@code :core}), which owns the {@code <name>+<url>} split.
     */
    public static String repoName(String source) {
        return RepoSource.parse(source).name();
    }

    /**
     * True for a <em>named remote</em> repo — one whose full store under {@code repos/<name>/} holds
     * a real jar fetched from that repository. The {@code local} full store and {@code git:} sources
     * are not named remotes (they resolve through the local store / the CAS instead).
     */
    public static boolean isNamedRemote(String repoName) {
        return repoName != null
                && !repoName.isEmpty()
                && !repoName.equals("local")
                && !repoName.startsWith(GIT_SOURCE_PREFIX);
    }

    /**
     * Resolve an artifact to a real {@code .jar} path (proper Maven-layout filename, not a CAS hash
     * path): the package's named-repo index first (when {@code source} is a named remote), else the
     * local full store; materialising a local-store copy from the CAS blob {@code hex} when only the
     * CAS holds it. Returns {@code null} when unresolved.
     */
    public static Path locateOrMaterialize(Cas cas, String source, String relativePath, String hex) {
        String repoName = repoName(source);
        if (isNamedRemote(repoName)) {
            // With a pinned hash, resolve hash-verified so a rewritten/poisoned ~/.m2 copy
            // falls through to the local store / CAS instead of being served as-is.
            RepoArtifactStore store = RepoArtifactStore.forRepoName(cas.root(), repoName);
            Optional<Path> found = hex != null ? store.locate(relativePath, hex) : store.locate(relativePath);
            if (found.isPresent()) return found.get();
        }
        RepoArtifactStore local = RepoArtifactStore.forRepoName(cas.root(), "local");
        Optional<Path> found = local.locate(relativePath);
        if (found.isPresent()) return found.get();
        if (hex != null && cas.contains(hex)) {
            local.materialize(relativePath, cas.pathFor(hex), hex);
            found = local.locate(relativePath);
        }
        return found.orElse(null);
    }
}
