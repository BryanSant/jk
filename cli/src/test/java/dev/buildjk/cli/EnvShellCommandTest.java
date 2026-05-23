// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class EnvShellCommandTest {

    @Test
    void env_prints_exports_for_pinned_jdk(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        Path home = jdks.resolve("21.0.5-tem-x64-linux");
        Files.createDirectories(home.resolve("bin"));

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "21.0.5-tem-x64-linux");

        String stdout = captureStdout(() -> run(
                "env",
                "-C", project.toString(),
                "--jdks-dir", jdks.toString()));

        assertThat(stdout).contains("export JAVA_HOME=" + home);
        assertThat(stdout).contains("export PATH=" + home.resolve("bin") + ":$PATH");
    }

    @Test
    void env_errors_with_no_pin(@TempDir Path tempDir) {
        Path project = tempDir.resolve("project");
        try { Files.createDirectories(project); } catch (IOException e) { throw new RuntimeException(e); }

        int exit = run("env",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void env_shell_quotes_paths_with_spaces(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("home dir/jdks");
        Path home = jdks.resolve("21.0.5-tem-x64-linux");
        Files.createDirectories(home.resolve("bin"));

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jk-version"), "21.0.5-tem-x64-linux");

        String stdout = captureStdout(() -> run(
                "env",
                "-C", project.toString(),
                "--jdks-dir", jdks.toString()));

        assertThat(stdout).contains("export JAVA_HOME='");
    }

    @Test
    void shell_errors_with_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        int exit = run("shell",
                "-C", project.toString(),
                "--jdks-dir", tempDir.resolve("jdks").toString());
        assertThat(exit).isEqualTo(2);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static String captureStdout(IntSupplier body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer));
        try {
            body.getAsInt();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
