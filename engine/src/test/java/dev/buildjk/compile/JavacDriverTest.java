// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavacDriverTest {

    @Test
    void compiles_clean_source(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Hello.java");
        Files.writeString(source, """
                public class Hello {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);

        CompileResult result = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(source))
                .outputDir(tempDir.resolve("out"))
                .build());

        assertThat(result.success()).isTrue();
        assertThat(result.hasErrors()).isFalse();
        assertThat(tempDir.resolve("out/Hello.class")).exists();
    }

    @Test
    void reports_syntax_error(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Broken.java");
        Files.writeString(source, "public class Broken { void f(  // missing brace\n");

        CompileResult result = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(source))
                .outputDir(tempDir.resolve("out"))
                .build());

        assertThat(result.success()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.severity()).isEqualTo(CompileResult.Severity.ERROR));
    }

    @Test
    void check_mode_does_not_write_class_files(@TempDir Path tempDir) throws IOException {
        Path source = tempDir.resolve("Hello.java");
        Files.writeString(source, """
                public class Hello { public static void main(String[] a) {} }
                """);
        Path out = tempDir.resolve("out");

        CompileResult result = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(source))
                // outputDir not set → check mode
                .build());

        assertThat(result.success()).isTrue();
        assertThat(out).doesNotExist();
    }

    @Test
    void picks_up_classpath_dependency(@TempDir Path tempDir) throws IOException {
        // Compile a tiny "library" first, then a "consumer" that uses it via classpath.
        Path libDir = tempDir.resolve("lib-out");
        Path libSrc = tempDir.resolve("Lib.java");
        Files.writeString(libSrc, """
                package lib;
                public final class Lib {
                    public static String greet() { return "hi"; }
                }
                """);
        // Move into a package directory javac expects.
        Path libPkg = tempDir.resolve("lib");
        Files.createDirectories(libPkg);
        Files.move(libSrc, libPkg.resolve("Lib.java"));

        CompileResult libResult = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(libPkg.resolve("Lib.java")))
                .outputDir(libDir)
                .build());
        assertThat(libResult.success()).isTrue();

        Path consumer = tempDir.resolve("Use.java");
        Files.writeString(consumer, """
                public class Use {
                    public static void main(String[] args) {
                        System.out.println(lib.Lib.greet());
                    }
                }
                """);

        CompileResult result = new JavacDriver().compile(CompileRequest.builder()
                .sources(List.of(consumer))
                .classpath(List.of(libDir))
                .outputDir(tempDir.resolve("consumer-out"))
                .build());

        assertThat(result.success()).isTrue();
    }

    @Test
    void diagnostic_renders_with_location() {
        CompileResult.Diagnostic d = new CompileResult.Diagnostic(
                CompileResult.Severity.ERROR,
                Path.of("src/Foo.java"),
                12, 7,
                "missing semicolon");
        assertThat(d.render()).isEqualTo("error: src/Foo.java:12:7: missing semicolon");
    }
}
