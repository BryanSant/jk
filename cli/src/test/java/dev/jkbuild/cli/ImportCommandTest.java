// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

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

        String jkBuild = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(jkBuild).contains("artifact = \"widget\"");
        assertThat(jkBuild).contains("\"com.fasterxml.jackson.core:jackson-databind:2.18.2\"");

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

        Path existing = tempDir.resolve("jk.toml");
        Files.writeString(existing, "[project]\ngroup = \"prior\"\n");

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
        Files.writeString(tempDir.resolve("jk.toml"), "[project]\ngroup = \"prior\"\n");

        int exit = run("import", "--force", pom.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(tempDir.resolve("jk.toml")))
                .contains("artifact = \"widget\"")
                .doesNotContain("\"prior\"");
    }

    @Test
    void gradle_kts_source_imports_and_writes_build_jk(@TempDir Path tempDir) throws Exception {
        Path gradle = tempDir.resolve("build.gradle.kts");
        Files.writeString(gradle, """
                plugins { id("java") }

                group = "com.example"
                version = "1.0.0"

                java { sourceCompatibility = JavaVersion.VERSION_21 }

                dependencies {
                    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
                }
                """, StandardCharsets.UTF_8);

        int exit = run("import", gradle.toString());
        assertThat(exit).isEqualTo(0);

        String jkBuild = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(jkBuild).contains("group    = \"com.example\"");
        assertThat(jkBuild).contains("jdk      = 21");
        assertThat(jkBuild).contains("\"com.fasterxml.jackson.core:jackson-databind:2.18.2\"");

        assertThat(Files.readString(tempDir.resolve("jk-import-report.md")))
                .contains("# jk import report");
    }

    @Test
    void unrecognised_source_returns_usage_error(@TempDir Path tempDir) throws Exception {
        Path foo = tempDir.resolve("foo.txt");
        Files.writeString(foo, "x");
        int exit = run("import", foo.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void missing_source_returns_no_input(@TempDir Path tempDir) {
        int exit = run("import", tempDir.resolve("missing.xml").toString());
        assertThat(exit).isEqualTo(66); // EX_NOINPUT
    }

    @Test
    void multi_module_pom_writes_root_and_child_build_jks(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("core"));
        Files.writeString(tempDir.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-core</artifactId>
                </project>
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(tempDir.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-app</artifactId>
                </project>
                """, StandardCharsets.UTF_8);

        int exit = run("import", tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(0);

        String root = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(root).contains("artifact = \"widget-parent\"");
        assertThat(root).contains("[workspace]");
        assertThat(root).contains("members = [\"core\", \"app\"]");

        assertThat(Files.readString(tempDir.resolve("core/jk.toml")))
                .contains("artifact = \"widget-core\"");
        assertThat(Files.readString(tempDir.resolve("app/jk.toml")))
                .contains("artifact = \"widget-app\"");
    }

    private static int run(String... args) {
        CommandLine cmd = Jk.newCommandLine();
        return cmd.execute(args);
    }
}
