// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import dev.jkbuild.config.ForgeAuthConfig;
import dev.jkbuild.forge.ForgeKind;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code jk auth} command wiring. Uses Gitea on a made-up host
 * for the round-trip cases: it has no native token CLI to shell out to and
 * no env var likely to be set in CI, so the only token source is jk's own
 * store — which the hidden {@code --credentials-dir} points at a temp dir.
 */
class AuthCommandTest {

    private record Result(int code, String out, String err) {}

    private static Result run(String stdin, String... args) {
        InputStream prevIn = System.in;
        PrintStream prevOut = System.out;
        PrintStream prevErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream((stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = Jk.execute(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setIn(prevIn);
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
    }

    @Test
    void unknown_provider_is_a_usage_error() {
        Result r = run(null, "auth", "login", "gitfoo");
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Unknown provider");
    }

    /** Write a .git/config with the given origin URL under {@code repo}. */
    private static void gitOrigin(Path repo, String url) throws Exception {
        Path gitDir = repo.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), """
                [remote "origin"]
                    url = %s
                """.formatted(url));
    }

    @Test
    void token_auto_detects_provider_from_git_remote(@TempDir Path repo, @TempDir Path creds) throws Exception {
        // Codeberg → Gitea, which has no native CLI to shell out to, so the
        // only token source is the (empty) store: hermetic, no network.
        gitOrigin(repo, "https://codeberg.org/owner/repo.git");

        Result r = run(null, "auth", "token", "-C", repo.toString(), "--credentials-dir", creds.toString());
        // Detection worked: it knew the provider+host without us naming them.
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("Gitea/Forgejo (codeberg.org)");
    }

    @Test
    void auto_detect_fails_cleanly_outside_a_repo(@TempDir Path notARepo, @TempDir Path creds) {
        Result r = run(null, "auth", "token", "-C", notARepo.toString(), "--credentials-dir", creds.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("Could not detect a forge");
    }

    @Test
    void explicit_provider_still_overrides_detection(@TempDir Path repo, @TempDir Path creds) throws Exception {
        // Repo is on codeberg, but we ask for gitea on an explicit host.
        gitOrigin(repo, "https://codeberg.org/owner/repo.git");
        Result r = run(
                null,
                "auth",
                "token",
                "gitea",
                "--host",
                "git.example.org",
                "-C",
                repo.toString(),
                "--credentials-dir",
                creds.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("git.example.org");
    }

    @Test
    void login_with_token_then_token_round_trips(@TempDir Path dir) {
        Result login = run(
                "paste-me\n",
                "auth",
                "login",
                "gitea",
                "--host",
                "git.example.org",
                "--with-token",
                "--credentials-dir",
                dir.toString());
        assertThat(login.code()).isEqualTo(0);
        assertThat(login.out()).contains("Logged in to Gitea/Forgejo (git.example.org)");

        Result token =
                run(null, "auth", "token", "gitea", "--host", "git.example.org", "--credentials-dir", dir.toString());
        assertThat(token.code()).isEqualTo(0);
        assertThat(token.out().strip()).isEqualTo("paste-me");
    }

    @Test
    void logout_removes_the_stored_token(@TempDir Path dir) {
        run(
                "paste-me\n",
                "auth",
                "login",
                "gitea",
                "--host",
                "git.example.org",
                "--with-token",
                "--credentials-dir",
                dir.toString());

        Result logout =
                run(null, "auth", "logout", "gitea", "--host", "git.example.org", "--credentials-dir", dir.toString());
        assertThat(logout.code()).isEqualTo(0);

        Result token =
                run(null, "auth", "token", "gitea", "--host", "git.example.org", "--credentials-dir", dir.toString());
        assertThat(token.code()).isEqualTo(1);
        assertThat(token.err()).contains("not logged in");
    }

    @Test
    void blank_stdin_token_fails(@TempDir Path dir) {
        Result r = run(
                "   \n",
                "auth",
                "login",
                "gitea",
                "--host",
                "git.example.org",
                "--with-token",
                "--credentials-dir",
                dir.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("No token supplied");
    }

    @Test
    void gitea_without_host_is_a_usage_error(@TempDir Path dir) {
        Result r = run("paste-me\n", "auth", "login", "gitea", "--with-token", "--credentials-dir", dir.toString());
        assertThat(r.code()).isEqualTo(2);
        assertThat(r.err()).contains("--host");
    }

    @Test
    void bitbucket_interactive_login_directs_to_token_paste(@TempDir Path dir) {
        // Bitbucket has no device flow; without --with-token we must advise.
        Result r = run(null, "auth", "login", "bitbucket", "--credentials-dir", dir.toString());
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.err()).contains("--with-token");
    }

    @Test
    void client_id_resolves_from_config_when_env_absent(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("jk.toml");
        Files.writeString(cfg, """
                [forge.gitea]
                client-id = "gitea-from-config"

                [[forge.host]]
                name = "git.example.org"
                client-id = "host-specific-id"
                """);
        ForgeAuthConfig config = ForgeAuthConfig.loadFrom(cfg);

        // No env var → per-host override wins.
        assertThat(AuthLoginCommand.oauthClientId(ForgeKind.GITEA, "git.example.org", k -> null, config))
                .contains("host-specific-id");

        // A different host falls back to the provider default.
        assertThat(AuthLoginCommand.oauthClientId(ForgeKind.GITEA, "other.example.org", k -> null, config))
                .contains("gitea-from-config");
    }

    @Test
    void env_var_client_id_overrides_config() throws Exception {
        ForgeAuthConfig config = ForgeAuthConfig.empty();
        Map<String, String> env = Map.of("JK_GITHUB_OAUTH_CLIENT_ID", "from-env");

        assertThat(AuthLoginCommand.oauthClientId(ForgeKind.GITHUB, "github.com", env::get, config))
                .contains("from-env");
    }

    @Test
    void builtin_default_used_for_github_dot_com() {
        // No env, no config → jk's registered github.com app id.
        assertThat(AuthLoginCommand.oauthClientId(ForgeKind.GITHUB, "github.com", k -> null, ForgeAuthConfig.empty()))
                .contains("Ov23liOYrWd84ZK2Eg2n");
    }

    @Test
    void builtin_default_not_used_for_self_hosted_host() {
        // A GitHub Enterprise host can't use github.com's app — must be configured.
        assertThat(AuthLoginCommand.oauthClientId(
                        ForgeKind.GITHUB, "ghe.corp.example", k -> null, ForgeAuthConfig.empty()))
                .isEmpty();
    }

    @Test
    void client_id_empty_when_neither_env_nor_config_nor_builtin_has_it() {
        // GitLab has no built-in default yet → empty without env/config.
        assertThat(AuthLoginCommand.oauthClientId(ForgeKind.GITLAB, "gitlab.com", k -> null, ForgeAuthConfig.empty()))
                .isEmpty();
    }

    @Test
    void status_reports_not_authenticated_then_authenticated(@TempDir Path dir) {
        Result before =
                run(null, "auth", "status", "gitea", "--host", "git.example.org", "--credentials-dir", dir.toString());
        assertThat(before.code()).isEqualTo(1);
        assertThat(before.out()).contains("not authenticated");

        run(
                "paste-me\n",
                "auth",
                "login",
                "gitea",
                "--host",
                "git.example.org",
                "--with-token",
                "--credentials-dir",
                dir.toString());

        Result after =
                run(null, "auth", "status", "gitea", "--host", "git.example.org", "--credentials-dir", dir.toString());
        assertThat(after.code()).isEqualTo(0);
        assertThat(after.out()).contains("authenticated");
        // The token value itself must never be printed by status.
        assertThat(after.out()).doesNotContain("paste-me");
    }
}
