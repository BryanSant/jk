// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jk native} builds from source through the shared pipeline — no prior
 * {@code jk build} needed. We can't run GraalVM native-image in tests, so we
 * stop at the native tail's main-class check: a project with no main class
 * still compiles + packages from source (proving the pipeline ran), then the
 * native phase rejects it with EX_USAGE (64).
 */
class NativeCommandTest {

    @Test
    void builds_from_source_then_fails_native_without_main(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nartifact = \"widget\"\nversion = \"0.1.0\"\njava = 21\n");
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Hello {}\n");

        int exit = run("native", "-C", tempDir.toString(),
                "--cache-dir", tempDir.resolve("cache").toString());

        // Reached the native tail and rejected the missing main class…
        assertThat(exit).isEqualTo(64);
        // …but only after building the jar from source in the same run.
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();
        // And it auto-locked, proving the full pipeline ran (not a stub).
        assertThat(tempDir.resolve("jk.lock")).exists();
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
