// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

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

        // Second build: stamp is fresh, no input newer → fast skip without
        // even hashing source content for an action-key lookup.
        String stdout = captureStdout(() ->
                run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("Up to date");
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

        // Simulate `jk clean` — wipe the output tree, taking the stamp with it.
        deleteRecursively(tempDir.resolve("target"));

        String stdout = captureStdout(() ->
                run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("Built");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
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

        String stdout = captureStdout(() ->
                run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
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
