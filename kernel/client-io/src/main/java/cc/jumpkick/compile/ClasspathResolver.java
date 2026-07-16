// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.nio.file.Files;
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
    public static final Set<Scope> TEST =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.TEST, Scope.TEST_DEV);

    /** Scopes bundled into a runnable app (shadow jar / installed ~/.jk/lib). */
    public static final Set<Scope> RUNTIME = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME);

    /**
     * The {@code jk run}/{@code jk dev} exec classpath: production runtime plus the dev-loop
     * scopes ({@code [dev-dependencies]}, {@code [test-dev-dependencies]}) — DevTools, Docker
     * Compose support, and friends ride local runs but never artifacts ({@link #RUNTIME} is what
     * packagers consume).
     */
    public static final Set<Scope> RUN =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.DEV, Scope.TEST_DEV);

    /** Scopes visible while compiling main sources. */
    public static final Set<Scope> COMPILE_MAIN = EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED);

    /** Scopes visible while compiling test sources. */
    public static final Set<Scope> COMPILE_TEST =
            EnumSet.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.TEST, Scope.TEST_DEV);

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
        for (Entry entry : entriesFor(lock, scopes)) {
            if (entry.jar() != null) result.add(entry.jar());
        }
        return result;
    }

    /**
     * A resolved classpath element with the lockfile artifact it came from. {@code container} is
     * the exploded archive dir for artifacts whose packaging is a container (an AAR: res/,
     * AndroidManifest.xml, R.txt live there; {@code jar} is its {@code classes.jar}) — null for
     * plain jars. An AAR with no classes.jar yields a null {@code jar} (resources-only library).
     */
    public record Entry(Lockfile.Artifact artifact, Path jar, Path container) {

        /** Back-compat: a plain-jar entry. */
        public Entry(Lockfile.Artifact artifact, Path jar) {
            this(artifact, jar, null);
        }
    }

    /**
     * As {@link #classpathFor(Lockfile, Set)}, but keeping each path paired with its lockfile
     * artifact — packagers that need original coordinates (Boot's {@code BOOT-INF/lib} uses
     * {@code artifact-version.jar} names, never CAS hashes) read these.
     */
    public List<Entry> entriesFor(Lockfile lock, Set<Scope> scopes) {
        List<Entry> result = new ArrayList<>(lock.artifacts().size());
        cc.jumpkick.task.AccessLedger ledger = cc.jumpkick.task.AccessLedger.atDefaultPath();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (!pkg.inAnyScope(scopes)) continue;
            String checksum = pkg.checksum();
            if (checksum == null) continue;
            String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
            // Prefer the human-readable repos/<name>/<m2-path>.jar path; fall back to the CAS
            // hash path for artifacts fetched before the named-repo store was introduced, or in
            // the rare case repos/<name>/ itself no longer matches the locked hash — the CAS blob
            // is the only path guaranteed to hold the pinned bytes.
            if (pkg.isAar()) {
                // Container packaging: the classpath entry is the exploded AAR's classes.jar;
                // the container dir itself rides along for resource/manifest consumers.
                try {
                    Path container = cc.jumpkick.cache.ExplodedArchives.explode(cas, hex);
                    Path classesJar = container.resolve("classes.jar");
                    result.add(new Entry(pkg, Files.isRegularFile(classesJar) ? classesJar : null, container));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(
                            pkg.name() + " v" + pkg.version() + ": " + e.getMessage(), e);
                }
                ledger.touch(hex);
                continue;
            }
            Path repoPath = resolveFromRepos(pkg, hex);
            result.add(new Entry(pkg, repoPath != null ? repoPath : cas.pathFor(hex)));
            ledger.touch(hex);
        }
        return result;
    }

    /**
     * Resolve the artifact path from the named-repo store ({@code repos/<name>/<m2-path>.jar}),
     * verified against the locked hash {@code hex}. Returns {@code null} when the artifact is
     * absent from the store, its content no longer matches the lock (corruption/tampering —
     * jk's own store should never legitimately drift), the source is not a Maven repo (git, path,
     * local), or the source field is missing — callers fall back to the CAS path.
     */
    private Path resolveFromRepos(Lockfile.Artifact pkg, String hex) {
        String repoName = cc.jumpkick.repo.RepoArtifactResolver.repoName(pkg.source());
        // Only a named remote repo has a full store under repos/<name>/; skip local/git/missing.
        if (!cc.jumpkick.repo.RepoArtifactResolver.isNamedRemote(repoName)) return null;
        if (pkg.name().indexOf(':') < 0) return null;
        cc.jumpkick.model.Coordinate coord = pkg.coordinate();
        String m2Path = cc.jumpkick.repo.MavenLayout.artifactPath(coord);
        // locate() gives the repos/<name>/<m2-path> artifact path (human-readable) rather than a
        // sha256/AB/CD/… CAS path.
        cc.jumpkick.repo.RepoArtifactStore store = cc.jumpkick.repo.RepoArtifactStore.forRepoName(cas.root(), repoName);
        return store.locate(m2Path, hex).orElse(null);
    }
}
