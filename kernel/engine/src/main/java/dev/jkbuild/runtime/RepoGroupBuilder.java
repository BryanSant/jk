// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.ObjectStoreConfig;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoCredentialResolver;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.repo.RepoTransport;
import dev.jkbuild.repo.RepoTransports;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link RepoGroup} from a {@link JkBuild}'s declared repositories, with command-line
 * overrides for {@code --repo-url} (used in tests). If neither the project nor the override
 * specifies a repo, default to Maven Central.
 *
 * <p>Repository resolution order (highest to lowest precedence):
 * <ol>
 *   <li>Project-level {@code [repositories]} in {@code jk.toml}
 *   <li>User-global {@code [repositories]} in {@code ~/.jk/config.toml}
 *   <li>Built-in Maven Central fallback
 * </ol>
 *
 * <p>When both the project and global config declare a repo with the same name, the project's
 * declaration wins. The effective list is deduplicated by name in the order above.
 *
 * <p>One {@link Http} client and one {@link Cas} are shared across the resulting {@link MavenRepo}s
 * — they all back into the same cache.
 */
public final class RepoGroupBuilder {

    private RepoGroupBuilder() {}

    public static RepoGroup buildFor(JkBuild project, URI overrideUrl, Cas cas) {
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>();
        boolean mirrorToM2 = project.project().m2install();
        if (overrideUrl != null) {
            // Tests pin one URL; project-declared repos are ignored.
            repos.add(new MavenRepo("central", overrideUrl, http, cas, RepoCredential.ANONYMOUS, mirrorToM2));
        } else {
            // Merge: project repos > global repos > built-in Maven Central.
            // Deduplicate by name: first declaration wins (project beats global,
            // global beats built-in).
            List<RepositorySpec> projectRepos = project.repositories();
            List<RepositorySpec> globalRepos = GlobalConfig.repositories();

            // Build ordered dedup map: project first, then global fill-ins.
            Map<String, RepositorySpec> byName = new LinkedHashMap<>();
            for (RepositorySpec s : projectRepos) byName.put(s.name(), s);
            for (RepositorySpec s : globalRepos) byName.putIfAbsent(s.name(), s);

            List<RepositorySpec> effective;
            if (byName.isEmpty()) {
                // Neither project nor global declared any repos → use built-in.
                effective = List.of(RepositorySpec.MAVEN_CENTRAL);
            } else {
                effective = new ArrayList<>(byName.values());
                // If no repo named "central" was declared, append Maven Central as
                // the final fallback so artifact resolution has a public baseline.
                if (!byName.containsKey(RepositorySpec.MAVEN_CENTRAL.name())) {
                    effective.add(RepositorySpec.MAVEN_CENTRAL);
                }
            }

            // Resolve credentials per declared repo (env / store / settings.xml /
            // forge-token bridge). Public repos resolve to ANONYMOUS, so this is
            // transparent for Maven Central and other open mirrors.
            RepoCredentialResolver creds = new RepoCredentialResolver();
            for (RepositorySpec spec : effective) {
                RepoCredential cred = creds.resolve(spec.name(), spec.url(), spec.credential());
                // Per-repo object-store config (region/endpoint/keys) flows to the
                // transport; HTTP credentials still ride the MavenRepo credential.
                RepoTransport transport = RepoTransports.forUrl(
                        spec.url(), http, spec.objectStore().orElse(ObjectStoreConfig.EMPTY));
                repos.add(new MavenRepo(spec.name(), spec.url(), transport, cas, cred, mirrorToM2));
            }
        }
        return new RepoGroup(repos);
    }
}
