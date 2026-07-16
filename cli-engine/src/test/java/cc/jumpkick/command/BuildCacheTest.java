// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildCacheTest {

    @Test
    void second_build_is_up_to_date_via_freshness_stamp(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        // First build: stamp is absent, action cache misses, real compile.
        int first = run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(first).isEqualTo(0);

        // Backdate the source so its mtime is unambiguously older than the
        // freshness stamp. Without this the test races filesystem mtime
        // granularity (a coarse mount can truncate the source's mtime into the
        // same second as the stamp), making the fast-skip nondeterministic.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

        // Second build: stamp is fresh, no input newer → fast skip without
        // even hashing source content for an action-key lookup.
        String stdout = captureStdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("project up to date");
    }

    @Test
    void rebuild_flag_bypasses_the_up_to_date_fast_path(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 5_000));

        // --rebuild: stamps and action cache are distrusted — a genuine recompile, never the
        // "project up to date" fast path (and unlike --force, no dependency re-fetch).
        String stdout = captureStdout(
                () -> run("build", "--rebuild", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).doesNotContain("project up to date");
        assertThat(stdout).contains("Build successful");
    }

    @Test
    void wiping_classes_dir_falls_through_to_action_cache(@TempDir Path tempDir) throws Exception {
        // The freshness stamp lives inside the classes dir; deleting the
        // dir (e.g. `jk clean`) removes the stamp and forces the action-key
        // path, which restores from CAS on a hit.
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        // Simulate `jk clean` — wipe both the intermediates (build/, where
        // the freshness stamp lives) and the artifacts (target/).
        deleteRecursively(tempDir.resolve("build"));
        deleteRecursively(tempDir.resolve("target"));

        String stdout = captureStdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("Built");
    }

    @Test
    void clean_force_also_invalidates_the_project_action_cache(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);
        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        Path keys = cache.resolve("actions/keys");
        assertThat(Files.list(keys).count()).as("build populated the action cache").isPositive();

        // Plain clean: files go, the action cache STAYS (the next build restores from it).
        assertThat(run("clean", "-C", tempDir.toString(), "--cache-dir", cache.toString())).isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(Files.list(keys).count()).isPositive();

        // The hammer: clean --force ALSO invalidates this project's action-cache entries.
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(run("clean", "--force", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(tempDir.resolve("target")).doesNotExist();
        assertThat(Files.list(keys).count())
                .as("clean --force left no action-cache entries for the project")
                .isZero();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void editing_a_source_invalidates_cache(@TempDir Path tempDir) throws Exception {
        run("new", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());

        // Edit the source — cache should miss.
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "ho"; } }
                """);
        // Forward-date the edit so its mtime is unambiguously newer than the
        // freshness stamp. Without this the test races filesystem mtime
        // granularity (a coarse mount truncates the edit into the same second
        // as the stamp, comparing below the millisecond-precise stamp clock),
        // which let a real content change look "up to date".
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        String stdout = captureStdout(() -> run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).doesNotContain("Cache hit");
        assertThat(stdout).contains("Built");
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
