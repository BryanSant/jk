// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkHistoryConfigTest {

    private static Function<String, String> env(Map<String, String> m) {
        return m::get;
    }

    @Test
    void missing_file_uses_defaults() {
        assertThat(JkHistoryConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkHistoryConfig.DEFAULTS);
        assertThat(JkHistoryConfig.DEFAULTS.enabled()).isTrue();
        assertThat(JkHistoryConfig.DEFAULTS.maxAgeDays()).isEqualTo(30);
        assertThat(JkHistoryConfig.DEFAULTS.maxDiskMb()).isEqualTo(512);
    }

    @Test
    void table_absent_uses_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\n");
        assertThat(JkHistoryConfig.fromToml(toml)).isEqualTo(JkHistoryConfig.DEFAULTS);
    }

    @Test
    void parses_all_keys(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[history]\nenabled = false\nmax-age-days = 7\nmax-disk-mb = 128\n");
        JkHistoryConfig c = JkHistoryConfig.fromToml(toml);
        assertThat(c.enabled()).isFalse();
        assertThat(c.maxAgeDays()).isEqualTo(7);
        assertThat(c.maxDiskMb()).isEqualTo(128);
    }

    @Test
    void negative_values_fall_back_to_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[history]\nmax-age-days = -5\nmax-disk-mb = -1\n");
        JkHistoryConfig c = JkHistoryConfig.fromToml(toml);
        assertThat(c.maxAgeDays()).isEqualTo(30);
        assertThat(c.maxDiskMb()).isEqualTo(512);
    }

    @Test
    void env_overrides_file(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[history]\nenabled = true\nmax-age-days = 30\nmax-disk-mb = 512\n");
        JkHistoryConfig c = JkHistoryConfig.resolve(
                toml,
                env(Map.of(
                        "JK_HISTORY_ENABLED", "false",
                        "JK_HISTORY_MAX_AGE_DAYS", "3",
                        "JK_HISTORY_MAX_DISK_MB", "64")));
        assertThat(c.enabled()).isFalse();
        assertThat(c.maxAgeDays()).isEqualTo(3);
        assertThat(c.maxDiskMb()).isEqualTo(64);
    }

    @Test
    void zero_means_unbounded() {
        JkHistoryConfig c = new JkHistoryConfig(true, 0, 0);
        assertThat(c.maxAgeMillis()).isZero();
        assertThat(c.maxDiskBytes()).isZero();
    }

    @Test
    void byte_and_millis_conversions() {
        JkHistoryConfig c = new JkHistoryConfig(true, 2, 10);
        assertThat(c.maxAgeMillis()).isEqualTo(2L * 86_400_000L);
        assertThat(c.maxDiskBytes()).isEqualTo(10L * 1024 * 1024);
    }
}
