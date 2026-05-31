// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ForgeAuthConfigTest {

    private static Path write(Path dir, String name, String toml) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, toml);
        return p;
    }

    @Test
    void reads_provider_default_client_id(@TempDir Path dir) throws Exception {
        Path file = write(dir, "jk.toml", """
                [forge.github]
                client-id = "Iv1.github-default"

                [forge.gitea]
                client-id = "gitea-default"
                """);

        ForgeAuthConfig cfg = ForgeAuthConfig.loadFrom(file);
        assertThat(cfg.oauthClientId("github", "github.com")).contains("Iv1.github-default");
        assertThat(cfg.oauthClientId("gitea", "codeberg.org")).contains("gitea-default");
        assertThat(cfg.oauthClientId("gitlab", "gitlab.com")).isEmpty();
    }

    @Test
    void per_host_override_wins_over_provider_default(@TempDir Path dir) throws Exception {
        Path file = write(dir, "jk.toml", """
                [forge.github]
                client-id = "Iv1.dotcom"

                [[forge.host]]
                name = "ghe.corp.example"
                client-id = "Iv1.enterprise"
                """);

        ForgeAuthConfig cfg = ForgeAuthConfig.loadFrom(file);
        // Self-hosted host gets its own app; github.com still gets the default.
        assertThat(cfg.oauthClientId("github", "ghe.corp.example")).contains("Iv1.enterprise");
        assertThat(cfg.oauthClientId("github", "github.com")).contains("Iv1.dotcom");
    }

    @Test
    void host_lookup_is_case_insensitive(@TempDir Path dir) throws Exception {
        Path file = write(dir, "jk.toml", """
                [[forge.host]]
                name = "GHE.Corp.Example"
                client-id = "Iv1.enterprise"
                """);

        ForgeAuthConfig cfg = ForgeAuthConfig.loadFrom(file);
        assertThat(cfg.oauthClientId("github", "ghe.corp.example")).contains("Iv1.enterprise");
    }

    @Test
    void missing_file_and_missing_table_are_empty(@TempDir Path dir) throws Exception {
        assertThat(ForgeAuthConfig.loadFrom(dir.resolve("nope.toml")).isEmpty()).isTrue();

        Path noForge = write(dir, "jk.toml", """
                [config]
                offline = true
                """);
        assertThat(ForgeAuthConfig.loadFrom(noForge).isEmpty()).isTrue();
    }

    @Test
    void malformed_toml_degrades_to_empty(@TempDir Path dir) throws Exception {
        Path bad = write(dir, "jk.toml", "[forge.github\nclient-id = ");
        assertThat(ForgeAuthConfig.loadFrom(bad).isEmpty()).isTrue();
    }

    @Test
    void merge_lets_higher_layer_win(@TempDir Path dir) throws Exception {
        ForgeAuthConfig lower = ForgeAuthConfig.loadFrom(write(dir, "low.toml", """
                [forge.github]
                client-id = "from-system"
                """));
        ForgeAuthConfig higher = ForgeAuthConfig.loadFrom(write(dir, "high.toml", """
                [forge.github]
                client-id = "from-user"
                """));

        assertThat(lower.mergedWith(higher).oauthClientId("github", null)).contains("from-user");
        // And the lower layer's other entries survive a merge that doesn't touch them.
        assertThat(higher.mergedWith(lower).oauthClientId("github", null)).contains("from-system");
    }
}
