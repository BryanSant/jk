// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkConfigLoaderTest {

    @Test
    void empty_returns_all_optionals_empty() {
        JkConfig empty = JkConfig.empty();
        assertThat(empty.color()).isEmpty();
        assertThat(empty.offline()).isEmpty();
        assertThat(empty.directory()).isEmpty();
    }

    @Test
    void mergedWith_overlays_set_values() {
        JkConfig base = new JkConfig(
                Optional.of(JkConfig.ColorChoice.NEVER),
                Optional.of(true),
                Optional.empty(), // rerun
                Optional.empty(), // refresh
                Optional.empty(), // noProgress
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(), // force
                Optional.empty()); // noAnsi
        JkConfig over = new JkConfig(
                Optional.of(JkConfig.ColorChoice.ALWAYS),
                Optional.empty(),
                Optional.empty(), // rerun
                Optional.empty(), // refresh
                Optional.of(true), // noProgress — over sets it
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(), // force
                Optional.empty()); // noAnsi
        JkConfig merged = base.mergedWith(over);
        assertThat(merged.color()).hasValue(JkConfig.ColorChoice.ALWAYS); // over wins
        assertThat(merged.offline()).hasValue(true); // base passes through
        assertThat(merged.noProgress()).hasValue(true); // over sets it
    }

    @Test
    void parses_project_toml_config_section(@TempDir Path tempDir) throws IOException {
        Path toml = tempDir.resolve("jk.toml");
        Files.writeString(toml, """
                [config]
                color = "always"
                offline = true
                quiet = false
                """);
        JkConfig loaded = JkConfigLoader.loadTomlOrEmpty(toml);
        assertThat(loaded.color()).hasValue(JkConfig.ColorChoice.ALWAYS);
        assertThat(loaded.offline()).hasValue(true);
        assertThat(loaded.quiet()).hasValue(false);
    }

    @Test
    void env_var_color_overrides_no_color() {
        // JK_COLOR=auto, NO_COLOR=1 — explicit JK_COLOR wins.
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(Map.of(
                "JK_COLOR", "auto",
                "NO_COLOR", "1")::get);
        assertThat(fromEnv.color()).hasValue(JkConfig.ColorChoice.AUTO);
    }

    @Test
    void env_var_no_color_alone_means_never() {
        JkConfig fromEnv = JkConfigLoader.loadFromEnv(Map.of("NO_COLOR", "1")::get);
        assertThat(fromEnv.color()).hasValue(JkConfig.ColorChoice.NEVER);
    }

    @Test
    void env_var_boolean_truthy_values() {
        JkConfig env = JkConfigLoader.loadFromEnv(Map.of(
                "JK_OFFLINE", "true",
                "JK_NO_PROGRESS", "1",
                "JK_QUIET", "yes",
                "JK_VERBOSE", "off")::get);
        assertThat(env.offline()).hasValue(true);
        assertThat(env.noProgress()).hasValue(true);
        assertThat(env.quiet()).hasValue(true);
        assertThat(env.verbose()).hasValue(false);
    }

    @Test
    void find_project_config_walks_upward(@TempDir Path tempDir) throws IOException {
        Path nested = tempDir.resolve("a").resolve("b").resolve("c");
        Files.createDirectories(nested);
        Path projectToml = tempDir.resolve("a").resolve("jk.toml");
        Files.writeString(projectToml, "[config]\ncolor = \"always\"\n");

        Path found = ConfigSources.findProjectConfig(nested);
        assertThat(found).isEqualTo(projectToml);
    }

    @Test
    void no_config_short_circuits_files_but_keeps_env(@TempDir Path tempDir) throws IOException {
        Path projectToml = tempDir.resolve("jk.toml");
        Files.writeString(projectToml, "[config]\ncolor = \"always\"\n");

        JkConfig loaded = JkConfigLoader.load(tempDir, /* noConfig= */ true, Optional.empty());
        // Project config was IGNORED.
        assertThat(loaded.color()).isEmpty();
    }
}
