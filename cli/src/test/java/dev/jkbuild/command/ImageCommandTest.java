// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jk image} builds from source through the shared pipeline — no prior
 * {@code jk build}. A project with no main class compiles + packages (proving
 * the pipeline ran), then the image tail rejects it with EX_USAGE (64).
 */
class ImageCommandTest {

    @Test
    void builds_from_source_then_fails_image_without_main(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nartifact = \"widget\"\nversion = \"0.1.0\"\njava = 21\n");
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package example;\npublic class Hello {}\n");

        int exit = run("image", "-C", tempDir.toString(),
                "--tarball", tempDir.resolve("out.oci.tar").toString(),
                "--cache-dir", tempDir.resolve("cache").toString());

        assertThat(exit).isEqualTo(64);
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).exists();
        assertThat(tempDir.resolve("jk.lock")).exists();
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
