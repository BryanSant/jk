// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KotlincDriverTest {

    @Test
    void compiles_clean_kotlin_source(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Hello.kt");
        Files.writeString(src, """
                package example

                fun greet(): String = "hello"
                """);

        KotlincResult result = new KotlincDriver().compile(
                KotlincRequest.builder()
                        .sources(List.of(src))
                        .outputDir(tempDir.resolve("out"))
                        .jvmTarget(21)
                        .build());

        assertThat(result.success())
                .withFailMessage("kotlinc reported: %s", result.output())
                .isTrue();
        assertThat(tempDir.resolve("out/example/HelloKt.class")).exists();
    }

    @Test
    void reports_syntax_error(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("Broken.kt");
        Files.writeString(src, "package example fun bad( = ");

        KotlincResult result = new KotlincDriver().compile(
                KotlincRequest.builder()
                        .sources(List.of(src))
                        .outputDir(tempDir.resolve("out"))
                        .jvmTarget(21)
                        .build());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isNotEmpty();
    }

    @Test
    void picks_up_classpath_dependency(@TempDir Path tempDir) throws IOException {
        // Compile a Java "library" jar first, put it on the classpath, then
        // compile Kotlin that references it.
        Path libDir = tempDir.resolve("lib");
        Path libSrcDir = libDir.resolve("src/lib");
        Files.createDirectories(libSrcDir);
        Files.writeString(libSrcDir.resolve("Greeter.java"), """
                package lib;
                public final class Greeter {
                    public static String greet() { return "hi from java"; }
                }
                """);
        Path libOut = libDir.resolve("out");
        CompileResult libResult = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(libSrcDir.resolve("Greeter.java")))
                .outputDir(libOut)
                .release(21)
                .build());
        assertThat(libResult.success()).isTrue();

        Path consumer = tempDir.resolve("Consumer.kt");
        Files.writeString(consumer, """
                package example
                import lib.Greeter
                fun useIt(): String = Greeter.greet()
                """);

        KotlincResult result = new KotlincDriver().compile(
                KotlincRequest.builder()
                        .sources(List.of(consumer))
                        .classpath(List.of(libOut))
                        .outputDir(tempDir.resolve("k-out"))
                        .jvmTarget(21)
                        .build());

        assertThat(result.success())
                .withFailMessage("kotlinc reported: %s", result.output())
                .isTrue();
    }

    @Test
    void result_error_lines_collected() {
        KotlincResult result = new KotlincResult(
                false, "error: foo\nwarning: bar\nerror: baz\n");
        assertThat(result.errorLines()).containsExactly("error: foo", "error: baz");
    }
}
