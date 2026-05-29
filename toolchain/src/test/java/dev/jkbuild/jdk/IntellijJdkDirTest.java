// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntellijJdkDirTest {

    @Test
    void root_is_dot_jk_jdks_on_every_platform() {
        assertThat(IntellijJdkDir.rootFor("/home/me"))
                .isEqualTo(Path.of("/home/me/.jk/jdks"));
        assertThat(IntellijJdkDir.rootFor("/Users/me"))
                .isEqualTo(Path.of("/Users/me/.jk/jdks"));
    }

    @Test
    void java_home_passes_through_when_no_contents_home(@TempDir Path tempDir) throws IOException {
        Path install = tempDir.resolve("temurin-21");
        Files.createDirectories(install.resolve("bin"));
        assertThat(IntellijJdkDir.javaHome(install)).isEqualTo(install);
    }

    @Test
    void java_home_steps_into_contents_home_on_mac_layout(@TempDir Path tempDir) throws IOException {
        Path install = tempDir.resolve("temurin-21");
        Path realHome = install.resolve("Contents").resolve("Home");
        Files.createDirectories(realHome.resolve("bin"));
        assertThat(IntellijJdkDir.javaHome(install)).isEqualTo(realHome);
    }
}
