// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Cas;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The single owner of the lockfile {@code "<name>+<url>"} source-string format and of "find (or
 * materialise) an artifact on disk by its Maven-layout path". Consolidates parsing + locate logic
 * that was hand-copied across {@code CacheSync}, {@code ClasspathResolver}, and the CLI's IDE export.
 */
public final class RepoArtifactResolver {

    private RepoArtifactResolver() {}

    /**
     * The {@code <name>} before the {@code '+'} in a lockfile source ({@code "central+https://…"}),
     * or {@code null} when the source is absent/malformed.
     */
    public static String repoName(String source) {
        if (source == null) return null;
        int plus = source.indexOf('+');
        if (plus <= 0 || plus >= source.length() - 1) return null;
        return source.substring(0, plus);
    }

    /**
     * True for a <em>named remote</em> repo — one whose sidecar index in {@code repos/<name>/} points
     * at a real {@code ~/.m2} artifact. The {@code local} full store and {@code git:} sources are not
     * named remotes (they resolve through the local store / the CAS instead).
     */
    public static boolean isNamedRemote(String repoName) {
        return repoName != null
                && !repoName.isEmpty()
                && !repoName.equals("local")
                && !repoName.startsWith("git:");
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
            Optional<Path> found = RepoArtifactStore.forRepoName(cas.root(), repoName).locate(relativePath);
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
