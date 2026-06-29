// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [global].nerdfont} reading from ~/.jk/config.toml, leniently. */
class GlobalConfigTest {

    @Test
    void reads_global_nerdfont_false(@TempDir Path dir) throws IOException {
        Path cfg = write(dir, "[global]\nnerdfont = false\n");
        assertThat(GlobalConfig.nerdfont(cfg, null)).isFalse();
    }

    @Test
    void defaults_true_when_unset_or_missing(@TempDir Path dir) throws IOException {
        assertThat(GlobalConfig.nerdfont(dir.resolve("nope.toml"), null)).isTrue(); // no file
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\n"), null)).isTrue(); // table, no key
        assertThat(GlobalConfig.nerdfont(write(dir, "[cache]\nauto-prune = true\n"), null))
                .isTrue(); // no [global]
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\nnerdfont = \"yes\"\n"), null))
                .isTrue(); // wrong type
    }

    @Test
    void env_var_overrides_config(@TempDir Path dir) throws IOException {
        Path cfgTrue = write(dir, "[global]\nnerdfont = true\n");
        Path cfgFalse = write(dir, "[global]\nnerdfont = false\n");
        Path noFile = dir.resolve("nope.toml");

        // JK_NERDFONT=false disables regardless of config
        assertThat(GlobalConfig.nerdfont(cfgTrue, "false")).isFalse();
        assertThat(GlobalConfig.nerdfont(cfgTrue, "0")).isFalse();
        assertThat(GlobalConfig.nerdfont(cfgTrue, "FALSE")).isFalse();
        assertThat(GlobalConfig.nerdfont(noFile, "false")).isFalse();

        // JK_NERDFONT=true enables regardless of config (jk-wide truth set)
        assertThat(GlobalConfig.nerdfont(cfgFalse, "true")).isTrue();
        assertThat(GlobalConfig.nerdfont(cfgFalse, "1")).isTrue();
        assertThat(GlobalConfig.nerdfont(cfgFalse, "yes")).isTrue();
        assertThat(GlobalConfig.nerdfont(cfgFalse, "on")).isTrue();
        assertThat(GlobalConfig.nerdfont(noFile, "true")).isTrue();

        // unrecognised value falls through to config/default
        assertThat(GlobalConfig.nerdfont(cfgFalse, "maybe")).isFalse();
        assertThat(GlobalConfig.nerdfont(noFile, "maybe")).isTrue();
    }

    @Test
    void color_disabled_degrades_nerdfont_to_false(@TempDir Path dir) throws IOException {
        // colorEnabled=false (--color never / NO_COLOR) must disable Nerd Font:
        // PUA glyphs without color produce misaligned or blank-box output.
        Path cfgNerdTrue  = write(dir, "[global]\nnerdfont = true\n");
        Path cfgNerdFalse = write(dir, "[global]\nnerdfont = false\n");
        assertThat(GlobalConfig.nerdfont(cfgNerdTrue,  null, false)).isFalse();
        assertThat(GlobalConfig.nerdfont(cfgNerdFalse, null, false)).isFalse();
    }

    @Test
    void color_enabled_respects_configured_nerdfont(@TempDir Path dir) throws IOException {
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\nnerdfont = true\n"),  null, true)).isTrue();
        assertThat(GlobalConfig.nerdfont(write(dir, "[global]\nnerdfont = false\n"), null, true)).isFalse();
    }

    private static Path write(Path dir, String content) throws IOException {
        Path f = Files.createTempFile(dir, "config", ".toml");
        Files.writeString(f, content);
        return f;
    }
}
