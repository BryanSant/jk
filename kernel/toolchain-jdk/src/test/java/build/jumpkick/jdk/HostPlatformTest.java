// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostPlatformTest {

    @Test
    void linux_maps_to_feed_os() {
        assertThat(HostPlatform.mapOs("Linux")).isEqualTo("linux");
        assertThat(HostPlatform.mapOs("Mac OS X")).isEqualTo("macOS");
        assertThat(HostPlatform.mapOs("Darwin")).isEqualTo("macOS");
        assertThat(HostPlatform.mapOs("Windows 11")).isEqualTo("windows");
        assertThat(HostPlatform.mapOs("AIX")).isEqualTo(HostPlatform.UNSUPPORTED);
        assertThat(HostPlatform.mapOs(null)).isEqualTo(HostPlatform.UNSUPPORTED);
    }

    @Test
    void arch_normalises_to_feed_vocabulary() {
        assertThat(HostPlatform.mapArch("amd64")).isEqualTo("x86_64");
        assertThat(HostPlatform.mapArch("x86_64")).isEqualTo("x86_64");
        assertThat(HostPlatform.mapArch("aarch64")).isEqualTo("aarch64");
        assertThat(HostPlatform.mapArch("arm64")).isEqualTo("aarch64");
        assertThat(HostPlatform.mapArch("i386")).isEqualTo(HostPlatform.UNSUPPORTED);
        assertThat(HostPlatform.mapArch("arm")).isEqualTo(HostPlatform.UNSUPPORTED);
    }
}
