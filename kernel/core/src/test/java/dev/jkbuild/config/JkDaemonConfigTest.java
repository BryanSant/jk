// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkDaemonConfigTest {

    @Test
    void missing_file_yields_defaults() {
        assertThat(JkDaemonConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkDaemonConfig.DEFAULTS);
        assertThat(JkDaemonConfig.DEFAULTS.idleMinutes()).isEqualTo(120);
    }

    @Test
    void table_absent_yields_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[project]\ngroup = \"x\"\n");
        assertThat(JkDaemonConfig.fromToml(toml)).isEqualTo(JkDaemonConfig.DEFAULTS);
    }

    @Test
    void parses_idle_minutes(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[daemon]\nidle-minutes = 30\n");
        assertThat(JkDaemonConfig.fromToml(toml).idleMinutes()).isEqualTo(30);
    }

    @Test
    void zero_means_exit_as_soon_as_idle(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[daemon]\nidle-minutes = 0\n");
        JkDaemonConfig c = JkDaemonConfig.fromToml(toml);
        assertThat(c.exitAsSoonAsIdle()).isTrue();
        assertThat(c.neverExpires()).isFalse();
    }

    @Test
    void minus_one_means_never_expires(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[daemon]\nidle-minutes = -1\n");
        JkDaemonConfig c = JkDaemonConfig.fromToml(toml);
        assertThat(c.neverExpires()).isTrue();
        assertThat(c.exitAsSoonAsIdle()).isFalse();
    }

    @Test
    void below_minus_one_is_invalid_and_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[daemon]\nidle-minutes = -2\n");
        assertThat(JkDaemonConfig.fromToml(toml).idleMinutes()).isEqualTo(JkDaemonConfig.DEFAULT_IDLE_MINUTES);
    }

    @Test
    void env_overrides_file() throws IOException {
        Path toml = Files.createTempFile("jk-daemon-", ".toml");
        try {
            Files.writeString(toml, "[daemon]\nidle-minutes = 30\n");
            JkDaemonConfig c = JkDaemonConfig.resolve(toml, Map.of("JK_DAEMON_IDLE_MINUTES", "-1")::get);
            assertThat(c.idleMinutes()).isEqualTo(-1);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void garbage_env_value_falls_through_to_file() throws IOException {
        Path toml = Files.createTempFile("jk-daemon-", ".toml");
        try {
            Files.writeString(toml, "[daemon]\nidle-minutes = 45\n");
            JkDaemonConfig c = JkDaemonConfig.resolve(toml, Map.of("JK_DAEMON_IDLE_MINUTES", "soon")::get);
            assertThat(c.idleMinutes()).isEqualTo(45);
        } finally {
            Files.deleteIfExists(toml);
        }
    }
}
