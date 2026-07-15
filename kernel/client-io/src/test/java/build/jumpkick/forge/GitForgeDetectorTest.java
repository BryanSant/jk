// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.forge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitForgeDetectorTest {

    /** Write a minimal .git/config with the given origin URL under {@code repo}. */
    private static void gitOrigin(Path repo, String url) throws Exception {
        Path gitDir = repo.resolve(".git");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("config"), """
                [core]
                    bare = false
                [remote "origin"]
                    url = %s
                    fetch = +refs/heads/*:refs/remotes/origin/*
                """.formatted(url));
    }

    @Test
    void detects_github_from_https_remote(@TempDir Path repo) throws Exception {
        gitOrigin(repo, "https://github.com/jkbuild/jk.git");
        assertThat(GitForgeDetector.detect(repo, null)).hasValueSatisfying(r -> {
            assertThat(r.kind()).isEqualTo(ForgeKind.GITHUB);
            assertThat(r.host()).isEqualTo("github.com");
        });
    }

    @Test
    void detects_github_from_scp_ssh_remote(@TempDir Path repo) throws Exception {
        gitOrigin(repo, "git@github.com:jkbuild/jk.git");
        assertThat(GitForgeDetector.detect(repo, null))
                .hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(ForgeKind.GITHUB));
    }

    @Test
    void detects_gitlab_and_codeberg(@TempDir Path repo, @TempDir Path repo2) throws Exception {
        gitOrigin(repo, "https://gitlab.com/group/proj.git");
        assertThat(GitForgeDetector.detect(repo, null))
                .hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(ForgeKind.GITLAB));

        gitOrigin(repo2, "git@codeberg.org:owner/repo.git");
        assertThat(GitForgeDetector.detect(repo2, null)).hasValueSatisfying(r -> {
            assertThat(r.kind()).isEqualTo(ForgeKind.GITEA); // Forgejo/Codeberg
            assertThat(r.host()).isEqualTo("codeberg.org");
        });
    }

    @Test
    void resolves_ssh_alias_to_real_host(@TempDir Path repo, @TempDir Path sshHome) throws Exception {
        // Remote uses an alias; ~/.ssh/config maps it to github.com.
        gitOrigin(repo, "git@work-gh:jkbuild/jk.git");
        Path sshConfig = sshHome.resolve("config");
        Files.writeString(sshConfig, """
                Host work-gh
                    HostName github.com
                    User git
                    IdentityFile ~/.ssh/id_work
                """);

        assertThat(GitForgeDetector.detect(repo, sshConfig)).hasValueSatisfying(r -> {
            assertThat(r.kind()).isEqualTo(ForgeKind.GITHUB);
            assertThat(r.host()).isEqualTo("github.com");
        });
    }

    @Test
    void unknown_self_hosted_host_is_not_detected(@TempDir Path repo) throws Exception {
        gitOrigin(repo, "https://gitlab.internal.corp/team/svc.git");
        assertThat(GitForgeDetector.detect(repo, null)).isEmpty();
    }

    @Test
    void no_git_repo_yields_empty(@TempDir Path notARepo) {
        assertThat(GitForgeDetector.detect(notARepo, null)).isEmpty();
    }

    @Test
    void walks_up_to_find_repo_root(@TempDir Path repo) throws Exception {
        gitOrigin(repo, "https://github.com/jkbuild/jk.git");
        Path nested = repo.resolve("a/b/c");
        Files.createDirectories(nested);
        assertThat(GitForgeDetector.detect(nested, null))
                .hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(ForgeKind.GITHUB));
    }

    @Test
    void honors_git_file_pointer_for_worktrees(@TempDir Path repo, @TempDir Path realGitDir) throws Exception {
        // .git is a file pointing at the real git dir (worktree/submodule form).
        Files.writeString(realGitDir.resolve("config"), """
                [remote "origin"]
                    url = https://github.com/jkbuild/jk.git
                """);
        Files.writeString(repo.resolve(".git"), "gitdir: " + realGitDir + "\n");

        assertThat(GitForgeDetector.detect(repo, null))
                .hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(ForgeKind.GITHUB));
    }
}
