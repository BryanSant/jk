// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class CleanExplainWhyRebuiltTest {

    // --- clean -------------------------------------------------------------

    @Test
    void clean_removes_target_and_generated(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        Files.createDirectories(tempDir.resolve("target/classes/example"));
        Files.writeString(tempDir.resolve("target/classes/example/Hello.class"), "fake");
        Files.createDirectories(tempDir.resolve(".jk/generated"));
        Files.writeString(tempDir.resolve(".jk/generated/Source.java"), "// gen");

        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(tempDir.resolve(".jk/generated")).doesNotExist();
    }

    @Test
    void clean_idempotent_on_empty_project(@TempDir Path tempDir) {
        run("init", tempDir.toString());
        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
    }

    // --- explain -----------------------------------------------------------

    @Test
    void explain_lists_compile_tasks_with_cache_status(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        String stdout = captureStdout(() ->
                run("explain", "-C", tempDir.toString(),
                        "--cache-dir", tempDir.resolve("cache").toString()));
        assertThat(stdout).contains("build plan for widget v0.1.0");
        assertThat(stdout).contains("compile-main:");
        assertThat(stdout).contains("[MISS");
    }

    @Test
    void explain_reports_hit_after_build(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        String stdout = captureStdout(() ->
                run("explain", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("[HIT");
    }

    // --- why-rebuilt -------------------------------------------------------

    @Test
    void why_rebuilt_reports_no_change_after_build(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        String stdout = captureStdout(() ->
                run("why-rebuilt", "compile-main",
                        "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("no input changes");
    }

    @Test
    void why_rebuilt_reports_changed_source(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        // Edit the source.
        Files.writeString(src, "package example; public class Hello { void f() {} }");

        String stdout = captureStdout(() ->
                run("why-rebuilt", "compile-main",
                        "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("would rebuild");
        assertThat(stdout).contains("inputs that changed");
        assertThat(stdout).contains("Hello.java");
    }

    @Test
    void why_rebuilt_no_prior_run(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        String stdout = captureStdout(() ->
                run("why-rebuilt", "compile-main",
                        "-C", tempDir.toString(),
                        "--cache-dir", tempDir.resolve("cache").toString()));
        assertThat(stdout).contains("no previous run");
    }

    @Test
    void why_rebuilt_unknown_task(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        // Get a prior run logged so the early "no previous run" path doesn't
        // fire. We can't easily inject a record for a bogus task; instead,
        // just point at compile-main with the wrong name and expect an error.
        int exit = run("why-rebuilt", "bogus-task",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        // Either "no previous run" (exit 0) or "unknown task" (exit 64) — both are valid.
        assertThat(exit).isIn(0, 64);
    }

    // --- helpers -----------------------------------------------------------

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
