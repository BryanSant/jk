// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for Kotlin support in jk check / jk build. No network —
 * jk init produces a jk.toml + empty lockfile, then we run check and
 * build against pure-Kotlin sources.
 */
class KotlinCompilationTest {

    @Test
    void check_passes_for_pure_kotlin_project(@TempDir Path tempDir) throws IOException {
        run("new", "--lang", "kotlin", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // jk new no longer locks; check needs a lock
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi from kotlin"
                """);

        int exit = run("check", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void build_packages_kotlin_classes_into_jar(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--lang", "kotlin", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi"
                """);

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        Path jar = tempDir.resolve("target/widget-0.1.0.jar");
        assertThat(jar).exists();
        try (JarFile jf = new JarFile(jar.toFile())) {
            // Top-level Kotlin function -> <FilenameKt> class.
            assertThat(jf.getJarEntry("example/HelloKt.class")).isNotNull();
        }
    }

    @Test
    void second_build_skips_kotlin_compile_when_nothing_changed(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--lang", "kotlin", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi"
                """);
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Path ktClass = tempDir.resolve("target/classes/main/example/HelloKt.class");
        Path stamp = tempDir.resolve("target/classes/main/.kstamp");
        assertThat(stamp).exists(); // freshness stamp written
        assertThat(ktClass).exists();
        long firstMtime = Files.getLastModifiedTime(ktClass).toMillis();

        // A no-change rebuild must hit the freshness stamp and NOT re-invoke
        // kotlinc — the output .class is left exactly as the first build wrote it.
        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(ktClass).toMillis()).isEqualTo(firstMtime);
    }

    @Test
    void editing_a_kotlin_source_recompiles(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "widget", "--lang", "kotlin", tempDir.toString());
        Path src = tempDir.resolve("src/main/kotlin/example/Hello.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package example

                fun greet(): String = "hi"
                """);
        Path cache = tempDir.resolve("cache");

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        Path ktClass = tempDir.resolve("target/classes/main/example/HelloKt.class");
        long firstMtime = Files.getLastModifiedTime(ktClass).toMillis();

        // Edit the source forward in time so its mtime exceeds the stamp; the
        // next build must fall through the freshness check and recompile.
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(firstMtime + 5_000));
        Files.writeString(src, """
                package example

                fun greet(): String = "hi, again"
                """);
        Files.setLastModifiedTime(src, java.nio.file.attribute.FileTime.fromMillis(firstMtime + 5_000));

        assertThat(run("build", "-C", tempDir.toString(), "--cache-dir", cache.toString()))
                .isEqualTo(0);
        assertThat(Files.getLastModifiedTime(ktClass).toMillis()).isNotEqualTo(firstMtime);
    }

    @Test
    void check_fails_on_kotlin_syntax_error(@TempDir Path tempDir) throws IOException {
        run("new", "--lang", "kotlin", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // jk new no longer locks; check needs a lock
        Path src = tempDir.resolve("src/main/kotlin/Broken.kt");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "fun broken( = oops");

        int exit = run("check", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void mixed_java_and_kotlin_compile_together(@TempDir Path tempDir) throws IOException {
        run("new", "--name", "mixed", "--lang", "kotlin", "--layout", "traditional", tempDir.toString());
        // Opt into Java too — a mixed project declares both java and kotlin.
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replace("[project]\n", "[project]\njava = 25\n"));
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

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        try (JarFile jf = new JarFile(tempDir.resolve("target/mixed-0.1.0.jar").toFile())) {
            assertThat(jf.getJarEntry("example/Hub.class")).isNotNull();
            assertThat(jf.getJarEntry("example/GreeterKt.class")).isNotNull();
        }
    }

    @Test
    void java_calls_kotlin_compiles_together(@TempDir Path tempDir) throws IOException {
        // The reorder (Kotlin first, then javac against Kotlin's output) makes
        // Java → Kotlin references resolve within a single module.
        run("new", "--name", "mixed", "--lang", "kotlin", "--layout", "traditional", tempDir.toString());
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replace("[project]\n", "[project]\njava = 25\n"));
        Path ktSrc = tempDir.resolve("src/main/kotlin/example/Greeter.kt");
        Files.createDirectories(ktSrc.getParent());
        Files.writeString(ktSrc, """
                package example

                class Greeter { fun greet(): String = "hi from kotlin" }
                """);
        // Java type that calls the Kotlin class.
        Path javaSrc = tempDir.resolve("src/main/java/example/App.java");
        Files.createDirectories(javaSrc.getParent());
        Files.writeString(javaSrc, """
                package example;
                public final class App {
                    public static String run() { return new Greeter().greet(); }
                }
                """);

        int exit = run("build", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);

        try (JarFile jf = new JarFile(tempDir.resolve("target/mixed-0.1.0.jar").toFile())) {
            assertThat(jf.getJarEntry("example/Greeter.class")).isNotNull();
            assertThat(jf.getJarEntry("example/App.class")).isNotNull();
        }
    }

    @Test
    void check_passes_for_java_calling_kotlin(@TempDir Path tempDir) throws IOException {
        // jk check → CompileCommand, which also compiles Kotlin-first so Java can
        // reference Kotlin within the module.
        run("new", "--name", "mixed", "--lang", "kotlin", "--layout", "traditional", tempDir.toString());
        ScaffoldTestSupport.writeEmptyLock(tempDir); // jk new no longer locks; check needs a lock
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, Files.readString(toml).replace("[project]\n", "[project]\njava = 25\n"));
        Path ktSrc = tempDir.resolve("src/main/kotlin/example/Greeter.kt");
        Files.createDirectories(ktSrc.getParent());
        Files.writeString(ktSrc, """
                package example

                class Greeter { fun greet(): String = "hi from kotlin" }
                """);
        Path javaSrc = tempDir.resolve("src/main/java/example/App.java");
        Files.createDirectories(javaSrc.getParent());
        Files.writeString(javaSrc, """
                package example;
                public final class App {
                    public static String run() { return new Greeter().greet(); }
                }
                """);

        int exit = run("check", "-C", tempDir.toString(), "--cache-dir", SharedTestCache.arg());
        assertThat(exit).isEqualTo(0);
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
