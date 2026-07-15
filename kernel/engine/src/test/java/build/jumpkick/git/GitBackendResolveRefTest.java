// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.git;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.git.GitBackendsTestSupport.BackendFactory;
import build.jumpkick.model.GitRefSpec;
import build.jumpkick.model.GitSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@code resolveRef} against a real local git repo built with jgit — fully offline (clone
 * over a {@code file://} URL) — for every available {@link GitBackend}. This is the CLI-vs-JGit
 * parity check for commit time and nearest-tag describe.
 */
class GitBackendResolveRefTest {

    private static final Instant TAG_TIME = Instant.parse("2026-05-01T09:00:00Z");
    private static final Instant HEAD_TIME = Instant.parse("2026-06-01T13:47:52Z");

    /** Build a repo: commit (tagged v1.2.3) then a second commit on the default branch. */
    private record Repo(GitSource source, String tagSha, String headSha) {}

    private Repo buildRepo(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.writeString(repoDir.resolve("a.txt"), "one");
            git.add().addFilepattern("a.txt").call();
            PersonIdent tagAuthor = new PersonIdent("t", "t@e", TAG_TIME, ZoneOffset.UTC);
            RevCommit c1 = git.commit()
                    .setMessage("one")
                    .setAuthor(tagAuthor)
                    .setCommitter(tagAuthor)
                    .call();
            git.tag().setName("v1.2.3").setObjectId(c1).setAnnotated(false).call();

            Files.writeString(repoDir.resolve("b.txt"), "two");
            git.add().addFilepattern("b.txt").call();
            PersonIdent headAuthor = new PersonIdent("t", "t@e", HEAD_TIME, ZoneOffset.UTC);
            RevCommit c2 = git.commit()
                    .setMessage("two")
                    .setAuthor(headAuthor)
                    .setCommitter(headAuthor)
                    .call();

            String url = repoDir.toUri().toString();
            return new Repo(GitSource.of(url, url, new GitRefSpec.Tag("v1.2.3")), c1.getName(), c2.getName());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("build.jumpkick.git.GitBackendsTestSupport#backends")
    void resolves_a_tag_to_its_commit_and_describes_it(String name, BackendFactory factory, @TempDir Path tmp)
            throws Exception {
        Repo repo = buildRepo(tmp.resolve("src"));
        GitBackend backend = factory.create(tmp.resolve("gitcache"));

        GitFetcher.RefInfo info = backend.resolveRef(repo.source());
        assertThat(info.sha()).isEqualTo(repo.tagSha());
        assertThat(info.commitTime()).isEqualTo(TAG_TIME);
        assertThat(info.nearestTag()).contains("v1.2.3");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("build.jumpkick.git.GitBackendsTestSupport#backends")
    void untagged_commit_reports_nearest_tag_and_commit_time(String name, BackendFactory factory, @TempDir Path tmp)
            throws Exception {
        Repo repo = buildRepo(tmp.resolve("src"));
        GitBackend backend = factory.create(tmp.resolve("gitcache"));

        String url = repo.source().canonicalUrl();
        GitSource headRev = GitSource.of(url, url, new GitRefSpec.Rev(repo.headSha()));
        GitFetcher.RefInfo info = backend.resolveRef(headRev);

        assertThat(info.sha()).isEqualTo(repo.headSha());
        assertThat(info.commitTime()).isEqualTo(HEAD_TIME);
        // describe from the second commit → "v1.2.3-1-g<sha>" → stripped to the tag.
        assertThat(info.nearestTag()).contains("v1.2.3");
    }
}
