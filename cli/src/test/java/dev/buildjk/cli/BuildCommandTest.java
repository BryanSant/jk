// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class BuildCommandTest {

    @Test
    void builds_jar_from_main_sources(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());

        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static String greet() { return "hi"; }
                }
                """);

        int exit = run("build", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Path jar = tempDir.resolve("target/widget-0.1.0.jar");
        assertThat(jar).exists();

        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getJarEntry("example/Hello.class")).isNotNull();
        }
    }

    @Test
    void copies_resources_into_jar(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path res = tempDir.resolve("src/main/resources/application.properties");
        Files.createDirectories(res.getParent());
        Files.writeString(res, "name=widget\n");

        int exit = run("build", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        try (JarFile jf = new JarFile(tempDir.resolve("target/widget-0.1.0.jar").toFile())) {
            assertThat(jf.getJarEntry("application.properties")).isNotNull();
            String content = new String(
                    jf.getInputStream(jf.getJarEntry("application.properties")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(content).contains("name=widget");
        }
    }

    @Test
    void build_fails_on_syntax_error(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/Bad.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "public class Bad { void f(   // missing\n");

        int exit = run("build", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void build_without_lockfile_errors(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nartifact = \"x\"\nversion = \"0.1\"\n");
        int exit = run("build", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void build_with_no_sources_still_produces_jar(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "empty", tempDir.toString());
        int exit = run("build", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target/empty-0.1.0.jar")).exists();
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
