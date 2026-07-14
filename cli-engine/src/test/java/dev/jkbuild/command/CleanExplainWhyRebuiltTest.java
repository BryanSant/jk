// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CleanExplainWhyRebuiltTest {

    // --- clean -------------------------------------------------------------

    @Test
    void clean_removes_target_and_build_and_generated(@TempDir Path tempDir) throws Exception {
        run("new", "--layout", "traditional", tempDir.toString());
        // Intermediates live under build/ in the v1 two-tier layout.
        Files.createDirectories(tempDir.resolve("target/build/classes/main/example"));
        Files.writeString(tempDir.resolve("target/build/classes/main/example/Hello.class"), "fake");
        // Final artifacts live under target/.
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/widget-0.1.0.jar"), "fake");
        // Foreign build systems' dirs are not jk's to clean.
        Files.createDirectories(tempDir.resolve("build"));

        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(tempDir.resolve("build")).exists();
    }

    @Test
    void clean_keep_artifacts_preserves_target(@TempDir Path tempDir) throws Exception {
        run("new", "--layout", "traditional", tempDir.toString());
        Files.createDirectories(tempDir.resolve("target/build/classes/main"));
        Files.writeString(tempDir.resolve("target/build/classes/main/Hello.class"), "fake");
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/widget-0.1.0.jar"), "fake-jar");

        int exit = run("clean", "--keep-artifacts", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
        assertThat(tempDir.resolve("build")).doesNotExist();
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();
    }

    @Test
    void clean_idempotent_on_empty_project(@TempDir Path tempDir) {
        run("new", "--layout", "traditional", tempDir.toString());
        int exit = run("clean", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);
    }

    // --- explain -----------------------------------------------------------

    @Test
    void explain_lists_compile_tasks_with_cache_status(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // jk new no longer locks; explain needs a lock
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        String stdout = captureStdout(() -> run(
                "explain",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString()));
        // Header "- Build Plan" chip + the ● root coord; a single never-built project
        // lands in the "Rebuild" section.
        assertThat(stdout).contains("Build Plan").contains("widget");
        assertThat(stdout).containsIgnoringCase("rebuild");
        assertThat(stdout).contains("compile-main");
        // Never built → the module rebuilds: "□ full compile · N sources".
        assertThat(stdout).contains("full compile");
    }

    @Test
    void explain_reports_hit_after_build(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example; public class Hello {}");

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        String stdout = captureStdout(() -> run("explain", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        // After a real build the module is cached: the "Fully Cached" section (or a
        // "✓ cached <key>" phase if a downstream phase still rebuilds).
        assertThat(stdout).containsIgnoringCase("cached");
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
