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
    void linux_root_is_dot_jdks() {
        assertThat(IntellijJdkDir.rootFor("linux", "/home/me"))
                .isEqualTo(Path.of("/home/me/.jdks"));
        assertThat(IntellijJdkDir.rootFor("windows", "/home/me"))
                .isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void macos_root_is_library_java_jvms() {
        assertThat(IntellijJdkDir.rootFor("macOS", "/Users/me"))
                .isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
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
