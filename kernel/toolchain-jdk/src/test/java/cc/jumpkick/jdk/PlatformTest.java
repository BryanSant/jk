// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTest {

    @Test
    void maps_common_architectures() {
        assertThat(Platform.mapArchitecture("amd64")).isEqualTo("x64");
        assertThat(Platform.mapArchitecture("x86_64")).isEqualTo("x64");
        assertThat(Platform.mapArchitecture("aarch64")).isEqualTo("aarch64");
        assertThat(Platform.mapArchitecture("arm64")).isEqualTo("aarch64");
        assertThat(Platform.mapArchitecture("i686")).isEqualTo("x86");
        assertThat(Platform.mapArchitecture("riscv64")).isEqualTo("riscv64");
    }

    @Test
    void maps_common_operating_systems() {
        assertThat(Platform.mapOperatingSystem("Linux")).isEqualTo("linux");
        assertThat(Platform.mapOperatingSystem("Mac OS X")).isEqualTo("macos");
        assertThat(Platform.mapOperatingSystem("Darwin")).isEqualTo("macos");
        assertThat(Platform.mapOperatingSystem("Windows 11")).isEqualTo("windows");
    }

    @Test
    void libc_detection_picks_musl_when_loader_is_present(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/ld-musl-x86_64.so.1"), "");
        assertThat(Platform.libCTypeFor("linux", root)).isEqualTo("musl");
    }

    @Test
    void libc_detection_falls_back_to_glibc_on_linux(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/ld-linux-x86-64.so.2"), "");
        assertThat(Platform.libCTypeFor("linux", root)).isEqualTo("glibc");
    }

    @Test
    void libc_detection_glibc_when_lib_dir_missing(@TempDir Path root) {
        // No /lib at all — still glibc (matches a sane default for the JVM's host).
        assertThat(Platform.libCTypeFor("linux", root)).isEqualTo("glibc");
    }

    @Test
    void libc_detection_per_os() {
        assertThat(Platform.libCTypeFor("macos", Path.of("/"))).isEqualTo("libc");
        assertThat(Platform.libCTypeFor("windows", Path.of("/"))).isEqualTo("c_std_lib");
        assertThat(Platform.libCTypeFor("freebsd", Path.of("/"))).isNull();
    }
}
