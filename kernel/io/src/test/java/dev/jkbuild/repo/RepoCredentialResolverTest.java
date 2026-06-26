// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.credential.MavenSettings;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeIdentity;
import dev.jkbuild.forge.TokenStore;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoCredentialResolverTest {

    /** Identity lookup that can't resolve a login (offline / error). */
    private static final ForgeIdentity NO_IDENTITY = (endpoint, field, token) -> Optional.empty();

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    /** A ForgeAuth with no env/CLI and an injectable token store dir. */
    private static ForgeAuth forge(TokenStore store) {
        return new ForgeAuth(store, k -> null, argv -> Optional.empty());
    }

    private static RepoCredentialResolver resolver(
            Function<String, String> env, MavenSettings settings, RepoCredentialStore store, ForgeAuth forge) {
        return new RepoCredentialResolver(env, settings, store, forge, NO_IDENTITY);
    }

    @Test
    void inline_credential_wins(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        RepoCredential cred = r.resolve(
                "nexus", URI.create("https://nexus.corp/repo"), Optional.of(new RepoCredential.Bearer("inline-tok")));
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("inline-tok"));
    }

    @Test
    void env_token_becomes_bearer(@TempDir Path dir) {
        var r = resolver(
                env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("env-tok"));
    }

    @Test
    void env_username_password_becomes_basic(@TempDir Path dir) {
        var r = resolver(
                env(Map.of(
                        "JK_REPO_CORP_NEXUS_USERNAME", "deployer",
                        "JK_REPO_CORP_NEXUS_PASSWORD", "s3cr3t")),
                MavenSettings.empty(),
                new RepoCredentialStore(dir),
                forge(new TokenStore(dir)));
        // Note: the id "corp-nexus" sanitizes to CORP_NEXUS for the env var.
        assertThat(r.resolve("corp-nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Basic("deployer", "s3cr3t"));
    }

    @Test
    void store_used_when_no_inline_or_env(@TempDir Path dir) {
        var store = new RepoCredentialStore(dir);
        store.write("nexus", new RepoCredential.Bearer("stored-tok"));
        var r = resolver(env(Map.of()), MavenSettings.empty(), store, forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("stored-tok"));
    }

    @Test
    void github_packages_bridge_uses_basic_with_resolved_login(@TempDir Path dir) {
        // A prior `jk auth login github` left a token; GitHub Packages reuses it
        // as HTTP Basic with the account login as username.
        var forgeStore = new TokenStore(dir);
        forgeStore.write("github.com", "gho_pkgtoken");
        ForgeIdentity octocat = (endpoint, field, token) -> Optional.of("octocat");
        var r = new RepoCredentialResolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore),
                octocat);

        RepoCredential cred = r.resolve("ghp", URI.create("https://maven.pkg.github.com/jkbuild/jk"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Basic("octocat", "gho_pkgtoken"));
    }

    @Test
    void github_packages_bridge_falls_back_to_bearer_when_login_unavailable(@TempDir Path dir) {
        // Offline / API error → no login → fall back to Bearer (no worse than before).
        var forgeStore = new TokenStore(dir);
        forgeStore.write("github.com", "gho_pkgtoken");
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore)); // NO_IDENTITY

        RepoCredential cred = r.resolve("ghp", URI.create("https://maven.pkg.github.com/jkbuild/jk"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("gho_pkgtoken"));
    }

    @Test
    void gitlab_packages_bridge_uses_bearer(@TempDir Path dir) {
        // GitLab's package API takes the device-flow OAuth token as a Bearer.
        var forgeStore = new TokenStore(dir);
        forgeStore.write("gitlab.com", "glpat-or-oauth");
        var r = resolver(
                env(Map.of()),
                MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")),
                forge(forgeStore));

        RepoCredential cred =
                r.resolve("gl", URI.create("https://gitlab.com/api/v4/projects/1/packages/maven"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("glpat-or-oauth"));
    }

    @Test
    void anonymous_when_nothing_matches(@TempDir Path dir) {
        var r = resolver(
                env(Map.of()), MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        assertThat(r.resolve("central", URI.create("https://repo.maven.apache.org/maven2/"), Optional.empty()))
                .isEqualTo(RepoCredential.ANONYMOUS);
    }
}
