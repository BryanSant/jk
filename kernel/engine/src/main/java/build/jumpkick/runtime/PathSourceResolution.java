// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.http.Http;
import build.jumpkick.model.Dependency;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.PathSource;
import build.jumpkick.model.Scope;
import build.jumpkick.model.VersionSelector;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoArtifactResolver;
import build.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pre-solve bridge between local-path dependencies and the Maven resolver — the path-source
 * analogue of {@link GitSourceResolution}. For each {@code path = "..."} dependency it:
 *
 * <ol>
 *   <li>materializes the target directory into a {@code file://} Maven repo ({@link
 *       PathSourceMaterializer} — jk/Gradle/Maven, compile/package only),
 *   <li>augments the {@link RepoGroup} with that {@code file://} repo, and
 *   <li>rewrites the dependency into an <em>exact coordinate pin</em> ({@code group:artifact =
 *       "=version"}) the PubGrub solver resolves like any other coordinate.
 * </ol>
 *
 * <p>Path deps carry no lock provenance (unlike git's {@code GitInfo}): the {@code jk.toml} {@code
 * path} entry is the source of truth and is re-materialized from the still-present local directory
 * on every lock. A project with no path dependencies passes through untouched — same project, same
 * repos — so the common path is a zero-cost no-op.
 */
public final class PathSourceResolution {

    private PathSourceResolution() {}

    /** The result of preparing a build: the dependency-rewritten project and the augmented repos. */
    public record Prepared(JkBuild project, RepoGroup repos) {}

    /**
     * Materialize every path dependency in {@code effective} (resolving each against {@code
     * lockRootDir} — the directory of the consuming {@code jk.toml}), augment {@code baseRepos} with
     * each artifact's {@code file://} repo, and rewrite path deps to coordinate pins.
     */
    public static Prepared prepare(
            JkBuild effective, RepoGroup baseRepos, Cas cas, Path lockRootDir, Path javaHome, String jkVersion)
            throws IOException, InterruptedException {
        Map<Scope, List<Dependency>> byScope = effective.dependencies().byScope();
        boolean anyPath = byScope.values().stream().flatMap(List::stream).anyMatch(Dependency::isPath);
        if (!anyPath) {
            return new Prepared(effective, baseRepos);
        }

        PathSourceMaterializer materializer =
                new PathSourceMaterializer(lockRootDir, cas, baseRepos, javaHome, jkVersion);

        // Materialize once per unique target directory; a target appearing in several scopes
        // (main + test) is built and published only once.
        Map<String, PathSourceMaterializer.Materialized> bySource = new LinkedHashMap<>();
        List<MavenRepo> extraRepos = new ArrayList<>();
        for (List<Dependency> list : byScope.values()) {
            for (Dependency d : list) {
                if (!d.isPath()) continue;
                String key = d.pathSource().rawPath();
                if (bySource.containsKey(key)) continue;
                PathSourceMaterializer.Materialized m = materializer.materialize(d.pathSource());
                bySource.put(key, m);
                extraRepos.add(new MavenRepo(
                        RepoArtifactResolver.GIT_SOURCE_PREFIX + m.coordinate() + ":" + m.version(),
                        m.repoUrl(),
                        new Http(),
                        cas));
            }
        }

        // Rewrite each path dep into an exact pin on the materialized coordinate.
        EnumMap<Scope, List<Dependency>> rewritten = new EnumMap<>(Scope.class);
        byScope.forEach((scope, list) -> {
            List<Dependency> out = new ArrayList<>(list.size());
            for (Dependency d : list) {
                if (!d.isPath()) {
                    out.add(d);
                    continue;
                }
                PathSourceMaterializer.Materialized m = bySource.get(d.pathSource().rawPath());
                out.add(Dependency.of(d.library(), m.coordinate(), VersionSelector.parse("=" + m.version())));
            }
            rewritten.put(scope, out);
        });

        JkBuild project = JkBuild.builder(effective.project())
                .dependencies(new JkBuild.Dependencies(rewritten))
                .repositories(effective.repositories())
                .profiles(effective.profiles())
                .features(effective.features())
                .workspace(effective.workspace())
                .manifest(effective.manifest())
                .build();

        // Path artifact repos first: the pinned coordinate is built locally, so the file:// repo
        // answers before any remote is consulted.
        List<MavenRepo> merged = new ArrayList<>(extraRepos);
        merged.addAll(baseRepos.repos());
        return new Prepared(project, new RepoGroup(merged));
    }
}
