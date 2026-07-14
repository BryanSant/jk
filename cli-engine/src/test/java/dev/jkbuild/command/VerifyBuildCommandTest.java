// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@code jk verify}: build a project, then re-build it through the real
 * pipeline into a scratch copy and compare artifact hashes. Reproducible-by-default packaging means
 * an unchanged project must verify clean — including Kotlin, which the old javac-only verify never
 * covered.
 */
class VerifyBuildCommandTest {

    @Test
    void verify_passes_for_unchanged_java_project(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static String greet() { return "hi"; }
                }
                """);
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        // Verify's scratch rebuild bypasses the action cache in BOTH directions: its keys hash
        // the (random, never-recurring) scratch paths, so any store would be a permanent orphan.
        java.util.Set<String> keysBefore = actionKeys(cache);
        assertThat(run("verify", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(actionKeys(cache))
                .as("verify leaves no orphan action-cache entries behind")
                .isEqualTo(keysBefore);
    }

    /** Filenames under {@code <cache>/actions/keys} — the action-cache entry set. */
    private static java.util.Set<String> actionKeys(Path cache) throws IOException {
        Path keys = cache.resolve("actions/keys");
        if (!Files.isDirectory(keys)) return java.util.Set.of();
        try (var list = Files.list(keys)) {
            return new java.util.HashSet<>(
                    list.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void verify_passes_for_kotlin_project(@TempDir Path tempDir) throws IOException {
        // The old verify compiled src/main/java with javac only — a Kotlin project silently
        // verified an empty jar. Through the real pipeline, kotlinc runs in the scratch rebuild.
        run("new", "--name", "widget", "--lang", "kotlin", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi"
                """);

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg()))
                .isEqualTo(0);
        assertThat(run("verify", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg()))
                .isEqualTo(0);
    }

    @Test
    void verify_fails_when_sources_changed_since_build(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static String greet() { return "hi"; }
                }
                """);
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        // Change the source after the build: the scratch rebuild compiles the new code, so its
        // jar can no longer match the stale one in target/.
        Files.writeString(src, """
                package example;
                public class Hello {
                    public static String greet() { return "bye"; }
                }
                """);
        assertThat(run("verify", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(1);
    }

    @Test
    void verify_requires_an_existing_jar(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // verify requires jk.lock, but no build ran
        int exit = run("verify", "-C", tempDir.toString(), "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(66); // Exit.NO_INPUT — run `jk build` first
    }

    @Test
    void verify_covers_every_module_of_a_workspace(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["liba", "app"]
                """, StandardCharsets.UTF_8);
        module(tempDir.resolve("liba"), "liba", "a", "A", "");
        module(tempDir.resolve("app"), "app", "app", "Main", """

                [dependencies]
                liba = { group = "com.example", name = "liba", version = "1.0.0" }
                """);
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(run("verify", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);

        // A drifted module artifact must flip the whole workspace verify to a mismatch.
        Files.writeString(
                tempDir.resolve("liba/src/main/java/a/A.java"),
                "package a; public class A { public static int n() { return 2; } }",
                StandardCharsets.UTF_8);
        assertThat(run("verify", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(1);
    }

    private static void module(Path dir, String name, String pkg, String cls, String extra) throws IOException {
        Files.createDirectories(dir.resolve("src/main/java/" + pkg));
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                group = "com.example"
                name  = "%s"
                version = "1.0.0"
                jdk = 25
                java = 25
                %s
                """.formatted(name, extra), StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("src/main/java/" + pkg + "/" + cls + ".java"),
                "package " + pkg + "; public class " + cls + " {}",
                StandardCharsets.UTF_8);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
