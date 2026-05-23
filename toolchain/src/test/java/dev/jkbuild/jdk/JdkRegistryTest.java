// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkRegistryTest {

    @Test
    void list_returns_immediate_subdirectories(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"));
        makeJdkInstall(tempDir.resolve("graalvm-ce-21.0.2"));
        Files.writeString(tempDir.resolve("README.txt"), "ignored");

        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.list())
                .extracting(InstalledJdk::identifier)
                .containsExactly("graalvm-ce-21.0.2", "temurin-21.0.5");
    }

    @Test
    void list_skips_directories_without_bin(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("not-a-jdk"));
        assertThat(new JdkRegistry(tempDir).list()).isEmpty();
    }

    @Test
    void list_resolves_macos_contents_home_layout(@TempDir Path tempDir) throws IOException {
        Path macHome = tempDir.resolve("temurin-21").resolve("Contents").resolve("Home").resolve("bin");
        Files.createDirectories(macHome);
        Files.writeString(macHome.resolve("java"), "#!/fake");

        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.list())
                .singleElement()
                .satisfies(jdk -> {
                    assertThat(jdk.identifier()).isEqualTo("temurin-21");
                    assertThat(jdk.home()).isEqualTo(tempDir.resolve("temurin-21")
                            .resolve("Contents").resolve("Home"));
                });
    }

    @Test
    void missing_root_returns_empty_list(@TempDir Path tempDir) throws IOException {
        Path nonexistent = tempDir.resolve("nope");
        assertThat(new JdkRegistry(nonexistent).list()).isEmpty();
    }

    @Test
    void find_by_prefix(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"));
        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.findByPrefix("temurin-21"))
                .isPresent()
                .get().extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void remove_deletes_directory_tree(@TempDir Path tempDir) throws IOException {
        Path jdkHome = tempDir.resolve("temurin-21.0.5");
        makeJdkInstall(jdkHome);

        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.remove("temurin-21.0.5")).isTrue();
        assertThat(jdkHome).doesNotExist();

        assertThat(registry.remove("not-installed")).isFalse();
    }

    private static void makeJdkInstall(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
    }
}
