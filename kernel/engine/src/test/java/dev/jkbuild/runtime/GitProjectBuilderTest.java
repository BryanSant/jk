// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.RepositorySpec;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitProjectBuilderTest {

    @Test
    void builds_jar_and_pom_with_the_target_coordinate(@TempDir Path dir) throws Exception {
        // A trivial no-dependency jk.toml project (offline: resolution finds nothing to fetch).
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widgets"
                version  = "0.1.0"
                jdk      = 25
                java     = 25
                """);
        Path src = dir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String hi() { return "hi"; } }
                """);

        JkBuild project = JkBuildParser.parse(Files.readString(dir.resolve("jk.toml")));
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        Path javaHome = Path.of(System.getProperty("java.home"));

        // Build under an overridden coordinate + git-derived version.
        String version = "1.2.3-20260601.134752-3f2a9c1b4d5e";
        GitProjectBuilder.Built built =
                GitProjectBuilder.build(dir, project, "com.acme", "widgets", version, javaHome, cas, repos, "test");

        assertThat(built.jar()).isNotEmpty();
        assertThat(jarEntryNames(built.jar())).contains("example/Hello.class");

        // The POM must carry the overridden coordinate and the git-derived version
        // (so it matches where the artifact is published), not the repo's 0.1.0.
        assertThat(built.pomXml())
                .contains("<groupId>com.acme</groupId>")
                .contains("<artifactId>widgets</artifactId>")
                .contains("<version>" + version + "</version>")
                .doesNotContain("<version>0.1.0</version>");
    }

    private static List<String> jarEntryNames(byte[] jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (var e = in.getNextJarEntry(); e != null; e = in.getNextJarEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
