// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.RepoGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Composes the resolver pipeline end-to-end: parsed {@link BuildJk} ->
 * {@link Resolution} -> artifact downloads -> {@link Lockfile}.
 *
 * <p>v0.1 scope: resolves the {@link Scope#MAIN} declared deps only.
 * Test / runtime / provided scopes get their own resolution passes once
 * we wire {@code jk test} and the runtime-classpath story. POM-only
 * artifacts (parents declared as deps, BOMs at compile scope) leave
 * {@code checksum} {@code null} rather than fail the lock.
 *
 * <p>Multi-repo first-hit-wins per PRD §7.5: artifacts are fetched from
 * declared repos in order, and the lockfile's {@code source} field
 * records the repo that actually served the artifact.
 */
public final class LockOrchestrator {

    private final RepoGroup repos;
    private final Resolver resolver;

    public LockOrchestrator(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public LockOrchestrator(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolver = new PubGrubResolver(repos);
    }

    /** Test seam: lets tests inject a different resolver (e.g. NaiveResolver). */
    LockOrchestrator(RepoGroup repos, Resolver resolver) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public Lockfile lock(BuildJk project, String jkVersion) throws IOException, InterruptedException {
        // Lock main + runtime + provided + test deps in a single pass. v0.2's
        // jk.lock doesn't yet track scope per package, so jk test pulls
        // junit-jupiter from the same lock the runtime classpath uses.
        // Per-scope tagging lands with the v0.3 lockfile schema bump.
        LinkedHashMap<String, Dependency> deduped = new LinkedHashMap<>();
        for (Scope scope : List.of(Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST)) {
            for (Dependency dep : project.dependencies().of(scope)) {
                deduped.putIfAbsent(dep.module(), dep);
            }
        }
        List<Dependency> declared = new ArrayList<>(deduped.values());
        Resolution resolution = resolver.resolve(declared);

        // Fallback source string used when no repo could serve the artifact.
        MavenRepo first = repos.repos().getFirst();
        String fallbackSource = first.name() + "+" + first.baseUrl();

        List<Lockfile.Package> packages = new ArrayList<>(resolution.modules().size());
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            int colon = mod.module().indexOf(':');
            Coordinate coord = Coordinate.of(
                    mod.module().substring(0, colon),
                    mod.module().substring(colon + 1),
                    mod.version());

            String source = fallbackSource;
            String checksum = null;
            RepoGroup.RepoFetched hit = repos.tryFetchArtifact(coord).orElse(null);
            if (hit != null) {
                source = hit.repo().name() + "+" + hit.repo().baseUrl();
                checksum = "sha256:" + hit.fetched().sha256();
            }

            packages.add(new Lockfile.Package(
                    mod.module(),
                    mod.version(),
                    source,
                    checksum,
                    null,
                    mod.deps()));
        }

        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk " + jkVersion,
                Lockfile.RESOLUTION_ALGORITHM,
                packages);
    }
}
