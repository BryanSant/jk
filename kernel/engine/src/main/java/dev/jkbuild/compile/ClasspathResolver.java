// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Scope;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a {@link Lockfile}'s checksummed packages to on-disk artifact paths in the {@link Cas},
 * filtered by scope. Packages without a checksum (POM-only / path / git) are skipped — they don't
 * contribute to the compile classpath.
 *
 * <p>This is a pure name-resolution step: it doesn't fetch anything. {@code jk sync} ensures the
 * CAS is populated.
 */
public final class ClasspathResolver {

    /** Scopes used to build the runtime / test runtime classpath. */
    public static final Set<Scope> TEST = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.TEST);

    /** Scopes bundled into a runnable app (shadow jar / installed libexec). */
    public static final Set<Scope> RUNTIME = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    /** Scopes visible while compiling main sources. */
    public static final Set<Scope> COMPILE_MAIN = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED);

    /** Scopes visible while compiling test sources. */
    public static final Set<Scope> COMPILE_TEST = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.TEST);

    private final Cas cas;

    public ClasspathResolver(Cas cas) {
        this.cas = Objects.requireNonNull(cas, "cas");
    }

    /** Backwards-compat overload: returns every checksummed package. */
    public List<Path> classpathFor(Lockfile lock) {
        return classpathFor(lock, EnumSet.allOf(Scope.class));
    }

    /** Filtered: only packages tagged with one of {@code scopes}. */
    public List<Path> classpathFor(Lockfile lock, Set<Scope> scopes) {
        List<Path> result = new ArrayList<>(lock.artifacts().size());
        dev.jkbuild.task.AccessLedger ledger = dev.jkbuild.task.AccessLedger.atDefaultPath();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (!pkg.inAnyScope(scopes)) continue;
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            // Prefer the human-readable repos/<name>/<m2-path>.jar path; fall back to the CAS
            // hash path for artifacts fetched before the named-repo store was introduced.
            Path repoPath = resolveFromRepos(pkg);
            result.add(repoPath != null ? repoPath : cas.pathFor(hex));
            ledger.touch(hex);
        }
        return result;
    }

    /**
     * Resolve the artifact path from the named-repo store ({@code repos/<name>/<m2-path>.jar}).
     * Returns {@code null} when the artifact is absent from the store, the source is not a Maven
     * repo (git, path, local), or the source field is missing — callers fall back to the CAS path.
     */
    private Path resolveFromRepos(Lockfile.Artifact pkg) {
        String repoName = dev.jkbuild.repo.RepoArtifactResolver.repoName(pkg.source());
        // Only a named remote repo has a sidecar index pointing at ~/.m2; skip local/git/missing.
        if (!dev.jkbuild.repo.RepoArtifactResolver.isNamedRemote(repoName)) return null;
        if (pkg.name().indexOf(':') < 0) return null;
        dev.jkbuild.model.Coordinate coord = pkg.coordinate();
        String m2Path = dev.jkbuild.repo.MavenLayout.artifactPath(coord);
        // forRepoName() returns an index-only store for non-local repos so locate() gives the
        // ~/.m2 artifact path (human-readable) rather than a sha256/AB/CD/… CAS path.
        dev.jkbuild.repo.RepoArtifactStore store = dev.jkbuild.repo.RepoArtifactStore.forRepoName(cas.root(), repoName);
        return store.locate(m2Path).orElse(null);
    }
}
