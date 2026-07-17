// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkHttpConfigTest {

    @Test
    void missing_file_means_enabled_with_defaults() {
        assertThat(JkHttpConfig.fromToml(Path.of("/no/such/file"))).contains(JkHttpConfig.DEFAULTS);
    }

    @Test
    void table_absent_means_enabled_with_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[engine]\nmax-heap-mb = 256\n");
        assertThat(JkHttpConfig.fromToml(toml)).contains(JkHttpConfig.DEFAULTS);
    }

    @Test
    void enabled_false_disables(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nenabled = false\nport = 9000\n");
        assertThat(JkHttpConfig.fromToml(toml)).isEmpty();
    }

    @Test
    void explicit_enabled_true_keeps_the_tables_values(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nenabled = true\nport = 9000\n");
        assertThat(JkHttpConfig.fromToml(toml).orElseThrow().port()).isEqualTo(9000);
    }

    @Test
    void wrong_typed_enabled_falls_back_to_on(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nenabled = \"nope\"\n");
        assertThat(JkHttpConfig.fromToml(toml)).contains(JkHttpConfig.DEFAULTS);
    }

    @Test
    void empty_table_enables_with_all_defaults(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\n");
        assertThat(JkHttpConfig.fromToml(toml)).contains(JkHttpConfig.DEFAULTS);
        assertThat(JkHttpConfig.DEFAULTS.host()).isEqualTo("127.0.0.1");
        assertThat(JkHttpConfig.DEFAULTS.port()).isEqualTo(8910);
        assertThat(JkHttpConfig.DEFAULTS.maxConcurrentRequests()).isEqualTo(16);
        assertThat(JkHttpConfig.DEFAULTS.webRoot()).isEqualTo("state/web");
    }

    @Test
    void parses_all_keys(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [http]
                host = "0.0.0.0"
                port = 8911
                max-concurrent-requests = 4
                web-root = "/srv/jk-web"
                """);
        JkHttpConfig c = JkHttpConfig.fromToml(toml).orElseThrow();
        assertThat(c.host()).isEqualTo("0.0.0.0");
        assertThat(c.port()).isEqualTo(8911);
        assertThat(c.maxConcurrentRequests()).isEqualTo(4);
        assertThat(c.webRoot()).isEqualTo("/srv/jk-web");
    }

    @Test
    void out_of_range_port_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nport = 70000\n");
        assertThat(JkHttpConfig.fromToml(toml).orElseThrow().port()).isEqualTo(JkHttpConfig.DEFAULT_PORT);
    }

    @Test
    void negative_port_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nport = -1\n");
        assertThat(JkHttpConfig.fromToml(toml).orElseThrow().port()).isEqualTo(JkHttpConfig.DEFAULT_PORT);
    }

    @Test
    void port_zero_means_os_assigned_and_is_kept(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nport = 0\n");
        assertThat(JkHttpConfig.fromToml(toml).orElseThrow().port()).isZero();
    }

    @Test
    void negative_max_concurrent_requests_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nmax-concurrent-requests = -4\n");
        assertThat(JkHttpConfig.fromToml(toml).orElseThrow().maxConcurrentRequests())
                .isEqualTo(JkHttpConfig.DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    @Test
    void zero_max_concurrent_requests_means_core_count(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nmax-concurrent-requests = 0\n");
        JkHttpConfig c = JkHttpConfig.fromToml(toml).orElseThrow();
        assertThat(c.maxConcurrentRequests()).isZero();
        assertThat(c.effectiveMaxConcurrentRequests())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void positive_max_concurrent_requests_is_effective_as_is() {
        assertThat(JkHttpConfig.DEFAULTS.effectiveMaxConcurrentRequests())
                .isEqualTo(JkHttpConfig.DEFAULT_MAX_CONCURRENT_REQUESTS);
    }

    @Test
    void wrong_typed_key_falls_back_to_default(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http]\nhost = 8910\nport = \"eight-nine-one-oh\"\n");
        JkHttpConfig c = JkHttpConfig.fromToml(toml).orElseThrow();
        assertThat(c.host()).isEqualTo(JkHttpConfig.DEFAULT_HOST);
        assertThat(c.port()).isEqualTo(JkHttpConfig.DEFAULT_PORT);
    }

    @Test
    void malformed_file_means_disabled(@TempDir Path tempDir) throws IOException {
        // The unreadable file may contain an `enabled = false` we can't see — a disable must fail
        // closed, so an existing-but-unparseable config never silently serves.
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[http\nport = 8911\n");
        assertThat(JkHttpConfig.fromToml(toml)).isEmpty();
    }

    @Test
    void env_enabled_false_disables_even_with_a_healthy_table() throws IOException {
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[http]\nport = 8911\n");
            assertThat(JkHttpConfig.resolve(toml, Map.of("JK_HTTP_ENABLED", "false")::get))
                    .isEmpty();
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void env_enabled_true_wins_over_a_file_disable() throws IOException {
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[http]\nenabled = false\nport = 9000\n");
            // env > user-config; the file's other keys are gone with the disable, so defaults serve.
            assertThat(JkHttpConfig.resolve(toml, Map.of("JK_HTTP_ENABLED", "true")::get))
                    .contains(JkHttpConfig.DEFAULTS);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void resolve_defaults_to_enabled_with_no_file_and_no_env() {
        assertThat(JkHttpConfig.resolve(Path.of("/no/such/file"), k -> null)).contains(JkHttpConfig.DEFAULTS);
    }

    @Test
    void env_overrides_file() throws IOException {
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[http]\nport = 8911\nhost = \"0.0.0.0\"\n");
            JkHttpConfig c = JkHttpConfig.resolve(
                            toml, Map.of("JK_HTTP_PORT", "9000", "JK_HTTP_HOST", "127.0.0.1")::get)
                    .orElseThrow();
            assertThat(c.port()).isEqualTo(9000);
            assertThat(c.host()).isEqualTo("127.0.0.1");
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void env_keys_apply_on_top_of_the_default_on_state() throws IOException {
        // No [http] table needed anymore: the server is on by default, and JK_HTTP_* env keys
        // customize that default just as they customize a table's values.
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[engine]\nmax-heap-mb = 256\n");
            JkHttpConfig c = JkHttpConfig.resolve(toml, Map.of("JK_HTTP_PORT", "9000")::get)
                    .orElseThrow();
            assertThat(c.port()).isEqualTo(9000);
            assertThat(c.host()).isEqualTo(JkHttpConfig.DEFAULT_HOST);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void garbage_env_value_falls_through_to_file() throws IOException {
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[http]\nport = 8911\n");
            JkHttpConfig c = JkHttpConfig.resolve(toml, Map.of("JK_HTTP_PORT", "many")::get)
                    .orElseThrow();
            assertThat(c.port()).isEqualTo(8911);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void out_of_range_env_port_falls_through_to_file() throws IOException {
        Path toml = Files.createTempFile("jk-http-", ".toml");
        try {
            Files.writeString(toml, "[http]\nport = 8911\n");
            JkHttpConfig c = JkHttpConfig.resolve(toml, Map.of("JK_HTTP_PORT", "70000")::get)
                    .orElseThrow();
            assertThat(c.port()).isEqualTo(8911);
        } finally {
            Files.deleteIfExists(toml);
        }
    }

    @Test
    void relative_web_root_resolves_against_home(@TempDir Path tempDir) {
        assertThat(JkHttpConfig.DEFAULTS.webRootPath(tempDir)).isEqualTo(tempDir.resolve("state/web"));
    }

    @Test
    void absolute_web_root_ignores_home(@TempDir Path tempDir) {
        JkHttpConfig c = new JkHttpConfig("127.0.0.1", 8910, 16, "/srv/jk-web");
        assertThat(c.webRootPath(tempDir)).isEqualTo(Path.of("/srv/jk-web"));
    }
}
