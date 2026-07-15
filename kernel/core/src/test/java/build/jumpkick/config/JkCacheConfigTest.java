// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkCacheConfigTest {

    @Test
    void missing_file_yields_defaults() throws IOException {
        assertThat(JkCacheConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void parses_full_cache_table(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, """
                [cache]
                auto-prune          = true
                max-size-gb         = 25
                prune-interval-days = 14
                record-ttl-days     = 45
                """);

        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.autoPrune()).isTrue();
        assertThat(c.maxSizeGb()).hasValue(25);
        assertThat(c.pruneIntervalDays()).isEqualTo(14);
        assertThat(c.recordTtlDays()).isEqualTo(45);
    }

    @Test
    void missing_keys_fall_back_to_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, """
                [cache]
                auto-prune = true
                """);

        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.autoPrune()).isTrue();
        assertThat(c.maxSizeGb()).isEmpty();
        assertThat(c.pruneIntervalDays()).isEqualTo(JkCacheConfig.DEFAULTS.pruneIntervalDays());
        assertThat(c.recordTtlDays()).isEqualTo(JkCacheConfig.DEFAULTS.recordTtlDays());
    }

    @Test
    void table_absent_yields_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, """
                [project]
                group = "x"
                """);
        assertThat(JkCacheConfig.fromToml(toml)).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void env_vars_override_user_config(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [cache]
                auto-prune          = true
                max-size-gb         = 25
                prune-interval-days = 14
                record-ttl-days     = 45
                """);
        var env = Map.of(
                "JK_AUTO_PRUNE", "false",
                "JK_MAX_SIZE_GB", "100",
                "JK_PRUNE_INTERVAL_DAYS", "1",
                "JK_RECORD_TTL_DAYS", "7");

        JkCacheConfig c = JkCacheConfig.resolve(toml, env::get);
        assertThat(c.autoPrune()).isFalse(); // env wins over file
        assertThat(c.maxSizeGb()).hasValue(100);
        assertThat(c.pruneIntervalDays()).isEqualTo(1);
        assertThat(c.recordTtlDays()).isEqualTo(7);
    }

    @Test
    void env_falls_through_to_file_then_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-size-gb = 25\n");

        // No env set: file value for max-size, defaults for the rest.
        JkCacheConfig c = JkCacheConfig.resolve(toml, name -> null);
        assertThat(c.maxSizeGb()).hasValue(25);
        assertThat(c.autoPrune()).isEqualTo(JkCacheConfig.DEFAULTS.autoPrune());
        assertThat(c.pruneIntervalDays()).isEqualTo(JkCacheConfig.DEFAULTS.pruneIntervalDays());

        // env can supply max-size even when the file omits it entirely.
        JkCacheConfig c2 = JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("JK_MAX_SIZE_GB", "50")::get);
        assertThat(c2.maxSizeGb()).hasValue(50);

        // Garbage env value is ignored — falls through to the file/default.
        JkCacheConfig c3 = JkCacheConfig.resolve(toml, Map.of("JK_MAX_SIZE_GB", "huge")::get);
        assertThat(c3.maxSizeGb()).hasValue(25);
    }
}
