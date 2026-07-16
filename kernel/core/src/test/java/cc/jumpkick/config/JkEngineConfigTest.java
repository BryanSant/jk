// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkEngineConfigTest {

    @Test
    void missing_file_yields_defaults() {
        assertThat(JkEngineConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkEngineConfig.DEFAULTS);
        assertThat(JkEngineConfig.DEFAULTS.maxHeapMb()).isEqualTo(JkEngineConfig.DEFAULT_MAX_HEAP_MB);
    }

    @Test
    void table_absent_yields_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[project]\ngroup = \"x\"\n");
        assertThat(JkEngineConfig.fromToml(toml)).isEqualTo(JkEngineConfig.DEFAULTS);
    }

    @Test
    void parses_max_heap_mb(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 1024\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.maxHeapMb()).isEqualTo(1024);
        assertThat(c.heapCapped()).isTrue();
    }

    @Test
    void zero_max_heap_means_uncapped(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 0\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.maxHeapMb()).isZero();
        assertThat(c.heapCapped()).isFalse();
    }

    @Test
    void negative_max_heap_is_invalid_and_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = -64\n");
        assertThat(JkEngineConfig.fromToml(toml).maxHeapMb()).isEqualTo(JkEngineConfig.DEFAULT_MAX_HEAP_MB);
    }

    @Test
    void max_heap_env_overrides_file() throws IOException {
        Path toml = Files.createTempFile("jk-engine-", ".toml");
        try {
            Files.writeString(toml, "[engine]\nmax-heap-mb = 1024\n");
            JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_MAX_HEAP_MB", "256")::get);
            assertThat(c.maxHeapMb()).isEqualTo(256);
        } finally {
            Files.deleteIfExists(toml);
        }
    }
}
