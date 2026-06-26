// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for {@code jk export <gradle|maven|idea>}: the in-process
 * exporters write runnable build files for a single project and a workspace,
 * and the overwrite guard / parent-usage behavior holds.
 */
class ExportCommandTest {

    private static void writeApp(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "app"
                version = "1.2.3"
                jdk  = 21
                java = 21
                main = "com.example.Main"

                [dependencies.main]
                guava = { group = "com.google.guava", name = "guava", version = "=33.0.0-jre" }
                """);
    }

    @Test
    void export_gradle_writes_settings_and_build(@TempDir Path tmp) throws IOException {
        writeApp(tmp);

        int exit = Jk.execute(new String[] {"export", "gradle", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(0);

        assertThat(tmp.resolve("settings.gradle.kts")).exists();
        String build = Files.readString(tmp.resolve("build.gradle.kts"));
        assertThat(build).contains("application");
        assertThat(build).contains("mainClass = \"com.example.Main\"");
        assertThat(build).contains("languageVersion = JavaLanguageVersion.of(21)");
        assertThat(build).contains("implementation(\"com.google.guava:guava:33.0.0-jre\")");
        assertThat(Files.readString(tmp.resolve("settings.gradle.kts"))).contains("foojay-resolver-convention");
    }

    @Test
    void export_maven_writes_pom(@TempDir Path tmp) throws IOException {
        writeApp(tmp);

        int exit = Jk.execute(new String[] {"export", "maven", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(0);

        String pom = Files.readString(tmp.resolve("pom.xml"));
        assertThat(pom).contains("<artifactId>app</artifactId>");
        assertThat(pom).contains("<artifactId>guava</artifactId>");
        assertThat(pom).contains("<artifactId>toolchains-maven-plugin</artifactId>");
    }

    @Test
    void pom_alias_works(@TempDir Path tmp) throws IOException {
        writeApp(tmp);
        int exit = Jk.execute(new String[] {"export", "pom", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(0);
        assertThat(tmp.resolve("pom.xml")).exists();
    }

    @Test
    void overwrite_guard_blocks_then_force_allows(@TempDir Path tmp) throws IOException {
        writeApp(tmp);

        assertThat(Jk.execute(new String[] {"export", "maven", "-C", tmp.toString()}))
                .isEqualTo(0);
        // Second run without --force must refuse.
        assertThat(Jk.execute(new String[] {"export", "maven", "-C", tmp.toString()}))
                .isNotEqualTo(0);
        // With --force it overwrites.
        assertThat(Jk.execute(new String[] {"export", "maven", "--force", "-C", tmp.toString()}))
                .isEqualTo(0);
    }

    @Test
    void parent_without_subcommand_is_usage_error(@TempDir Path tmp) throws IOException {
        writeApp(tmp);
        int exit = Jk.execute(new String[] {"export", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void missing_jk_toml_returns_no_input(@TempDir Path tmp) {
        int exit = Jk.execute(new String[] {"export", "maven", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void export_maven_workspace_writes_root_and_module_poms(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk  = 21
                java = 21

                [workspace]
                modules = ["mod-a"]
                """);
        Path modA = tmp.resolve("mod-a");
        Files.createDirectories(modA);
        Files.writeString(modA.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "mod-a"
                version = "1.0.0"
                jdk  = 21
                java = 21
                """);

        int exit = Jk.execute(new String[] {"export", "maven", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(0);

        assertThat(Files.readString(tmp.resolve("pom.xml")))
                .contains("<packaging>pom</packaging>")
                .contains("<module>mod-a</module>");
        assertThat(Files.readString(modA.resolve("pom.xml")))
                .contains("<artifactId>mod-a</artifactId>")
                .contains("<packaging>jar</packaging>");
    }

    @Test
    void export_gradle_workspace_includes_modules(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp);
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "root"
                version = "1.0.0"
                jdk  = 21
                java = 21

                [workspace]
                modules = ["mod-a"]
                """);
        Path modA = tmp.resolve("mod-a");
        Files.createDirectories(modA);
        Files.writeString(modA.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "mod-a"
                version = "1.0.0"
                jdk  = 21
                java = 21
                """);

        int exit = Jk.execute(new String[] {"export", "gradle", "-C", tmp.toString()});
        assertThat(exit).isEqualTo(0);

        assertThat(Files.readString(tmp.resolve("settings.gradle.kts"))).contains("include(\":mod-a\")");
        assertThat(modA.resolve("build.gradle.kts")).exists();
    }
}
