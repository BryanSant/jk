// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.discovery.JetbrainsProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdkRegistryTest {

    @Test
    void list_returns_immediate_subdirectories(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");
        makeJdkInstall(tempDir.resolve("graalvm-ce-21.0.2"), "21.0.2");
        Files.writeString(tempDir.resolve("README.txt"), "ignored");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.list())
                .extracting(InstalledJdk::identifier)
                .containsExactlyInAnyOrder("graalvm-ce-21.0.2", "temurin-21.0.5");
    }

    @Test
    void list_skips_directories_without_bin(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("not-a-jdk"));
        assertThat(isolatedRegistry(tempDir).list()).isEmpty();
    }

    @Test
    void list_resolves_macos_contents_home_layout(@TempDir Path tempDir) throws IOException {
        Path bundle = tempDir.resolve("temurin-21");
        Path macHome = bundle.resolve("Contents").resolve("Home");
        Files.createDirectories(macHome.resolve("bin"));
        Files.writeString(macHome.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(macHome.resolve("release"), "JAVA_VERSION=\"21\"\n");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.list())
                .singleElement()
                .satisfies(jdk -> {
                    assertThat(jdk.identifier()).isEqualTo("temurin-21");
                    assertThat(jdk.home()).isEqualTo(macHome.toRealPath());
                });
    }

    @Test
    void missing_root_returns_empty_list(@TempDir Path tempDir) throws IOException {
        Path nonexistent = tempDir.resolve("nope");
        assertThat(isolatedRegistry(nonexistent).list()).isEmpty();
    }

    @Test
    void find_by_prefix(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findByPrefix("temurin-21"))
                .isPresent()
                .get().extracting(InstalledJdk::identifier)
                .isEqualTo("temurin-21.0.5");
    }

    @Test
    void remove_deletes_directory_tree(@TempDir Path tempDir) throws IOException {
        Path jdkHome = tempDir.resolve("temurin-21.0.5");
        makeJdkInstall(jdkHome, "21.0.5");

        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.remove("temurin-21.0.5")).isTrue();
        assertThat(jdkHome).doesNotExist();

        assertThat(registry.remove("not-installed")).isFalse();
    }

    /** A {@link JdkRegistry} backed by a single {@link JetbrainsProbe} rooted at {@code root} — isolates the test from any JDKs actually installed on the host. */
    private static JdkRegistry isolatedRegistry(Path root) {
        return new JdkRegistry(root, List.of(new JetbrainsProbe(root)));
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"Eclipse Adoptium\"\n");
    }
}
