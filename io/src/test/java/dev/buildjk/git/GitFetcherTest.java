// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.git;

import dev.buildjk.model.GitRefSpec;
import dev.buildjk.model.GitSource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFetcherTest {

    @Test
    void fetches_a_tag_into_a_checkout(@TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        Path gitRoot = tempDir.resolve("jk-git");

        GitSource source = GitSource.of(
                "file://" + upstream.workTree(),
                "file://" + upstream.workTree(),
                new GitRefSpec.Tag("v1.0.0"));

        GitFetcher.Fetched fetched = new GitFetcher(gitRoot).fetch(source);
        assertThat(fetched.sha()).isEqualTo(upstream.taggedSha());
        assertThat(fetched.checkoutPath().resolve("README.md")).exists();
        assertThat(Files.readString(fetched.checkoutPath().resolve("README.md")))
                .contains("v1.0.0");
    }

    @Test
    void second_fetch_uses_cache(@TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        Path gitRoot = tempDir.resolve("jk-git");
        GitFetcher fetcher = new GitFetcher(gitRoot);
        GitSource source = GitSource.of("file://" + upstream.workTree(),
                "file://" + upstream.workTree(),
                new GitRefSpec.Tag("v1.0.0"));

        GitFetcher.Fetched first = fetcher.fetch(source);
        long firstMtime = Files.getLastModifiedTime(first.checkoutPath()).toMillis();
        Thread.sleep(20);
        GitFetcher.Fetched second = fetcher.fetch(source);
        assertThat(second.checkoutPath()).isEqualTo(first.checkoutPath());
        assertThat(Files.getLastModifiedTime(second.checkoutPath()).toMillis())
                .isEqualTo(firstMtime);
    }

    @Test
    void rev_spec_resolves_to_an_explicit_sha(@TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitSource source = GitSource.of("file://" + upstream.workTree(),
                "file://" + upstream.workTree(),
                new GitRefSpec.Rev(upstream.taggedSha()));

        GitFetcher.Fetched fetched = new GitFetcher(tempDir.resolve("jk-git")).fetch(source);
        assertThat(fetched.sha()).isEqualTo(upstream.taggedSha());
    }

    @Test
    void verify_locked_detects_tag_rewrite(@TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        Path gitRoot = tempDir.resolve("jk-git");
        GitFetcher fetcher = new GitFetcher(gitRoot);
        GitSource source = GitSource.of("file://" + upstream.workTree(),
                "file://" + upstream.workTree(),
                new GitRefSpec.Tag("v1.0.0"));

        // Initial fetch to populate the bare clone.
        fetcher.fetch(source);
        String firstSha = upstream.taggedSha();

        // Rewrite the tag upstream to a new commit.
        String secondSha = upstream.commitAndRetag(
                "second commit\n", "v1.0.0", true);
        assertThat(secondSha).isNotEqualTo(firstSha);

        assertThatThrownBy(() -> fetcher.verifyLocked(source, firstSha))
                .isInstanceOf(GitFetcher.TagRewriteException.class)
                .hasMessageContaining(firstSha)
                .hasMessageContaining(secondSha);
    }

    @Test
    void missing_ref_yields_clear_error(@TempDir Path tempDir) throws Exception {
        UpstreamFixture upstream = setupUpstream(tempDir.resolve("upstream"), "v1.0.0");
        GitSource source = GitSource.of("file://" + upstream.workTree(),
                "file://" + upstream.workTree(),
                new GitRefSpec.Tag("v99.0.0"));

        assertThatThrownBy(() -> new GitFetcher(tempDir.resolve("jk-git")).fetch(source))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("v99.0.0");
    }

    // --- fixture helpers --------------------------------------------------

    private static UpstreamFixture setupUpstream(Path workDir, String tagName) throws Exception {
        Files.createDirectories(workDir);
        try (Git git = Git.init().setDirectory(workDir.toFile()).call()) {
            Path readme = workDir.resolve("README.md");
            Files.writeString(readme, "Initial " + tagName + "\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            RevCommit commit = git.commit()
                    .setMessage("Initial commit").setSign(false)
                    .setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com").call();
            git.tag().setName(tagName).setObjectId(commit).setSigned(false).call();
            return new UpstreamFixture(workDir, commit.name());
        }
    }

    record UpstreamFixture(Path workTree, String taggedSha) {
        String commitAndRetag(String content, String tagName, boolean force) throws Exception {
            try (Git git = Git.open(workTree.toFile())) {
                Files.writeString(workTree.resolve("README.md"), content);
                git.add().addFilepattern("README.md").call();
                RevCommit commit = git.commit()
                        .setMessage("update").setSign(false)
                        .setAuthor("test", "test@example.com")
                        .setCommitter("test", "test@example.com").call();
                git.tag().setName(tagName).setObjectId(commit)
                        .setForceUpdate(force).setSigned(false).call();
                return commit.name();
            }
        }
    }
}
