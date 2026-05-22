// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for Kotlin support in jk check / jk build. No network —
 * jk init produces a build.jk + empty lockfile, then we run check and
 * build against pure-Kotlin sources.
 */
class KotlinCompilationTest {

    @Test
    void check_passes_for_pure_kotlin_project(@TempDir Path tempDir) throws IOException {
        run("init", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi from kotlin"
                """);

        int exit = run("check",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void build_packages_kotlin_classes_into_jar(@TempDir Path tempDir) throws IOException {
        run("init", "--name", "widget", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi"
                """);

        int exit = run("build",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        Path jar = tempDir.resolve("target/widget-0.1.0.jar");
        assertThat(jar).exists();
        try (JarFile jf = new JarFile(jar.toFile())) {
            // Top-level Kotlin function -> <FilenameKt> class.
            assertThat(jf.getJarEntry("example/HelloKt.class")).isNotNull();
        }
    }

    @Test
    void check_fails_on_kotlin_syntax_error(@TempDir Path tempDir) throws IOException {
        run("init", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/Broken.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "fun broken( = oops");

        int exit = run("check",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void mixed_java_and_kotlin_compile_together(@TempDir Path tempDir) throws IOException {
        run("init", "--name", "mixed", tempDir.toString());
        // Java type that Kotlin will use.
        Path javaSrc = tempDir.resolve("src/main/java/example/Hub.java");
        Files.createDirectories(javaSrc.getParent());
        Files.writeString(javaSrc, """
                package example;
                public final class Hub {
                    public static String banner() { return "hub from java"; }
                }
                """);
        Path ktSrc = tempDir.resolve("src/main/kotlin/example/Greeter.kt");
        Files.createDirectories(ktSrc.getParent());
        Files.writeString(ktSrc, """
                package example

                fun greet(): String = Hub.banner()
                """);

        int exit = run("build",
                "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());
        assertThat(exit).isEqualTo(0);

        try (JarFile jf = new JarFile(tempDir.resolve("target/mixed-0.1.0.jar").toFile())) {
            assertThat(jf.getJarEntry("example/Hub.class")).isNotNull();
            assertThat(jf.getJarEntry("example/GreeterKt.class")).isNotNull();
        }
    }

    private static int run(String... args) {
        return new CommandLine(new Jk()).execute(args);
    }
}
