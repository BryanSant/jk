// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.model.Coordinate;
import dev.buildjk.repo.EffectivePom;
import dev.buildjk.repo.EffectivePomBuilder;
import dev.buildjk.repo.MavenMetadata;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.Pom;
import dev.buildjk.repo.RepoGroup;
import dev.buildjk.resolver.pubgrub.PackageSource;
import dev.buildjk.resolver.pubgrub.Term;
import dev.buildjk.resolver.pubgrub.VersionSet;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts a {@link MavenRepo} + {@link EffectivePomBuilder} into the
 * PubGrub {@link PackageSource} contract.
 *
 * <p>Caches versions and dep lists in-memory per solver run so repeated
 * lookups during conflict resolution don't re-hit the network.
 */
public final class MavenPackageSource implements PackageSource {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final Map<String, List<String>> versionCache = new HashMap<>();
    private final Map<String, List<Term>> depsCache = new HashMap<>();

    public MavenPackageSource(MavenRepo repo, EffectivePomBuilder pomBuilder) {
        this(RepoGroup.of(repo), pomBuilder);
    }

    public MavenPackageSource(RepoGroup repos, EffectivePomBuilder pomBuilder) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.pomBuilder = Objects.requireNonNull(pomBuilder, "pomBuilder");
    }

    @Override
    public List<String> versions(String pkg) throws IOException, InterruptedException {
        List<String> cached = versionCache.get(pkg);
        if (cached != null) return cached;
        Coordinate metadataCoord = withVersion(pkg, "any");
        RepoGroup.RepoFetched hit = repos.tryFetchMetadata(metadataCoord).orElse(null);
        if (hit == null) {
            versionCache.put(pkg, List.of());
            return List.of();
        }
        MavenMetadata metadata = MavenMetadata.parse(Files.readAllBytes(hit.fetched().cachePath()));
        // PubGrub picks the first version that satisfies — order highest first.
        List<String> sorted = new ArrayList<>(metadata.versions());
        sorted.sort((a, b) -> Versions.compare(b, a));
        versionCache.put(pkg, sorted);
        return sorted;
    }

    @Override
    public List<Term> dependencies(String pkg, String version)
            throws IOException, InterruptedException {
        String key = pkg + "@" + version;
        List<Term> cached = depsCache.get(key);
        if (cached != null) return cached;

        Coordinate coord = withVersion(pkg, version);
        EffectivePom pom = pomBuilder.build(coord);
        List<Term> out = new ArrayList<>();
        for (Pom.Dep dep : pom.dependencies()) {
            if (dep.optional()) continue;
            String scope = dep.scope();
            if (scope != null && !scope.isEmpty() && !FOLLOWED_SCOPES.contains(scope)) continue;
            if (dep.version() == null || dep.version().isBlank()) continue;
            // Maven POM versions are (post-depMgmt) usually exact pins.
            // Real Maven ranges are rare; parsing them is future work.
            out.add(Term.positive(dep.module(), VersionSet.exact(dep.version())));
        }
        depsCache.put(key, List.copyOf(out));
        return depsCache.get(key);
    }

    private static Coordinate withVersion(String pkg, String version) {
        int colon = pkg.indexOf(':');
        return Coordinate.of(pkg.substring(0, colon), pkg.substring(colon + 1), version);
    }
}
