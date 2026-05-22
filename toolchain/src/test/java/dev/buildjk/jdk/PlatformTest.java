// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
