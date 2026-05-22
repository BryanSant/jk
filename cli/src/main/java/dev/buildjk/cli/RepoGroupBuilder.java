// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.http.Http;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.RepositorySpec;
import dev.buildjk.repo.MavenRepo;
import dev.buildjk.repo.RepoGroup;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link RepoGroup} from a {@link BuildJk}'s declared repositories,
 * with command-line overrides for {@code --repo-url} (used in tests). If
 * neither the project nor the override specifies a repo, default to Maven
 * Central.
 *
 * <p>One {@link Http} client and one {@link Cas} are shared across the
 * resulting {@link MavenRepo}s — they all back into the same cache.
 */
final class RepoGroupBuilder {

    private RepoGroupBuilder() {}

    static RepoGroup buildFor(BuildJk project, URI overrideUrl, Cas cas) {
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            repos.add(new MavenRepo("central", overrideUrl, http, cas));
        } else if (project.repositories().isEmpty()) {
            repos.add(new MavenRepo(RepositorySpec.MAVEN_CENTRAL.name(),
                    RepositorySpec.MAVEN_CENTRAL.url(), http, cas));
        } else {
            for (RepositorySpec spec : project.repositories()) {
                repos.add(new MavenRepo(spec.name(), spec.url(), http, cas));
            }
        }
        return new RepoGroup(repos);
    }
}
