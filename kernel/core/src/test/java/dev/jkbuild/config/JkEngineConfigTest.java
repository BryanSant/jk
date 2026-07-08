// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

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
        assertThat(JkEngineConfig.DEFAULTS.idleMinutes()).isEqualTo(120);
    }

    @Test
    void table_absent_yields_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[project]\ngroup = \"x\"\n");
        assertThat(JkEngineConfig.fromToml(toml)).isEqualTo(JkEngineConfig.DEFAULTS);
    }

    @Test
    void parses_idle_minutes(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nidle-minutes = 30\n");
        assertThat(JkEngineConfig.fromToml(toml).idleMinutes()).isEqualTo(30);
    }

    @Test
    void zero_means_exit_as_soon_as_idle(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nidle-minutes = 0\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.exitAsSoonAsIdle()).isTrue();
        assertThat(c.neverExpires()).isFalse();
    }

    @Test
    void minus_one_means_never_expires(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nidle-minutes = -1\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.neverExpires()).isTrue();
        assertThat(c.exitAsSoonAsIdle()).isFalse();
    }

    @Test
    void below_minus_one_is_invalid_and_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nidle-minutes = -2\n");
        assertThat(JkEngineConfig.fromToml(toml).idleMinutes()).isEqualTo(JkEngineConfig.DEFAULT_IDLE_MINUTES);
    }

    @Test
    void env_overrides_file() throws IOException {
        Path toml = Files.createTempFile("jk-engine-", ".toml");
        try {
            Files.writeString(toml, "[engine]\nidle-minutes = 30\n");
            JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_IDLE_MINUTES", "-1")::get);
            assertThat(c.idleMinutes()).isEqualTo(-1);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void garbage_env_value_falls_through_to_file() throws IOException {
        Path toml = Files.createTempFile("jk-engine-", ".toml");
        try {
            Files.writeString(toml, "[engine]\nidle-minutes = 45\n");
            JkEngineConfig c = JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_IDLE_MINUTES", "soon")::get);
            assertThat(c.idleMinutes()).isEqualTo(45);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void parses_max_heap_mb(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 1024\n");
        JkEngineConfig c = JkEngineConfig.fromToml(toml);
        assertThat(c.maxHeapMb()).isEqualTo(1024);
        assertThat(c.heapCapped()).isTrue();
        assertThat(c.idleMinutes()).isEqualTo(JkEngineConfig.DEFAULT_IDLE_MINUTES); // untouched
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
