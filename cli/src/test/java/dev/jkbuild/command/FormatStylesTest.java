// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.model.JkBuild;
import org.junit.jupiter.api.Test;

class FormatStylesTest {

    private static final JkBuild.FormatConfig EMPTY = JkBuild.FormatConfig.EMPTY;

    @Test
    void default_is_palantir_and_kotlinlang() {
        var r = FormatStyles.resolve(null, null, null, null, EMPTY);
        assertThat(r.java()).isEqualTo("palantir");
        assertThat(r.kotlin()).isEqualTo("kotlinlang");
    }

    @Test
    void cli_flags_win_over_everything() {
        var cfg = new JkBuild.FormatConfig("standard", "palantir", "kotlinlang", null);
        var r = FormatStyles.resolve("google", "meta", "standard", null, cfg);
        assertThat(r.java()).isEqualTo("google");
        assertThat(r.kotlin()).isEqualTo("meta");
    }

    @Test
    void cli_style_alias_sets_both() {
        var r = FormatStyles.resolve(null, null, "standard", null, EMPTY);
        assertThat(r.java()).isEqualTo("palantir");
        assertThat(r.kotlin()).isEqualTo("kotlinlang");
    }

    @Test
    void toml_per_language_overrides_default_and_alias() {
        var cfg = new JkBuild.FormatConfig("standard", "google", null, null);
        var r = FormatStyles.resolve(null, null, null, null, cfg);
        assertThat(r.java()).isEqualTo("google"); // format.java wins over format.style alias
        assertThat(r.kotlin()).isEqualTo("kotlinlang"); // falls back to alias-derived
    }

    @Test
    void unknown_style_is_rejected() {
        assertThatThrownBy(() -> FormatStyles.resolve("clang", null, null, null, EMPTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown java format style");
    }

    @Test
    void unknown_alias_is_rejected() {
        assertThatThrownBy(() -> FormatStyles.resolve(null, null, "bogus", null, EMPTY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a known style preset");
    }

    @Test
    void optimize_imports_defaults_true_but_cli_flag_and_toml_can_override() {
        assertThat(FormatStyles.resolve(null, null, null, null, EMPTY).optimizeImports())
                .isTrue();

        var cfg = new JkBuild.FormatConfig(null, null, null, false);
        assertThat(FormatStyles.resolve(null, null, null, null, cfg).optimizeImports())
                .isFalse(); // jk.toml wins over the default

        assertThat(FormatStyles.resolve(null, null, null, true, cfg).optimizeImports())
                .isTrue(); // CLI flag wins over jk.toml
    }
}
