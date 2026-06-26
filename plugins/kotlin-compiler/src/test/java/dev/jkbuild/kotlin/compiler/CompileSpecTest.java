// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure parsing + argument-building behaviour of the worker — no Build Tools API runtime required
 * (those paths are exercised end-to-end once jk's launcher + resolver land).
 */
class CompileSpecTest {

    @Test
    void parses_all_keys_and_repeatables(@TempDir Path dir) throws IOException {
        Path spec = write(dir, """
                # a comment, and a blank line follows

                OUTPUT /tmp/out
                WORKDIR /tmp/work
                SNAPSHOT_DIR /tmp/snaps
                JVM_TARGET 21
                MODULE_NAME main
                LANGUAGE_VERSION 2.4
                API_VERSION 2.4
                SOURCE /src/A.kt
                SOURCE /src/B.kt
                CLASSPATH /libs/stdlib.jar
                CLASSPATH /libs/dep.jar
                FRIEND /build/classes/other
                ARG -no-stdlib
                ARG -Xfoo
                """);

        CompileSpec s = CompileSpec.parse(spec);

        assertThat(s.outputDir).isEqualTo(new File("/tmp/out"));
        assertThat(s.workingDir).isEqualTo(new File("/tmp/work"));
        assertThat(s.snapshotDir).isEqualTo(new File("/tmp/snaps"));
        assertThat(s.incremental()).isTrue();
        assertThat(s.jvmTarget).isEqualTo("21");
        assertThat(s.moduleName).isEqualTo("main");
        assertThat(s.languageVersion).isEqualTo("2.4");
        assertThat(s.apiVersion).isEqualTo("2.4");
        assertThat(s.sources).containsExactly(new File("/src/A.kt"), new File("/src/B.kt"));
        assertThat(s.classpath).containsExactly(new File("/libs/stdlib.jar"), new File("/libs/dep.jar"));
        assertThat(s.friendPaths).containsExactly(new File("/build/classes/other"));
        assertThat(s.extraArgs).containsExactly("-no-stdlib", "-Xfoo");
    }

    @Test
    void absent_workdir_means_non_incremental(@TempDir Path dir) throws IOException {
        CompileSpec s = CompileSpec.parse(write(dir, """
                OUTPUT /tmp/out
                JVM_TARGET 21
                SOURCE /src/A.kt
                """));
        assertThat(s.incremental()).isFalse();
    }

    @Test
    void value_may_contain_spaces(@TempDir Path dir) throws IOException {
        CompileSpec s = CompileSpec.parse(write(dir, """
                OUTPUT /tmp/with space/out
                JVM_TARGET 21
                SOURCE /tmp/with space/A.kt
                """));
        assertThat(s.outputDir).isEqualTo(new File("/tmp/with space/out"));
        assertThat(s.sources).containsExactly(new File("/tmp/with space/A.kt"));
    }

    @Test
    void rejects_missing_required_keys(@TempDir Path dir) throws IOException {
        Path noOutput = write(dir, "JVM_TARGET 21\nSOURCE /a.kt\n");
        assertThatThrownBy(() -> CompileSpec.parse(noOutput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OUTPUT");

        Path noSources = write(dir, "OUTPUT /o\nJVM_TARGET 21\n");
        assertThatThrownBy(() -> CompileSpec.parse(noSources))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOURCE");
    }

    @Test
    void rejects_unknown_key(@TempDir Path dir) throws IOException {
        Path spec = write(dir, "OUTPUT /o\nJVM_TARGET 21\nSOURCE /a.kt\nBOGUS x\n");
        assertThatThrownBy(() -> CompileSpec.parse(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS");
    }

    @Test
    void build_args_for_incremental_carry_fir_flag_and_options(@TempDir Path dir) throws IOException {
        CompileSpec s = CompileSpec.parse(write(dir, """
                OUTPUT /o
                WORKDIR /w
                JVM_TARGET 21
                MODULE_NAME main
                LANGUAGE_VERSION 2.4
                SOURCE /a.kt
                CLASSPATH /libs/x.jar
                FRIEND /f1
                FRIEND /f2
                ARG -no-stdlib
                """));

        List<String> args = KotlinCompilerWorker.buildArgs(s);

        // Destination is a builder parameter, never a raw arg.
        assertThat(args).doesNotContain("-d");
        assertThat(args).containsSequence("-jvm-target", "21");
        assertThat(args).containsSequence("-module-name", "main");
        assertThat(args).containsSequence("-language-version", "2.4");
        assertThat(args).containsSequence("-classpath", "/libs/x.jar");
        assertThat(args).contains("-Xfriend-paths=/f1,/f2");
        assertThat(args).contains("-Xuse-fir-ic"); // required by the FIR IC runner
        assertThat(args).endsWith("-no-stdlib"); // free ARGs appended verbatim
    }

    @Test
    void build_args_for_full_compile_omit_fir_flag(@TempDir Path dir) throws IOException {
        CompileSpec s = CompileSpec.parse(write(dir, """
                OUTPUT /o
                JVM_TARGET 21
                SOURCE /a.kt
                """));
        assertThat(KotlinCompilerWorker.buildArgs(s)).doesNotContain("-Xuse-fir-ic");
    }

    private static Path write(Path dir, String body) throws IOException {
        Path f = dir.resolve("spec-" + System.nanoTime() + ".txt");
        Files.writeString(f, body);
        return f;
    }
}
