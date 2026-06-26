// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.JkMavenLocalRepo;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.repo.RepoTransport;
import dev.jkbuild.repo.RepoTransports;
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
public final class RepoGroupBuilder {

    private RepoGroupBuilder() {}

    public static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas) {
        Http http = new Http();
        // m2 local-repo mirror over the CAS, so fetches are recorded and an
        // offline run can resolve from what's already on disk.
        JkMavenLocalRepo localRepo = new JkMavenLocalRepo(cas.root());
        List<MavenRepo> repos = new ArrayList<>();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            repos.add(new MavenRepo("central", overrideUrl, http, cas, localRepo));
        } else if (project.repositories().isEmpty()) {
            repos.add(new MavenRepo(
                    RepositorySpec.MAVEN_CENTRAL.name(), RepositorySpec.MAVEN_CENTRAL.url(), http, cas, localRepo));
        } else {
            // Resolve credentials per declared repo (env / store / settings.xml /
            // forge-token bridge). Public repos resolve to ANONYMOUS, so this is
            // transparent for Maven Central and other open mirrors.
            RepoCredentialResolver creds = new RepoCredentialResolver();
            for (RepositorySpec spec : project.repositories()) {
                RepoCredential cred = creds.resolve(spec.name(), spec.url(), spec.credential());
                // Per-repo object-store config (region/endpoint/keys) flows to the
                // transport; HTTP credentials still ride the MavenRepo credential.
                RepoTransport transport = RepoTransports.forUrl(
                        spec.url(), http, spec.objectStore().orElse(ObjectStoreConfig.EMPTY));
                repos.add(new MavenRepo(spec.name(), spec.url(), transport, cas, localRepo, cred));
            }
        }
        return new RepoGroup(repos);
    }
}
