// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link GitFetcher#resolveRef} against a real local git repo built
 * with jgit — fully offline (clone over a {@code file://} URL).
 */
class GitFetcherResolveRefTest {

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
            RevCommit c1 = git.commit().setMessage("one").setAuthor(tagAuthor).setCommitter(tagAuthor).call();
            git.tag().setName("v1.2.3").setObjectId(c1).setAnnotated(false).call();

            Files.writeString(repoDir.resolve("b.txt"), "two");
            git.add().addFilepattern("b.txt").call();
            PersonIdent headAuthor = new PersonIdent("t", "t@e", HEAD_TIME, ZoneOffset.UTC);
            RevCommit c2 = git.commit().setMessage("two").setAuthor(headAuthor).setCommitter(headAuthor).call();

            String url = repoDir.toUri().toString();
            return new Repo(GitSource.of(url, url, new GitRefSpec.Tag("v1.2.3")), c1.getName(), c2.getName());
        }
    }

    @Test
    void resolves_a_tag_to_its_commit_and_describes_it(@TempDir Path tmp) throws Exception {
        Repo repo = buildRepo(tmp.resolve("src"));
        GitFetcher fetcher = new GitFetcher(tmp.resolve("gitcache"));

        GitFetcher.RefInfo info = fetcher.resolveRef(repo.source());
        assertThat(info.sha()).isEqualTo(repo.tagSha());
        assertThat(info.commitTime()).isEqualTo(TAG_TIME);
        assertThat(info.nearestTag()).contains("v1.2.3");
    }

    @Test
    void untagged_commit_reports_nearest_tag_and_commit_time(@TempDir Path tmp) throws Exception {
        Repo repo = buildRepo(tmp.resolve("src"));
        GitFetcher fetcher = new GitFetcher(tmp.resolve("gitcache"));

        String url = repo.source().canonicalUrl();
        GitSource headRev = GitSource.of(url, url, new GitRefSpec.Rev(repo.headSha()));
        GitFetcher.RefInfo info = fetcher.resolveRef(headRev);

        assertThat(info.sha()).isEqualTo(repo.headSha());
        assertThat(info.commitTime()).isEqualTo(HEAD_TIME);
        // describe from the second commit → "v1.2.3-1-g<sha>" → stripped to the tag.
        assertThat(info.nearestTag()).contains("v1.2.3");
    }
}
