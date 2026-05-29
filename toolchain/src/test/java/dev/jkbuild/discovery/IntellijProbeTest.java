// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.JdkHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntellijProbeTest {

    @Test
    void default_root_is_dot_jdks_on_linux() {
        assertThat(IntellijProbe.defaultRoot("Linux", "/home/me"))
                .isEqualTo(Path.of("/home/me/.jdks"));
    }

    @Test
    void default_root_is_library_java_jvms_on_macos() {
        assertThat(IntellijProbe.defaultRoot("Mac OS X", "/Users/me"))
                .isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
        assertThat(IntellijProbe.defaultRoot("Darwin", "/Users/me"))
                .isEqualTo(Path.of("/Users/me/Library/Java/JavaVirtualMachines"));
    }

    @Test
    void default_root_falls_back_to_dot_jdks_on_windows() {
        // Windows uses the same convention as Linux — IntelliJ on Windows
        // installs JDKs under %USERPROFILE%\.jdks\.
        assertThat(IntellijProbe.defaultRoot("Windows 11", "C:\\Users\\me"))
                .isEqualTo(Path.of("C:\\Users\\me").resolve(".jdks"));
    }

    @Test
    void discover_finds_a_flat_jdk_install(@TempDir Path tempDir) throws IOException {
        Path install = tempDir.resolve("temurin-21");
        Files.createDirectories(install.resolve("bin"));
        Files.writeString(install.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(install.resolve("release"),
                "JAVA_VERSION=\"21.0.5\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        List<JdkHit> hits = new IntellijProbe(tempDir).discoverAllJdks();
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().source()).isEqualTo("intellij");
    }

    @Test
    void discover_unwraps_contents_home_on_mac_bundles(@TempDir Path tempDir) throws IOException {
        Path bundle = tempDir.resolve("temurin-21.jdk");
        Path realHome = bundle.resolve("Contents").resolve("Home");
        Files.createDirectories(realHome.resolve("bin"));
        Files.writeString(realHome.resolve("bin").resolve("java"), "#!/fake\n");
        Files.writeString(realHome.resolve("release"),
                "JAVA_VERSION=\"21.0.5\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");

        List<JdkHit> hits = new IntellijProbe(tempDir).discoverAllJdks();
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().home()).isEqualTo(realHome.toRealPath());
    }

    @Test
    void empty_when_root_does_not_exist(@TempDir Path tempDir) throws IOException {
        assertThat(new IntellijProbe(tempDir.resolve("does-not-exist")).discoverAllJdks())
                .isEmpty();
    }
}
