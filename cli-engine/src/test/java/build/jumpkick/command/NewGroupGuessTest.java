// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewGroupGuessTest {

    @Test
    void github_email_uses_io_github_username() {
        assertThat(NewGroupGuess.groupForEmail("octocat@github.com")).isEqualTo("io.github.octocat");
        assertThat(NewGroupGuess.groupForEmail("Octocat@GitHub.IO")).isEqualTo("io.github.octocat");
    }

    @Test
    void github_noreply_strips_digit_prefix() {
        // GitHub-issued noreply format: <userid>+<username>@users.noreply.github.com
        assertThat(NewGroupGuess.groupForEmail("12345+octocat@users.noreply.github.com"))
                .isEqualTo("io.github.octocat");
    }

    @Test
    void wellknown_free_mail_collapses_to_example() {
        assertThat(NewGroupGuess.groupForEmail("alice@gmail.com")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("alice@hotmail.com")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("alice@outlook.com")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("alice@proton.me")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("alice@yahoo.com")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("alice@icloud.com")).isEqualTo("com.example");
    }

    @Test
    void custom_domain_reverses_to_group() {
        assertThat(NewGroupGuess.groupForEmail("bryan@modmed.com")).isEqualTo("com.modmed");
        assertThat(NewGroupGuess.groupForEmail("user@team.example.co.uk")).isEqualTo("uk.co.example.team");
    }

    @Test
    void digit_leading_label_is_underscored() {
        // "3com" is a real historical company; the reverse must yield a valid
        // Java identifier segment (so the package compiles).
        assertThat(NewGroupGuess.groupForEmail("user@3com.com")).isEqualTo("com._3com");
    }

    @Test
    void malformed_email_falls_back() {
        assertThat(NewGroupGuess.groupForEmail("noatsign")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("@nopart.com")).isEqualTo("com.example");
        assertThat(NewGroupGuess.groupForEmail("local@")).isEqualTo("com.example");
    }

    @Test
    void reads_user_email_from_repo_gitconfig(@TempDir Path tempDir) throws IOException {
        var repo = tempDir.resolve("project");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve(".gitconfig"), """
                [user]
                    name = Alice
                    email = alice@modmed.com
                """);
        assertThat(NewGroupGuess.guess(repo, tempDir.resolve("home"))).isEqualTo("com.modmed");
    }

    @Test
    void walks_up_to_find_gitconfig(@TempDir Path tempDir) throws IOException {
        var nested = tempDir.resolve("a/b/c");
        Files.createDirectories(nested);
        Files.writeString(tempDir.resolve(".gitconfig"), """
                [user]
                    email = bob@example.org
                """);
        assertThat(NewGroupGuess.guess(nested, tempDir.resolve("home"))).isEqualTo("org.example");
    }

    @Test
    void falls_back_to_home_gitconfig(@TempDir Path tempDir) throws IOException {
        var work = tempDir.resolve("work");
        var home = tempDir.resolve("home");
        Files.createDirectories(work);
        Files.createDirectories(home);
        Files.writeString(home.resolve(".gitconfig"), """
                [user]
                    email = carol@github.com
                """);
        assertThat(NewGroupGuess.guess(work, home)).isEqualTo("io.github.carol");
    }

    @Test
    void no_gitconfig_returns_fallback(@TempDir Path tempDir) {
        var work = tempDir.resolve("work");
        var home = tempDir.resolve("home");
        assertThat(NewGroupGuess.guess(work, home)).isEqualTo("com.example");
    }

    @Test
    void ignores_email_outside_user_section(@TempDir Path tempDir) throws IOException {
        var work = tempDir.resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve(".gitconfig"), """
                [committer]
                    email = ignored@modmed.com
                [user]
                    email = real@modmed.com
                """);
        assertThat(NewGroupGuess.guess(work, tempDir.resolve("home"))).isEqualTo("com.modmed");
    }

    @Test
    void quoted_email_is_unquoted(@TempDir Path tempDir) throws IOException {
        var work = tempDir.resolve("work");
        Files.createDirectories(work);
        Files.writeString(work.resolve(".gitconfig"), """
                [user]
                    email = "user@modmed.com"
                """);
        assertThat(NewGroupGuess.guess(work, tempDir.resolve("home"))).isEqualTo("com.modmed");
    }
}
