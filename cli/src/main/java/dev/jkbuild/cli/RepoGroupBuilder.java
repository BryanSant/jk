// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link RepoGroup} from a {@link JkBuild}'s declared repositories,
 * with command-line overrides for {@code --repo-url} (used in tests). If
 * neither the project nor the override specifies a repo, default to Maven
 * Central.
 *
 * <p>One {@link Http} client and one {@link Cas} are shared across the
 * resulting {@link MavenRepo}s — they all back into the same cache.
 */
final class RepoGroupBuilder {

    private RepoGroupBuilder() {}

    static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas) {
        Http http = new Http();
        // Coordinate→hash index over the CAS, so fetches are recorded and an
        // offline run can resolve from what's already on disk.
        Journal journal = new Journal(cas.root());
        List<MavenRepo> repos = new ArrayList<>();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            repos.add(new MavenRepo("central", overrideUrl, http, cas, journal));
        } else if (project.repositories().isEmpty()) {
            repos.add(new MavenRepo(RepositorySpec.MAVEN_CENTRAL.name(),
                    RepositorySpec.MAVEN_CENTRAL.url(), http, cas, journal));
        } else {
            for (RepositorySpec spec : project.repositories()) {
                repos.add(new MavenRepo(spec.name(), spec.url(), http, cas, journal));
            }
        }
        return new RepoGroup(repos);
    }
}
