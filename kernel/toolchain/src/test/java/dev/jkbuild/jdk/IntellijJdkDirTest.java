// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntellijJdkDirTest {

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
