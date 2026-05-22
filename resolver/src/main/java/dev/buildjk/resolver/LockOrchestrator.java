// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Coordinate;
import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;
import dev.buildjk.repo.MavenRepo;

import java.io.IOException;
import java.util.ArrayList;
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
 */
public final class LockOrchestrator {

    private final MavenRepo repo;
    private final Resolver resolver;

    public LockOrchestrator(MavenRepo repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.resolver = new PubGrubResolver(repo);
    }

    /** Test seam: lets tests inject a different resolver (e.g. NaiveResolver). */
    LockOrchestrator(MavenRepo repo, Resolver resolver) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public Lockfile lock(BuildJk project, String jkVersion) throws IOException, InterruptedException {
        List<Dependency> declared = project.dependencies().of(Scope.MAIN);
        Resolution resolution = resolver.resolve(declared);

        String source = repo.name() + "+" + repo.baseUrl();
        List<Lockfile.Package> packages = new ArrayList<>(resolution.modules().size());

        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            int colon = mod.module().indexOf(':');
            Coordinate coord = Coordinate.of(
                    mod.module().substring(0, colon),
                    mod.module().substring(colon + 1),
                    mod.version());

            String checksum = null;
            try {
                MavenRepo.Fetched fetched = repo.fetchArtifact(coord);
                checksum = "sha256:" + fetched.sha256();
            } catch (MavenRepo.ArtifactNotFoundException ignored) {
                // POM-only artifact; leave checksum null. Real Maven Central
                // never serves jars for <packaging>pom</packaging>, and the
                // dep walker shouldn't drag those in directly — but if it
                // does, we lock the version without a jar checksum rather
                // than blow up.
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
