// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.git.GitBackendsTestSupport.BackendFactory;
import build.jumpkick.model.GitRefSpec;
import build.jumpkick.model.GitSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code listRefs} (ls-remote) enumerates a remote's tags + HEAD without cloning. */
class GitBackendListRefsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("build.jumpkick.git.GitBackendsTestSupport#backends")
    void lists_all_tags_and_head_without_cloning(String name, BackendFactory factory, @TempDir Path tempDir)
            throws Exception {
        Path work = tempDir.resolve("upstream");
        Files.createDirectories(work);
        String head;
        try (Git git = Git.init().setDirectory(work.toFile()).call()) {
            Files.writeString(work.resolve("README.md"), "hi\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            RevCommit c1 = commit(git, "one");
            git.tag().setName("v1.0.0").setObjectId(c1).setSigned(false).call();
            git.tag().setName("v1.1.0").setObjectId(c1).setSigned(false).call();
            Files.writeString(work.resolve("README.md"), "bye\n", StandardCharsets.UTF_8);
            git.add().addFilepattern("README.md").call();
            RevCommit c2 = commit(git, "two");
            git.tag().setName("v2.0.0-rc1").setObjectId(c2).setSigned(false).call();
            head = c2.name();
        }

        GitSource source = GitSource.of("file://" + work, "file://" + work, new GitRefSpec.Tag("v1.0.0"));
        Path gitRoot = tempDir.resolve("jk-git");
        GitFetcher.RemoteRefs refs = factory.create(gitRoot).listRefs(source);

        assertThat(refs.tags()).containsExactlyInAnyOrder("v1.0.0", "v1.1.0", "v2.0.0-rc1");
        assertThat(refs.headSha()).isEqualTo(head);
        // No bare clone was created — ls-remote does not populate the cache dir.
        assertThat(Files.exists(gitRoot.resolve("db"))).isFalse();
    }

    private static RevCommit commit(Git git, String msg) throws Exception {
        return git.commit()
                .setMessage(msg)
                .setSign(false)
                .setAuthor("t", "t@example.com")
                .setCommitter("t", "t@example.com")
                .call();
    }
}
