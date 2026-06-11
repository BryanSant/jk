// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StableJdkPointerTest {

    private static Path fakeJdk(Path root, String name, String version) throws IOException {
        Path home = root.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        return home;
    }

    @Test
    void symlink_points_at_patch_dir_and_repoints_on_upgrade(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path p3 = fakeJdk(jdks, "temurin-25.0.3", "25.0.3");
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("temurin-25", p3);
        Path link = jdks.resolve("temurin-25");
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(link.toRealPath()).isEqualTo(p3.toRealPath());

        // Idempotent: a second ensure at the same target is a no-op.
        ptr.ensure("temurin-25", p3);
        assertThat(link.toRealPath()).isEqualTo(p3.toRealPath());

        // Upgrade: the symlink repoints at the new patch, the path is unchanged.
        Path p4 = fakeJdk(jdks, "temurin-25.0.4", "25.0.4");
        ptr.ensure("temurin-25", p4);
        assertThat(link.toRealPath()).isEqualTo(p4.toRealPath());
        // No Contents/Home on this layout → java home is the pointer itself.
        assertThat(ptr.javaHome("temurin-25")).isEqualTo(link);
    }

    @Test
    void java_home_resolves_macos_contents_home(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path install = jdks.resolve("temurin-25.0.3");
        Files.createDirectories(install.resolve("Contents/Home/bin"));
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("temurin-25", install);
        assertThat(ptr.javaHome("temurin-25"))
                .isEqualTo(jdks.resolve("temurin-25").resolve("Contents").resolve("Home"));
    }

    @Test
    void no_op_and_preserves_install_when_name_equals_install_dir(@TempDir Path tmp)
            throws IOException {
        // Vendor-level installs (e.g. graalvm-jdk-25) name the install dir the
        // same as the stable pointer — ensure() must not delete it.
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        Path install = fakeJdk(jdks, "graalvm-jdk-25", "25.0.3");
        StableJdkPointer ptr = new StableJdkPointer(jdks);

        ptr.ensure("graalvm-jdk-25", install);

        assertThat(install.resolve("bin/java")).exists();
        assertThat(Files.isSymbolicLink(install)).isFalse();
    }

    @Test
    void missing_install_is_a_no_op(@TempDir Path tmp) throws IOException {
        Path jdks = tmp.resolve("jdks");
        Files.createDirectories(jdks);
        StableJdkPointer ptr = new StableJdkPointer(jdks);
        ptr.ensure("temurin-25", jdks.resolve("does-not-exist"));
        assertThat(Files.exists(jdks.resolve("temurin-25"))).isFalse();
    }
}
