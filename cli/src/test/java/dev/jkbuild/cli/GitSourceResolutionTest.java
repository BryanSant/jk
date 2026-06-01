// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.model.Scope;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end (offline) wiring of a git-source dependency through the resolver
 * (docs/git-source-deps.md): a consuming project declares a {@code git} dep on a
 * local tagged library; {@link GitSourceResolution#prepare} materializes it,
 * rewrites it to an exact coordinate pin, and augments the repo group; the real
 * {@link LockOrchestrator} solves it; {@link GitSourceResolution#stamp} records
 * the git provenance on the locked package.
 */
class GitSourceResolutionTest {

    /** A local git repo holding a trivial no-dependency jk.toml library, tagged v1.0.0. */
    private GitSource buildLibraryRepo(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        Files.writeString(repoDir.resolve("jk.toml"), """
                [project]
                group    = "com.acme"
                artifact = "widgets"
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
            var commit = git.commit().setMessage("v1").setAuthor("t", "t@e").setCommitter("t", "t@e").call();
            git.tag().setName("v1.0.0").setObjectId(commit).setAnnotated(false).call();
        }
        String url = repoDir.toUri().toString();
        return GitSource.of(url, url, new GitRefSpec.Tag("v1.0.0"));
    }

    /** A consuming project with a single git dependency on the library. */
    private JkBuild consumer(GitSource lib) {
        JkBuild.Project project = new JkBuild.Project("com.example", "app", "0.1.0", 25);
        Dependency git = Dependency.git("com.acme:widgets", lib);
        JkBuild.Dependencies deps = new JkBuild.Dependencies(Map.of(Scope.MAIN, List.of(git)));
        return new JkBuild(project, deps);
    }

    @Test
    void materializes_pins_and_stamps_a_git_dependency(@TempDir Path tmp) throws Exception {
        GitSource lib = buildLibraryRepo(tmp.resolve("lib"));
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup baseRepos = RepoGroup.of(new MavenRepo(
                "central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));

        // Materialize + rewrite git deps to coordinate pins.
        GitSourceResolution.Prepared prep = GitSourceResolution.prepare(
                consumer(lib), baseRepos, cas,
                Path.of(System.getProperty("java.home")), "test");

        // The git dep is gone; a pinned com.acme:widgets:1.0.0 coordinate replaces it.
        List<Dependency> mainDeps = prep.project().dependencies().of(Scope.MAIN);
        assertThat(mainDeps).hasSize(1);
        Dependency pinned = mainDeps.get(0);
        assertThat(pinned.isGit()).isFalse();
        assertThat(pinned.module()).isEqualTo("com.acme:widgets");
        assertThat(pinned.version().raw()).isEqualTo("=1.0.0");
        // A file:// repo for the built artifact was prepended to the group.
        assertThat(prep.repos().repos()).hasSizeGreaterThan(baseRepos.repos().size());
        assertThat(prep.repos().repos().get(0).baseUrl().getScheme()).isEqualTo("file");

        // The real solver resolves the pin from the local file:// repo (offline).
        Lockfile lock = new LockOrchestrator(prep.repos())
                .lock(prep.project(), "test", List.of(), true);
        lock = GitSourceResolution.stamp(lock, prep.gitInfoByKey());

        Lockfile.Package widgets = lock.packages().stream()
                .filter(p -> p.name().equals("com.acme:widgets"))
                .findFirst().orElseThrow();
        assertThat(widgets.version()).isEqualTo("1.0.0");
        // Git provenance is stamped onto the locked package.
        assertThat(widgets.git()).isNotNull();
        assertThat(widgets.git().rev()).hasSize(40);
        assertThat(widgets.git().ref()).isEqualTo("tag=v1.0.0");
        assertThat(widgets.git().url()).isEqualTo(lib.canonicalUrl());
    }

    @Test
    void relocking_detects_a_force_moved_tag(@TempDir Path tmp) throws Exception {
        Path libDir = tmp.resolve("lib");
        GitSource lib = buildLibraryRepo(libDir);
        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup baseRepos = RepoGroup.of(new MavenRepo(
                "central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        Path javaHome = Path.of(System.getProperty("java.home"));

        // First lock: capture the immutable-ref provenance.
        GitSourceResolution.Prepared prep = GitSourceResolution.prepare(
                consumer(lib), baseRepos, cas, javaHome, "test");
        Lockfile lock = GitSourceResolution.stamp(
                new LockOrchestrator(prep.repos()).lock(prep.project(), "test", List.of(), true),
                prep.gitInfoByKey());
        Map<String, String> lockedShas = GitSourceResolution.lockedImmutableShas(lock);
        assertThat(lockedShas).isNotEmpty();

        // Force-move v1.0.0 to a new commit, simulating an upstream tag rewrite.
        try (Git git = Git.open(libDir.toFile())) {
            Files.writeString(libDir.resolve("src/main/java/acme/Widget.java"), """
                    package acme;
                    public class Widget { public static String name() { return "tampered"; } }
                    """);
            git.add().addFilepattern(".").call();
            var c2 = git.commit().setMessage("rewrite").setAuthor("t", "t@e").setCommitter("t", "t@e").call();
            git.tag().setName("v1.0.0").setObjectId(c2).setAnnotated(false).setForceUpdate(true).call();
        }

        // Re-locking against the recorded SHA must fail loudly.
        assertThatThrownBy(() -> GitSourceResolution.prepare(
                consumer(lib), baseRepos, cas, javaHome, "test", lockedShas))
                .isInstanceOf(GitFetcher.TagRewriteException.class)
                .hasMessageContaining("rewrite detected");
    }
}
