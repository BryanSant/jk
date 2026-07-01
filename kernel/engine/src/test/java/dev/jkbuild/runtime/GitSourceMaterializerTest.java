// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end (offline) materialization: build a local git "library" repo with a jk.toml project,
 * then clone → derive version → build → local-publish.
 */
class GitSourceMaterializerTest {

    /** A local git repo holding a trivial no-dependency jk.toml library, tagged v1.0.0. */
    private GitSource buildLibraryRepo(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("jk.toml"), """
                [project]
                group    = "com.acme"
                name     = "widgets"
                version  = "0.1.0"
                jdk      = 25
                java     = 25
                """);
        Path src = repoDir.resolve("src/main/java/acme/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package acme;
                public class Widget { public static String name() { return "widget"; } }
                """);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            var commit = git.commit()
                    .setMessage("v1")
                    .setAuthor("t", "t@e")
                    .setCommitter("t", "t@e")
                    .call();
            git.tag().setName("v1.0.0").setObjectId(commit).setAnnotated(false).call();
        }
        String url = repoDir.toUri().toString();
        return GitSource.of(url, url, new GitRefSpec.Tag("v1.0.0"));
    }

    @Test
    void materializes_a_tagged_library_into_a_local_repo(@TempDir Path tmp) throws Exception {
        GitSource source = buildLibraryRepo(tmp.resolve("lib"));

        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup buildRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        var materializer = new GitSourceMaterializer(
                tmp.resolve("git"),
                tmp.resolve("git-artifacts"),
                cas,
                buildRepos,
                Path.of(System.getProperty("java.home")),
                "test",
                new dev.jkbuild.forge.ForgeGitCredentials());

        GitSourceMaterializer.Materialized m = materializer.materialize(source);

        assertThat(m.group()).isEqualTo("com.acme");
        assertThat(m.artifact()).isEqualTo("widgets");
        assertThat(m.version()).isEqualTo("1.0.0"); // coerced from tag v1.0.0
        assertThat(m.gitInfo().rev()).hasSize(40); // resolved commit SHA
        assertThat(m.gitInfo().ref()).isEqualTo("tag=v1.0.0");

        // The file:// repo has the artifact at the standard Maven layout.
        Path repoDir = Path.of(m.repoUrl());
        Path jar = repoDir.resolve("com/acme/widgets/1.0.0/widgets-1.0.0.jar");
        Path pom = repoDir.resolve("com/acme/widgets/1.0.0/widgets-1.0.0.pom");
        assertThat(jar).exists();
        assertThat(pom).exists();
        assertThat(Files.readString(pom))
                .contains("<groupId>com.acme</groupId>")
                .contains("<version>1.0.0</version>");
    }

    @Test
    void is_idempotent_on_a_cache_hit(@TempDir Path tmp) throws Exception {
        GitSource source = buildLibraryRepo(tmp.resolve("lib"));
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup buildRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        var materializer = new GitSourceMaterializer(
                tmp.resolve("git"),
                tmp.resolve("git-artifacts"),
                cas,
                buildRepos,
                Path.of(System.getProperty("java.home")),
                "test",
                new dev.jkbuild.forge.ForgeGitCredentials());

        var first = materializer.materialize(source);
        var second = materializer.materialize(source); // cache hit — same sha/version/repo
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(second.gitInfo().rev()).isEqualTo(first.gitInfo().rev());
        assertThat(second.repoUrl()).isEqualTo(first.repoUrl());
    }
}
