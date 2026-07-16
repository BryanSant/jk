// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarInputStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceProjectBuilderTest {

    @Test
    void jk_target_builds_jar_and_pom_from_the_project_coordinate(@TempDir Path dir) throws Exception {
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

        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        Path javaHome = Path.of(System.getProperty("java.home"));

        // versionOverride replaces the jk.toml version (git deps pass their ref-derived version).
        String version = "1.2.3-20260601.134752-3f2a9c1b4d5e";
        SourceProjectBuilder.Built built = SourceProjectBuilder.build(dir, version, javaHome, cas, repos, "test");

        assertThat(built.group()).isEqualTo("com.example");
        assertThat(built.artifact()).isEqualTo("widgets");
        assertThat(built.version()).isEqualTo(version);
        assertThat(built.jar()).isRegularFile();
        assertThat(jarEntryNames(built.jar())).contains("example/Hello.class");
        assertThat(built.pomXml())
                .contains("<groupId>com.example</groupId>")
                .contains("<artifactId>widgets</artifactId>")
                .contains("<version>" + version + "</version>")
                .doesNotContain("<version>0.1.0</version>");
    }

    @Test
    void jk_target_uses_the_project_version_when_no_override(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group   = "com.example"
                name    = "lib"
                version = "2.5.0"
                jdk     = 25
                java    = 25
                """);
        Path src = dir.resolve("src/main/java/example/A.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class A {}");

        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        SourceProjectBuilder.Built built =
                SourceProjectBuilder.build(dir, null, Path.of(System.getProperty("java.home")), cas, repos, "test");

        assertThat(built.version()).isEqualTo("2.5.0");
    }

    @Test
    void unsupported_target_fails_fast(@TempDir Path dir) {
        Cas cas = new Cas(dir.resolve("cache"));
        RepoGroup repos = RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        assertThatThrownBy(() ->
                SourceProjectBuilder.build(dir, null, Path.of(System.getProperty("java.home")), cas, repos, "test"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("no jk.toml, build.gradle");
    }

    /** Pure foreign-build helpers — exercised without invoking a real Gradle/Maven. */
    @Nested
    class ForeignHelpers {

        @Test
        void parses_group_and_version_from_gradle_properties() {
            String out = """
                    ------------------------------------------------------------
                    Root project 'demo'
                    ------------------------------------------------------------

                    allprojects: [root project 'demo']
                    group: com.example.demo
                    name: demo
                    version: 3.1.4
                    """;
            assertThat(SourceProjectBuilder.gradleProperty(out, "group")).isEqualTo("com.example.demo");
            assertThat(SourceProjectBuilder.gradleProperty(out, "version")).isEqualTo("3.1.4");
        }

        @Test
        void treats_unspecified_gradle_property_as_absent() {
            String out = "group: \nversion: unspecified\n";
            assertThat(SourceProjectBuilder.gradleProperty(out, "group")).isNull();
            assertThat(SourceProjectBuilder.gradleProperty(out, "version")).isNull();
        }

        @Test
        void recovers_the_archive_base_name_from_a_jar_filename() {
            assertThat(SourceProjectBuilder.artifactFromJar("demo-3.1.4.jar", "3.1.4")).isEqualTo("demo");
            assertThat(SourceProjectBuilder.artifactFromJar("demo.jar", "3.1.4")).isEqualTo("demo");
        }

        @Test
        void reads_maven_gav_with_parent_inheritance(@TempDir Path dir) throws Exception {
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <parent>
                        <groupId>com.example.parent</groupId>
                        <version>9.9.9</version>
                      </parent>
                      <artifactId>child</artifactId>
                    </project>
                    """);
            SourceProjectBuilder.Gav gav = SourceProjectBuilder.parseMavenGav(pom);
            assertThat(gav.group()).isEqualTo("com.example.parent");
            assertThat(gav.artifact()).isEqualTo("child");
            assertThat(gav.version()).isEqualTo("9.9.9");
        }

        @Test
        void rejects_a_maven_version_property_placeholder(@TempDir Path dir) throws Exception {
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, """
                    <project>
                      <groupId>com.example</groupId>
                      <artifactId>demo</artifactId>
                      <version>${revision}</version>
                    </project>
                    """);
            assertThatThrownBy(() -> SourceProjectBuilder.parseMavenGav(pom))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("unresolved property");
        }

        @Test
        void selects_the_single_main_jar_ignoring_classifiers(@TempDir Path dir) throws Exception {
            Path libs = dir.resolve("build/libs");
            Files.createDirectories(libs);
            Files.writeString(libs.resolve("demo-1.0.jar"), "x");
            Files.writeString(libs.resolve("demo-1.0-sources.jar"), "x");
            Files.writeString(libs.resolve("demo-1.0-javadoc.jar"), "x");
            Files.writeString(libs.resolve("demo-1.0-plain.jar"), "x");
            assertThat(SourceProjectBuilder.selectMainJar(libs, dir).getFileName().toString())
                    .isEqualTo("demo-1.0.jar");
        }

        @Test
        void fails_on_ambiguous_build_output(@TempDir Path dir) throws Exception {
            Path libs = dir.resolve("build/libs");
            Files.createDirectories(libs);
            Files.writeString(libs.resolve("one-1.0.jar"), "x");
            Files.writeString(libs.resolve("two-1.0.jar"), "x");
            assertThatThrownBy(() -> SourceProjectBuilder.selectMainJar(libs, dir))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("ambiguous");
        }

        @Test
        void fails_when_no_jar_was_produced(@TempDir Path dir) throws Exception {
            Path libs = dir.resolve("build/libs");
            Files.createDirectories(libs);
            assertThatThrownBy(() -> SourceProjectBuilder.selectMainJar(libs, dir))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("no jar");
        }

        @Test
        void prefers_an_executable_wrapper_over_path(@TempDir Path dir) throws Exception {
            Path wrapper = dir.resolve("gradlew");
            Files.writeString(wrapper, "#!/bin/sh\n");
            wrapper.toFile().setExecutable(true);
            assertThat(SourceProjectBuilder.resolveTool(dir, "gradlew", "gradle"))
                    .isEqualTo(wrapper.toAbsolutePath().toString());
        }

        @Test
        void rejects_a_non_executable_wrapper(@TempDir Path dir) throws Exception {
            Path wrapper = dir.resolve("gradlew");
            Files.writeString(wrapper, "#!/bin/sh\n");
            wrapper.toFile().setExecutable(false);
            assertThatThrownBy(() -> SourceProjectBuilder.resolveTool(dir, "gradlew", "gradle"))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("not executable");
        }

        @Test
        void synthesizes_a_gav_only_pom() {
            String pom = SourceProjectBuilder.leafPom("com.example", "demo", "1.0");
            assertThat(pom)
                    .contains("<groupId>com.example</groupId>")
                    .contains("<artifactId>demo</artifactId>")
                    .contains("<version>1.0</version>")
                    .doesNotContain("<dependencies>");
        }
    }

    private static List<String> jarEntryNames(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            for (var e = in.getNextJarEntry(); e != null; e = in.getNextJarEntry()) {
                names.add(e.getName());
            }
        }
        return names;
    }
}
