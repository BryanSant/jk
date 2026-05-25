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

    @Test
    void find_by_spec_bare_major(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("25"))
                .isPresent()
                .get().extracting(InstalledJdk::identifier).isEqualTo("corretto-25.0.3");
    }

    @Test
    void find_by_spec_vendor_plus_major(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("temurin-25.0.1"), "25.0.1", "Eclipse Adoptium");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("temurin-25"))
                .get().extracting(InstalledJdk::identifier).isEqualTo("temurin-25.0.1");
    }

    @Test
    void find_by_spec_vendor_plus_exact_version(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("corretto-25.0.2"), "25.0.2", "Amazon.com Inc.");
        makeJdkInstall(tempDir.resolve("corretto-25.0.3"), "25.0.3", "Amazon.com Inc.");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("corretto-25.0.3"))
                .get().extracting(InstalledJdk::identifier).isEqualTo("corretto-25.0.3");
    }

    @Test
    void find_by_spec_version_then_sdkman_suffix(@TempDir Path tempDir) throws IOException {
        // librca = liberica's SDKMAN suffix.
        makeJdkInstall(tempDir.resolve("liberica-26.0.1"), "26.0.1", "BellSoft");
        makeJdkInstall(tempDir.resolve("temurin-26.0.1"), "26.0.1", "Eclipse Adoptium");
        JdkRegistry registry = isolatedRegistry(tempDir);
        assertThat(registry.findBySpec("26.0.1-librca"))
                .get().extracting(InstalledJdk::identifier).isEqualTo("liberica-26.0.1");
    }

    @Test
    void find_by_spec_returns_empty_when_no_match(@TempDir Path tempDir) throws IOException {
        makeJdkInstall(tempDir.resolve("temurin-21.0.5"), "21.0.5", "Eclipse Adoptium");
        assertThat(isolatedRegistry(tempDir).findBySpec("corretto-25")).isEmpty();
    }

    /** A {@link JdkRegistry} backed by a single {@link JetbrainsProbe} rooted at {@code root} — isolates the test from any JDKs actually installed on the host. */
    private static JdkRegistry isolatedRegistry(Path root) {
        return new JdkRegistry(root, List.of(new JetbrainsProbe(root)));
    }

    private static void makeJdkInstall(Path home, String version) throws IOException {
        makeJdkInstall(home, version, "Eclipse Adoptium");
    }

    private static void makeJdkInstall(Path home, String version, String implementor) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/fake");
        Files.writeString(home.resolve("release"),
                "JAVA_VERSION=\"" + version + "\"\nIMPLEMENTOR=\"" + implementor + "\"\n");
    }
}
