// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.credential.MavenSettings;
import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.forge.CliTokenProbe;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.TokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCredentialResolverTest {

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    /** A ForgeAuth with no env/CLI and an injectable token store dir. */
    private static ForgeAuth forge(TokenStore store) {
        return new ForgeAuth(store, k -> null, argv -> Optional.empty());
    }

    private static RepoCredentialResolver resolver(
            Function<String, String> env, MavenSettings settings,
            RepoCredentialStore store, ForgeAuth forge) {
        return new RepoCredentialResolver(env, settings, store, forge);
    }

    @Test
    void inline_credential_wins(@TempDir Path dir) {
        var r = resolver(env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        RepoCredential cred = r.resolve("nexus", URI.create("https://nexus.corp/repo"),
                Optional.of(new RepoCredential.Bearer("inline-tok")));
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("inline-tok"));
    }

    @Test
    void env_token_becomes_bearer(@TempDir Path dir) {
        var r = resolver(env(Map.of("JK_REPO_NEXUS_TOKEN", "env-tok")),
                MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        assertThat(r.resolve("nexus", URI.create("https://nexus.corp/repo"), Optional.empty()))
                .isEqualTo(new RepoCredential.Bearer("env-tok"));
    }

    @Test
    void env_username_password_becomes_basic(@TempDir Path dir) {
        var r = resolver(env(Map.of(
                        "JK_REPO_CORP_NEXUS_USERNAME", "deployer",
                        "JK_REPO_CORP_NEXUS_PASSWORD", "s3cr3t")),
                MavenSettings.empty(), new RepoCredentialStore(dir), forge(new TokenStore(dir)));
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
    void forge_bridge_borrows_github_token_for_packages(@TempDir Path dir) {
        // A prior `jk auth login github` left a token; GitHub Packages reuses it.
        var forgeStore = new TokenStore(dir);
        forgeStore.write("github.com", "gho_pkgtoken");
        var r = resolver(env(Map.of()), MavenSettings.empty(),
                new RepoCredentialStore(dir.resolve("repocreds")), forge(forgeStore));

        RepoCredential cred = r.resolve("ghp",
                URI.create("https://maven.pkg.github.com/jkbuild/jk"), Optional.empty());
        assertThat(cred).isEqualTo(new RepoCredential.Bearer("gho_pkgtoken"));
    }

    @Test
    void anonymous_when_nothing_matches(@TempDir Path dir) {
        var r = resolver(env(Map.of()), MavenSettings.empty(),
                new RepoCredentialStore(dir), forge(new TokenStore(dir)));
        assertThat(r.resolve("central", URI.create("https://repo.maven.apache.org/maven2/"),
                Optional.empty())).isEqualTo(RepoCredential.ANONYMOUS);
    }
}
