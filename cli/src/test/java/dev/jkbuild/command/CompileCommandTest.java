// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompileCommandTest {

    @Test
    void check_passes_for_clean_source(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);

        int exit = run("check", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void check_fails_on_syntax_error(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        Path src = tempDir.resolve("src/main/java/example/Broken.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Broken { void f(   // missing\n");

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(stderr));
        int exit;
        try {
            exit = run("check", "-C", tempDir.toString(),
                    "--cache-dir", tempDir.resolve("cache").toString());
        } finally {
            System.setErr(originalErr);
        }

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("error:");
    }

    @Test
    void check_reports_no_sources_as_clean(@TempDir Path tempDir) throws Exception {
        scaffold(tempDir);
        // No src/ at all — empty project.
        int exit = run("check", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void check_without_lockfile_errors(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nname = \"x\"\nversion = \"0.1\"\n");
        // No jk.lock.
        int exit = run("check", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    private static void scaffold(Path dir) throws IOException {
        run("new", dir.toString());
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
