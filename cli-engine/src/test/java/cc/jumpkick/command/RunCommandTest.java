// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk run} — project mode. {@code jk run} builds (if needed) and runs the current project's
 * main artifact, forwarding every argument to its {@code main} method. File execution
 * (.java/.kt/.kts/.jar) lives under {@code jk tool run} — see {@link ToolRunCommandTest}.
 */
class RunCommandTest {

    @Test
    void runs_current_project_and_forwards_args(@TempDir Path tempDir) throws Exception {
        // Scaffold a runnable project, write source that exits with code = arg count.
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
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
        int exit = run("run", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();

        // App args ride the `.` target (the first positional is always the target since
        // the 2026-07-09 inversion made `jk run` the universal runner).
        int withArgs = run("run", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg(), ".", "a", "b", "c");
        assertThat(withArgs).isEqualTo(3);
    }

    @Test
    void project_without_declared_main_runs_via_the_scan(@TempDir Path tempDir) throws Exception {
        // No [application] main: after the build jk scans the compiled output for the single
        // `public static void main` (spring-boot plan §3.8).
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
                tempDir.toString());
        // Strip the scaffolded main declaration but keep the [application] table.
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replaceAll("(?m)^main\\s*=.*$", ""));
        Path src = tempDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package com.example;
                public final class Main {
                    public static void main(String[] args) { System.exit(args.length); }
                }
                """);

        int exit = run("run", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg(), ".", "a");
        assertThat(exit).isEqualTo(1); // scanned main ran and saw one arg
    }

    @Test
    void project_mode_without_main_returns_data_error(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group    = "com.example"
                name     = "lib-only"
                version  = "0.1.0"
                """);
        String output =
                stripAnsi(runCapturingOutput(tempDir, exit -> assertThat(exit).isEqualTo(65))); // EX_DATAERR
        assertThat(output).contains("Failed to run").contains("No valid main method was specified or detected");
    }

    @Test
    void project_with_ambiguous_main_returns_data_error(@TempDir Path tempDir) throws Exception {
        // No [application] main + two scanned candidates: the scan can't pick one either.
        run(
                "new",
                "--group",
                "com.example",
                "--name",
                "widget",
                "--executable",
                "--layout",
                "traditional",
                tempDir.toString());
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replaceAll("(?m)^main\\s*=.*$", ""));
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {}
                }
                """);
        Files.writeString(srcDir.resolve("Other.java"), """
                package com.example;
                public final class Other {
                    public static void main(String[] args) {}
                }
                """);

        String output =
                stripAnsi(runCapturingOutput(tempDir, exit -> assertThat(exit).isEqualTo(65)));
        assertThat(output).contains("Failed to run").contains("Multiple main methods found");
    }

    @Test
    void no_jk_toml_returns_usage_error(@TempDir Path tempDir) {
        int exit = run("run", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /**
     * Runs {@code jk run -C <tempDir> --cache-dir <shared>}, capturing stdout + stderr for content
     * assertions. The pipeline-chip result line (success or failure) settles on stdout — same stream as
     * the live progress region it replaces — while ad hoc {@code CliOutput.err} messages go to
     * stderr; capture both since callers don't need to care which one a given message rides.
     */
    private static String runCapturingOutput(Path tempDir, java.util.function.IntConsumer assertExit) {
        var captured = new ByteArrayOutputStream();
        var prevOut = System.out;
        var prevErr = System.err;
        var combined = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(combined);
        System.setErr(combined);
        try {
            assertExit.accept(run("run", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg()));
        } finally {
            System.setOut(prevOut);
            System.setErr(prevErr);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
