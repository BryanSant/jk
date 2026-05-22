// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkRegistryTest {

    @Test
    void list_returns_immediate_subdirectories(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("21.0.5-tem-x64-linux"));
        Files.createDirectories(tempDir.resolve("21.0.2-graalce-x64-linux"));
        Files.writeString(tempDir.resolve("README.txt"), "ignored");

        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.list())
                .extracting(InstalledJdk::identifier)
                .containsExactly("21.0.2-graalce-x64-linux", "21.0.5-tem-x64-linux");
    }

    @Test
    void missing_root_returns_empty_list(@TempDir Path tempDir) throws IOException {
        Path nonexistent = tempDir.resolve("nope");
        assertThat(new JdkRegistry(nonexistent).list()).isEmpty();
    }

    @Test
    void find_by_prefix(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("21.0.5-tem-x64-linux"));
        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.findByPrefix("21.0.5-tem"))
                .isPresent()
                .get().extracting(InstalledJdk::identifier)
                .isEqualTo("21.0.5-tem-x64-linux");
    }

    @Test
    void remove_deletes_directory_tree(@TempDir Path tempDir) throws IOException {
        Path jdkHome = tempDir.resolve("21.0.5-tem-x64-linux");
        Files.createDirectories(jdkHome.resolve("bin"));
        Files.writeString(jdkHome.resolve("bin/java"), "#!/fake");

        JdkRegistry registry = new JdkRegistry(tempDir);
        assertThat(registry.remove("21.0.5-tem-x64-linux")).isTrue();
        assertThat(jdkHome).doesNotExist();

        assertThat(registry.remove("not-installed")).isFalse();
    }
}
