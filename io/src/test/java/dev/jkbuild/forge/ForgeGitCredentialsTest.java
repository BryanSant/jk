// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.TokenStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ForgeGitCredentialsTest {

    /** A ForgeAuth with no env/CLI and an injectable token store. */
    private static ForgeAuth forgeWith(Path dir, String host, String token) {
        TokenStore store = new TokenStore(dir);
        if (token != null) store.write(host, token);
        return new ForgeAuth(store, k -> null, argv -> Optional.empty());
    }

    @Test
    void github_uses_token_as_username(@TempDir Path dir) {
        var forge = forgeWith(dir, "github.com", "ghp_tok");
        var creds = ForgeGitCredentials.credentials("https://github.com/acme/widgets", forge);
        assertThat(creds).hasValueSatisfying(up -> {
            assertThat(up[0]).isEqualTo("ghp_tok");   // username = token
            assertThat(up[1]).isEqualTo("");
        });
    }

    @Test
    void gitlab_uses_oauth2_username(@TempDir Path dir) {
        var forge = forgeWith(dir, "gitlab.com", "glpat");
        var creds = ForgeGitCredentials.credentials("https://gitlab.com/acme/edge", forge);
        assertThat(creds).hasValueSatisfying(up -> {
            assertThat(up[0]).isEqualTo("oauth2");
            assertThat(up[1]).isEqualTo("glpat");
        });
    }

    @Test
    void ssh_remote_is_anonymous(@TempDir Path dir) {
        var forge = forgeWith(dir, "github.com", "ghp_tok");
        assertThat(ForgeGitCredentials.credentials("ssh://git@github.com/acme/widgets", forge))
                .isEmpty();
    }

    @Test
    void unknown_host_is_anonymous(@TempDir Path dir) {
        var forge = forgeWith(dir, "git.internal.corp", "tok");
        assertThat(ForgeGitCredentials.credentials("https://git.internal.corp/x/y", forge))
                .isEmpty();
    }

    @Test
    void no_stored_token_is_anonymous(@TempDir Path dir) {
        var forge = forgeWith(dir, "github.com", null);   // nothing stored
        assertThat(ForgeGitCredentials.credentials("https://github.com/acme/widgets", forge))
                .isEmpty();
    }

    @Test
    void per_forge_shapes() {
        assertThat(ForgeGitCredentials.usernamePassword(ForgeKind.GITHUB, "t")).containsExactly("t", "");
        assertThat(ForgeGitCredentials.usernamePassword(ForgeKind.GITEA, "t")).containsExactly("t", "");
        assertThat(ForgeGitCredentials.usernamePassword(ForgeKind.GITLAB, "t")).containsExactly("oauth2", "t");
        assertThat(ForgeGitCredentials.usernamePassword(ForgeKind.BITBUCKET, "t"))
                .containsExactly("x-token-auth", "t");
    }
}
