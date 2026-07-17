// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.discovery.JkProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkResolverTest {

    @Test
    void resolves_vendor_major_pin_to_the_installed_patch(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"), "21.0.5");
        makeJdkInstall(jdks.resolve("temurin-23"), "23");

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jdk-version"), "temurin-21");

        JdkResolver resolver = new JdkResolver(isolatedRegistry(jdks));
        assertThat(resolver.resolve(project))
                .isPresent()
                .get()
                .extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void rejects_a_patch_level_pin(@TempDir Path tempDir) throws IOException {
        Path jdks = tempDir.resolve("jdks");
        makeJdkInstall(jdks.resolve("temurin-21.0.5"), "21.0.5");
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".jdk-version"), "temurin-21.0.5");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new JdkResolver(isolatedRegistry(jdks)).resolve(project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<vendor>-<major>");
    }

    @Test
    void empty_when_no_pin(@TempDir Path tempDir) throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        assertThat(new JdkResolver(isolatedRegistry(tempDir.resolve("jdks"))).resolve(project))
                .isEmpty();
    }

    private static JdkRegistry isolatedRegistry(Path root) {
        return new JdkRegistry(root, List.of(new JkProbe(root)));
    }

    /**
     * Realistic install dir: {@code bin/java} + a {@code release} file so probe-based discovery picks
     * it up.
     */
    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(
                home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
