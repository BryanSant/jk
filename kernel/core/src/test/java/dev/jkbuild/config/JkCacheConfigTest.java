// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JkCacheConfigTest {

    @Test
    void missing_file_yields_defaults() throws IOException {
        assertThat(JkCacheConfig.fromToml(Path.of("/no/such/file")))
                .isEqualTo(JkCacheConfig.DEFAULTS);
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
    void overlay_lets_user_global_provide_max_size(@TempDir Path tempDir) {
        JkCacheConfig project = new JkCacheConfig(true, java.util.Optional.empty(), 7, 30);
        JkCacheConfig user = new JkCacheConfig(false, java.util.Optional.of(20), 7, 30);
        // Project wins on autoPrune; missing maxSize falls through to user.
        JkCacheConfig merged = project.overlay(user);
        assertThat(merged.autoPrune()).isTrue();
        assertThat(merged.maxSizeGb()).hasValue(20);
    }
}
