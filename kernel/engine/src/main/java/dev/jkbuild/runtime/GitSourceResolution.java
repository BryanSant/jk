// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.JkMavenLocalRepo;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-solve bridge between git-source dependencies and the Maven resolver
 * (docs/git-source-deps.md). For each {@code git = "..."} dependency it:
 *
 * <ol>
 *   <li>materializes the repo into a per-commit {@code file://} Maven repo
 *       ({@link GitSourceMaterializer}),</li>
 *   <li>augments the {@link RepoGroup} with that {@code file://} repo, and</li>
 *   <li>rewrites the dependency into an <em>exact coordinate pin</em>
 *       ({@code group:artifact = "=version"}) the PubGrub solver resolves like
 *       any other coordinate.</li>
 * </ol>
 *
 * <p>The solver therefore never has to know about git. After the lock, the
 * resolved package gets its git provenance stamped back on via {@link #stamp}.
 *
 * <p>A project with no git dependencies passes through untouched — same project,
 * same repos, empty provenance map — so the common path is a zero-cost no-op.
 */
public final class GitSourceResolution {

    private GitSourceResolution() {}

    /**
     * The result of preparing a build for resolution: the dependency-rewritten
     * project, the repo group augmented with each git artifact's {@code file://}
     * repo, and a map from {@code group:artifact@version} to the git provenance
     * to stamp onto the matching lockfile package.
     */
    public record Prepared(JkBuild project, RepoGroup repos, Map<String, Lockfile.Artifact.GitInfo> gitInfoByKey) {}

    /**
     * Materialize every git dependency in {@code effective}, augment
     * {@code baseRepos}, and rewrite git deps to coordinate pins, accepting any
     * upstream ref movement. Used on first-run resolve and {@code jk update}.
     */
    public static Prepared prepare(JkBuild effective, RepoGroup baseRepos, Cas cas, Path javaHome, String jkVersion)
            throws IOException, InterruptedException {
        return prepare(effective, baseRepos, cas, javaHome, jkVersion, Map.of());
    }

    /**
     * As {@link #prepare(JkBuild, RepoGroup, Cas, Path, String)}, but first
     * verifies each immutable (tag/rev) git ref still resolves to its
     * lockfile-recorded SHA — failing with a {@code TagRewriteException} if an
     * upstream tag was force-moved since the lock (docs/git-source-deps.md
     * §"Supply-chain safety"). {@code lockedShas} maps {@code url|ref-token} to
     * the locked SHA (see {@link #lockedImmutableShas}); an empty map disables
     * the check (the {@code jk update} / first-run behavior).
     *
     * <p>The git artifacts are themselves built (with {@code javaHome}),
     * resolving their own dependencies against {@code baseRepos}.
     */
    public static Prepared prepare(
            JkBuild effective,
            RepoGroup baseRepos,
            Cas cas,
            Path javaHome,
            String jkVersion,
            Map<String, String> lockedShas)
            throws IOException, InterruptedException {
        Map<Scope, List<Dependency>> byScope = effective.dependencies().byScope();
        boolean anyGit = byScope.values().stream().flatMap(List::stream).anyMatch(Dependency::isGit);
        if (!anyGit) {
            return new Prepared(effective, baseRepos, Map.of());
        }

        GitSourceMaterializer materializer = new GitSourceMaterializer(cas, baseRepos, javaHome, jkVersion);

        // Materialize once per unique git source; a coordinate appearing in
        // several scopes (main + test) is built and published only once.
        Map<String, GitSourceMaterializer.Materialized> bySource = new LinkedHashMap<>();
        List<MavenRepo> extraRepos = new ArrayList<>();
        Map<String, Lockfile.Artifact.GitInfo> gitInfo = new LinkedHashMap<>();
        for (List<Dependency> list : byScope.values()) {
            for (Dependency d : list) {
                // Only immutable (tag/rev) git deps are materialized + lock-pinned.
                // Branch git deps are moving targets: they pass through to be built
                // on demand and injected at classpath time (the composite build path),
                // never locked — so skip them here.
                if (!d.isGit() || !d.gitSource().ref().isImmutable()) continue;
                String key = sourceKey(d.gitSource());
                if (bySource.containsKey(key)) continue;
                // Tag-rewrite canary: an immutable ref must still point where the
                // lockfile says before we build it.
                verifyImmutableRef(materializer, d.gitSource(), lockedShas);
                GitSourceMaterializer.Materialized m = materializer.materialize(d.gitSource());
                bySource.put(key, m);
                extraRepos.add(new MavenRepo(
                        "git:" + m.coordinate() + ":" + m.version(),
                        m.repoUrl(),
                        new Http(),
                        cas,
                        JkMavenLocalRepo.NONE));
                gitInfo.put(provenanceKey(m.coordinate(), m.version()), m.gitInfo());
            }
        }

        // Rewrite each git dep into an exact pin on the materialized coordinate.
        EnumMap<Scope, List<Dependency>> rewritten = new EnumMap<>(Scope.class);
        byScope.forEach((scope, list) -> {
            List<Dependency> out = new ArrayList<>(list.size());
            for (Dependency d : list) {
                // Non-git and branch-git deps pass through unchanged: branch git
                // deps stay as-is for the composite build path, not rewritten to a pin.
                if (!d.isGit() || !d.gitSource().ref().isImmutable()) {
                    out.add(d);
                    continue;
                }
                GitSourceMaterializer.Materialized m = bySource.get(sourceKey(d.gitSource()));
                out.add(Dependency.of(d.library(), m.coordinate(), VersionSelector.parse("=" + m.version())));
            }
            rewritten.put(scope, out);
        });

        JkBuild project = new JkBuild(
                effective.project(),
                new JkBuild.Dependencies(rewritten),
                effective.repositories(),
                effective.profiles(),
                effective.features(),
                effective.workspace(),
                effective.manifest());

        // Git artifact repos first: the pinned coordinate is built locally, so
        // the file:// repo answers before any remote is consulted.
        List<MavenRepo> merged = new ArrayList<>(extraRepos);
        merged.addAll(baseRepos.repos());
        return new Prepared(project, new RepoGroup(merged), gitInfo);
    }

    /**
     * Stamp git provenance onto the resolved packages produced from a
     * {@link #prepare}d build. Packages whose {@code group:artifact@version}
     * matches a materialized git artifact gain a {@link Lockfile.Artifact.GitInfo};
     * everything else is copied through unchanged.
     */
    public static Lockfile stamp(Lockfile lock, Map<String, Lockfile.Artifact.GitInfo> gitInfoByKey) {
        if (gitInfoByKey.isEmpty()) return lock;
        List<Lockfile.Artifact> out = new ArrayList<>(lock.artifacts().size());
        for (Lockfile.Artifact p : lock.artifacts()) {
            Lockfile.Artifact.GitInfo gi = gitInfoByKey.get(provenanceKey(p.name(), p.version()));
            if (gi != null && p.git() == null) {
                out.add(new Lockfile.Artifact(
                        p.name(),
                        p.version(),
                        p.source(),
                        p.checksum(),
                        p.path(),
                        p.scopes(),
                        p.deps(),
                        p.pinnedBy(),
                        gi));
            } else {
                out.add(p);
            }
        }
        return new Lockfile(
                lock.version(), lock.generatedBy(), lock.resolutionAlgorithm(), lock.jdk(), lock.kotlin(), out);
    }

    /**
     * Verify an immutable (tag/rev) ref still resolves to its locked SHA.
     * Branches are mutable by design, so they're never checked here — their
     * tip is simply re-resolved. No locked SHA for this ref → nothing to check.
     */
    private static void verifyImmutableRef(
            GitSourceMaterializer materializer, GitSource source, Map<String, String> lockedShas) throws IOException {
        if (lockedShas.isEmpty()) return;
        GitRefSpec ref = source.ref();
        if (!(ref instanceof GitRefSpec.Tag) && !(ref instanceof GitRefSpec.Rev)) return;
        String expected = lockedShas.get(source.canonicalUrl() + "|" + ref.token());
        if (expected != null) {
            materializer.verifyLocked(source, expected);
        }
    }

    /**
     * Build the {@code url|ref-token → SHA} map of immutable git refs recorded
     * in {@code lock}, for {@link #prepare}'s tag-rewrite check. Branch refs are
     * excluded — they're expected to move.
     */
    public static Map<String, String> lockedImmutableShas(Lockfile lock) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Lockfile.Artifact p : lock.artifacts()) {
            Lockfile.Artifact.GitInfo g = p.git();
            if (g == null || g.ref() == null) continue;
            if (g.ref().startsWith("tag=") || g.ref().startsWith("rev=")) {
                out.put(g.url() + "|" + g.ref(), g.rev());
            }
        }
        return out;
    }

    /**
     * Identity of a git source: same URL + ref + subpath + overrides → one
     * materialization. Overrides are part of the key so two deps on the same
     * commit that relabel it differently each get their own published artifact.
     */
    private static String sourceKey(GitSource source) {
        return String.join(
                "|",
                source.canonicalUrl(),
                source.ref().token(),
                source.path() == null ? "" : source.path(),
                source.overrideGroup() == null ? "" : source.overrideGroup(),
                source.overrideArtifact() == null ? "" : source.overrideArtifact(),
                source.overrideVersion() == null ? "" : source.overrideVersion());
    }

    private static String provenanceKey(String coordinate, String version) {
        return coordinate + "@" + version;
    }
}
