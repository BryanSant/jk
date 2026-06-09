// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCommandTest {

    @Test
    void writes_pom_xml_from_build_jk(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 21

                [dependencies.main]
                jackson-databind = { group = "com.fasterxml.jackson.core", version = "=2.18.2" }
                """, StandardCharsets.UTF_8);

        int exit = run("export", "-C", tempDir.toString(), "pom.xml");
        assertThat(exit).isEqualTo(0);

        String pom = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(pom).contains("<groupId>com.example</groupId>");
        assertThat(pom).contains("<artifactId>jackson-databind</artifactId>");
        assertThat(pom).contains("<version>2.18.2</version>");
    }

    @Test
    void refuses_to_overwrite_without_force(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 21
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("pom.xml"), "<existing/>\n");

        int exit = run("export", "-C", tempDir.toString(), "pom.xml");
        assertThat(exit).isEqualTo(73);
        assertThat(Files.readString(tempDir.resolve("pom.xml"))).contains("<existing/>");
    }

    @Test
    void workspace_root_export_also_writes_each_member(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget-parent"
                version  = "1.0.0"
                jdk      = 21

                [workspace]
                members = ["core", "app"]
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("core"));
        Files.writeString(tempDir.resolve("core/jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget-core"
                version  = "1.0.0"
                jdk      = 21
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget-app"
                version  = "1.0.0"
                jdk      = 21
                """, StandardCharsets.UTF_8);

        int exit = run("export", "-C", tempDir.toString(), "pom.xml");
        assertThat(exit).isEqualTo(0);

        String rootPom = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(rootPom).contains("<packaging>pom</packaging>");
        assertThat(rootPom).contains("<module>core</module>");
        assertThat(rootPom).contains("<module>app</module>");

        assertThat(Files.readString(tempDir.resolve("core/pom.xml")))
                .contains("<artifactId>widget-core</artifactId>")
                .contains("<packaging>jar</packaging>");
        assertThat(Files.readString(tempDir.resolve("app/pom.xml")))
                .contains("<artifactId>widget-app</artifactId>");
    }

    @Test
    void missing_build_jk_returns_no_input(@TempDir Path tempDir) {
        int exit = run("export", "-C", tempDir.toString(), "pom.xml");
        assertThat(exit).isEqualTo(66);
    }

    @Test
    void gradle_target_emits_friendly_v1_1_message(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "widget"
                version  = "1.0.0"
                jdk      = 21
                """, StandardCharsets.UTF_8);

        int exit = run("export", "-C", tempDir.toString(), "build.gradle.kts");
        assertThat(exit).isEqualTo(64);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
