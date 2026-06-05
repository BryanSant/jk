// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jk run} — project mode. {@code jk run} builds (if needed) and runs
 * the current project's main artifact, forwarding every argument to its
 * {@code main} method. File execution (.java/.kt/.kts/.jar) lives under
 * {@code jk tool run} — see {@link ToolRunCommandTest}.
 */
class RunCommandTest {

    @Test
    void runs_current_project_and_forwards_args(@TempDir Path tempDir) throws Exception {
        // Scaffold a runnable project, write source that exits with code = arg count.
        run("new",
                "--group", "com.example",
                "--name", "widget",
                "--executable",
                tempDir.toString());
        Path src = tempDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {
                        System.exit(args.length);
                    }
                }
                """);

        // No jar yet — `jk run` should auto-build then exec.
        int exit = run("run", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("home/cache").toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();

        // Args after `--` reach the main method.
        int withArgs = run("run", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("home/cache").toString(),
                "--", "a", "b", "c");
        assertThat(withArgs).isEqualTo(3);
    }

    @Test
    void project_mode_without_main_returns_usage_error(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "lib-only"
                version  = "0.1.0"
                """);
        int exit = run("run", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void no_jk_toml_returns_usage_error(@TempDir Path tempDir) {
        int exit = run("run", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    private static int run(String... args) {
        CommandLine cmd = Jk.newCommandLine();
        return cmd.execute(args);
    }
}
