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

class BuildCacheTest {

    @Test
    void second_build_hits_action_cache(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello { public static String greet() { return "hi"; } }
                """);

        Path cache = tempDir.resolve("cache");
        // First build: cache miss, real compile.
        int first = run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString());
        assertThat(first).isEqualTo(0);

        // Second build: same inputs → cache hit. Capture stdout.
        String stdout = captureStdout(() ->
                run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()));
        assertThat(stdout).contains("Cache hit: compile-main");
    }

    @Test
    void editing_a_source_invalidates_cache(@TempDir Path tempDir) throws Exception {
        run("init", "--name", "widget", tempDir.toString());
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
