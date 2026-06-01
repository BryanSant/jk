// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
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
final class GitSourceResolution {

    private GitSourceResolution() {}

    /**
     * The result of preparing a build for resolution: the dependency-rewritten
     * project, the repo group augmented with each git artifact's {@code file://}
     * repo, and a map from {@code group:artifact@version} to the git provenance
     * to stamp onto the matching lockfile package.
     */
    record Prepared(JkBuild project, RepoGroup repos,
                    Map<String, Lockfile.Package.GitInfo> gitInfoByKey) {}

    /**
     * Materialize every git dependency in {@code effective}, augment
     * {@code baseRepos}, and rewrite git deps to coordinate pins. The git
     * artifacts are themselves built (with {@code javaHome}) resolving their own
     * dependencies against {@code baseRepos}.
     */
    static Prepared prepare(JkBuild effective, RepoGroup baseRepos, Cas cas,
                            Path javaHome, String jkVersion)
            throws IOException, InterruptedException {
        Map<Scope, List<Dependency>> byScope = effective.dependencies().byScope();
        boolean anyGit = byScope.values().stream().flatMap(List::stream).anyMatch(Dependency::isGit);
        if (!anyGit) {
            return new Prepared(effective, baseRepos, Map.of());
        }

        GitSourceMaterializer materializer =
                new GitSourceMaterializer(cas, baseRepos, javaHome, jkVersion);

        // Materialize once per unique git source; a coordinate appearing in
        // several scopes (main + test) is built and published only once.
        Map<String, GitSourceMaterializer.Materialized> bySource = new LinkedHashMap<>();
        List<MavenRepo> extraRepos = new ArrayList<>();
        Map<String, Lockfile.Package.GitInfo> gitInfo = new LinkedHashMap<>();
        for (List<Dependency> list : byScope.values()) {
            for (Dependency d : list) {
                if (!d.isGit()) continue;
                String key = sourceKey(d.gitSource());
                if (bySource.containsKey(key)) continue;
                GitSourceMaterializer.Materialized m = materializer.materialize(d.gitSource());
                bySource.put(key, m);
                extraRepos.add(new MavenRepo(
                        "git:" + m.coordinate() + ":" + m.version(),
                        m.repoUrl(), new Http(), cas, Journal.NONE));
                gitInfo.put(provenanceKey(m.coordinate(), m.version()), m.gitInfo());
            }
        }

        // Rewrite each git dep into an exact pin on the materialized coordinate.
        EnumMap<Scope, List<Dependency>> rewritten = new EnumMap<>(Scope.class);
        byScope.forEach((scope, list) -> {
            List<Dependency> out = new ArrayList<>(list.size());
            for (Dependency d : list) {
                if (!d.isGit()) {
                    out.add(d);
                    continue;
                }
                GitSourceMaterializer.Materialized m = bySource.get(sourceKey(d.gitSource()));
                out.add(Dependency.of(d.name(), m.coordinate(),
                        VersionSelector.parse("=" + m.version())));
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
     * matches a materialized git artifact gain a {@link Lockfile.Package.GitInfo};
     * everything else is copied through unchanged.
     */
    static Lockfile stamp(Lockfile lock, Map<String, Lockfile.Package.GitInfo> gitInfoByKey) {
        if (gitInfoByKey.isEmpty()) return lock;
        List<Lockfile.Package> out = new ArrayList<>(lock.packages().size());
        for (Lockfile.Package p : lock.packages()) {
            Lockfile.Package.GitInfo gi = gitInfoByKey.get(provenanceKey(p.name(), p.version()));
            if (gi != null && p.git() == null) {
                out.add(new Lockfile.Package(p.name(), p.version(), p.source(),
                        p.checksum(), p.path(), p.scopes(), p.deps(), p.pinnedBy(), gi));
            } else {
                out.add(p);
            }
        }
        return new Lockfile(lock.version(), lock.generatedBy(), lock.resolutionAlgorithm(),
                lock.jdk(), lock.kotlin(), out);
    }

    /**
     * Identity of a git source: same URL + ref + subpath + overrides → one
     * materialization. Overrides are part of the key so two deps on the same
     * commit that relabel it differently each get their own published artifact.
     */
    private static String sourceKey(GitSource source) {
        return String.join("|",
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
