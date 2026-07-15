// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.cache.Cas;
import build.jumpkick.http.Http;
import build.jumpkick.model.PathSource;
import build.jumpkick.model.RepositorySpec;
import build.jumpkick.repo.MavenRepo;
import build.jumpkick.repo.RepoGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end (offline) materialization of a local-path jk dependency: build a jk.toml library on
 * disk, then resolve → build → local-publish, keyed by a content fingerprint (no git, no network).
 */
class PathSourceMaterializerTest {

    /** Write a trivial no-dependency jk.toml library at {@code libDir}. */
    private static void writeLibrary(Path libDir, String returnValue) throws Exception {
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("jk.toml"), """
                [project]
                group   = "com.acme"
                name    = "widgets"
                version = "0.1.0"
                jdk     = 25
                java    = 25
                """);
        Path src = libDir.resolve("src/main/java/acme/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package acme;
                public class Widget { public static String name() { return "%s"; } }
                """.formatted(returnValue));
    }

    private static PathSourceMaterializer materializer(Path root, Path artifactsRoot) {
        Cas cas = new Cas(root.resolve("cas"));
        RepoGroup buildRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        return new PathSourceMaterializer(
                root, artifactsRoot, cas, buildRepos, Path.of(System.getProperty("java.home")), "test");
    }

    @Test
    void materializes_a_jk_path_library_into_a_local_repo(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var m = materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./lib"));

        assertThat(m.group()).isEqualTo("com.acme");
        assertThat(m.artifact()).isEqualTo("widgets");
        assertThat(m.version()).isEqualTo("0.1.0"); // path deps keep the declared version

        Path repoDir = Path.of(m.repoUrl());
        assertThat(repoDir.resolve("com/acme/widgets/0.1.0/widgets-0.1.0.jar")).exists();
        Path pom = repoDir.resolve("com/acme/widgets/0.1.0/widgets-0.1.0.pom");
        assertThat(pom).exists();
        assertThat(Files.readString(pom)).contains("<groupId>com.acme</groupId>");
    }

    @Test
    void is_idempotent_on_a_cache_hit(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var materializer = materializer(tmp, tmp.resolve("path-artifacts"));
        var first = materializer.materialize(new PathSource("./lib"));
        var second = materializer.materialize(new PathSource("./lib"));
        assertThat(second.repoUrl()).isEqualTo(first.repoUrl()); // same fingerprint → same repo dir
    }

    @Test
    void rebuilds_when_the_source_content_changes(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var materializer = materializer(tmp, tmp.resolve("path-artifacts"));
        var before = materializer.materialize(new PathSource("./lib"));

        writeLibrary(tmp.resolve("lib"), "gadget"); // mutate a source file
        var after = materializer.materialize(new PathSource("./lib"));

        assertThat(after.repoUrl()).isNotEqualTo(before.repoUrl()); // new fingerprint → new repo dir
        assertThat(Path.of(after.repoUrl()).resolve("com/acme/widgets/0.1.0/widgets-0.1.0.jar")).exists();
    }

    @Test
    void fails_when_the_target_directory_is_missing(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() ->
                materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./nope")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not resolve to a directory");
    }
}
