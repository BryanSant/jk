// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import build.jumpkick.forge.ForgeGitCredentials;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** {@code JK_GIT_BACKEND} selection semantics in {@link GitFetcher#select}. */
class GitFetcherBackendSelectionTest {

    private static final Path ROOT = Path.of("/tmp/jk-git-select");
    private static final ForgeGitCredentials CREDS = new ForgeGitCredentials();

    @Test
    void jgit_is_forced_and_case_insensitive() {
        assertThat(GitFetcher.select("jgit", ROOT, CREDS)).isInstanceOf(JGitExtension.class);
        assertThat(GitFetcher.select("JGit", ROOT, CREDS)).isInstanceOf(JGitExtension.class);
    }

    @Test
    void invalid_value_is_rejected() {
        assertThatThrownBy(() -> GitFetcher.select("bogus", ROOT, CREDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JK_GIT_BACKEND");
    }

    @Test
    void auto_prefers_cli_when_git_is_present() {
        assumeTrue(GitCliExtension.detect().isPresent(), "requires a git command on PATH");
        assertThat(GitFetcher.select("auto", ROOT, CREDS)).isInstanceOf(GitCliExtension.class);
        assertThat(GitFetcher.select(null, ROOT, CREDS)).isInstanceOf(GitCliExtension.class);
        assertThat(GitFetcher.select("", ROOT, CREDS)).isInstanceOf(GitCliExtension.class);
    }

    @Test
    void cli_is_forced_when_git_is_present() {
        assumeTrue(GitCliExtension.detect().isPresent(), "requires a git command on PATH");
        assertThat(GitFetcher.select("cli", ROOT, CREDS)).isInstanceOf(GitCliExtension.class);
    }
}
