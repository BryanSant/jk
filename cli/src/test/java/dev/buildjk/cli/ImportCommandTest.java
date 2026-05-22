// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImportCommandTest {

    @Test
    void writes_build_jk_and_report(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.fasterxml.jackson.core</groupId>
                      <artifactId>jackson-databind</artifactId>
                      <version>2.18.2</version>
                    </dependency>
                  </dependencies>
                </project>
                """, StandardCharsets.UTF_8);

        int exit = run("import", pom.toString());
        assertThat(exit).isEqualTo(0);

        String buildJk = Files.readString(tempDir.resolve("build.jk"));
        assertThat(buildJk).contains("artifact = \"widget\"");
        assertThat(buildJk).contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"=2.18.2\"");

        String report = Files.readString(tempDir.resolve("jk-import-report.md"));
        assertThat(report).contains("# jk import report");
        assertThat(report).contains("Import was lossless");
    }

    @Test
    void refuses_to_overwrite_without_force(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);

        Path existing = tempDir.resolve("build.jk");
        Files.writeString(existing, "project { group = \"prior\" }\n");

        int exit = run("import", pom.toString());
        assertThat(exit).isEqualTo(73); // EX_CANTCREAT
        assertThat(Files.readString(existing)).contains("\"prior\"");
    }

    @Test
    void force_overwrites_existing_build_jk(@TempDir Path tempDir) throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("build.jk"), "project { group = \"prior\" }\n");

        int exit = run("import", "--force", pom.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("build.jk")))
                .contains("artifact = \"widget\"")
                .doesNotContain("\"prior\"");
    }

    @Test
    void gradle_source_emits_friendly_not_yet_message(@TempDir Path tempDir) throws Exception {
        Path gradle = tempDir.resolve("build.gradle.kts");
        Files.writeString(gradle, "// placeholder\n");

        int exit = run("import", gradle.toString());
        assertThat(exit).isEqualTo(64); // EX_USAGE
    }

    @Test
    void missing_source_returns_no_input(@TempDir Path tempDir) {
        int exit = run("import", tempDir.resolve("missing.xml").toString());
        assertThat(exit).isEqualTo(66); // EX_NOINPUT
    }

    private static int run(String... args) {
        CommandLine cmd = Jk.newCommandLine();
        return cmd.execute(args);
    }
}
