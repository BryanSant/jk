// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.theme.Gradient;
import dev.jkbuild.cli.theme.JkDarkTheme;
import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class GradientTest {

    private static final Rgb ORANGE = Rgb.hex(0xff8b1a);
    private static final Rgb MAGENTA = Rgb.hex(0xe600ff);

    @Test
    void rgb_hex_unpacks_channels() {
        assertThat(Rgb.hex(0xff8b1a)).isEqualTo(new Rgb(0xff, 0x8b, 0x1a));
    }

    @Test
    void endpoints_and_midpoint() {
        Gradient g = new Gradient(ORANGE, MAGENTA);
        assertThat(g.at(0.0)).isEqualTo(ORANGE);
        assertThat(g.at(1.0)).isEqualTo(MAGENTA);
        // Midpoint is the rounded average of each channel.
        assertThat(g.at(0.5))
                .isEqualTo(new Rgb(
                        Math.round((0xff + 0xe6) / 2f),
                        Math.round((0x8b + 0x00) / 2f),
                        Math.round((0x1a + 0xff) / 2f)));
    }

    @Test
    void t_is_clamped() {
        Gradient g = new Gradient(ORANGE, MAGENTA);
        assertThat(g.at(-1.0)).isEqualTo(ORANGE);
        assertThat(g.at(2.0)).isEqualTo(MAGENTA);
    }

    @Test
    void reversed_swaps_ends() {
        Gradient g = new Gradient(ORANGE, MAGENTA);
        assertThat(g.reversed()).isEqualTo(new Gradient(MAGENTA, ORANGE));
        assertThat(g.reversed().at(0.0)).isEqualTo(MAGENTA);
    }

    @Test
    void title_runs_bright_blue_to_accent() {
        assertThat(Theme.active().titleGradient()).isEqualTo(new Gradient(JkDarkTheme.BRIGHT_BLUE, JkDarkTheme.ACCENT));
    }

    @Test
    void rgb_darker_and_brighter_scale_and_clamp() {
        // #4CAF50 (76,175,80) × 0.70 ; #69F0AE (105,240,174) × 1.10 (G clamps).
        assertThat(JkDarkTheme.NORMAL_GREEN.darker(0.30)).isEqualTo(new Rgb(53, 122, 56));
        assertThat(JkDarkTheme.BRIGHT_GREEN.brighter(0.10)).isEqualTo(new Rgb(116, 255, 191));
    }

    @Test
    void progress_runs_indigo_to_bright_magenta() {
        // The build/progress fill: indigo #3F51B5 → bright-magenta #E040FB.
        assertThat(Theme.active().progressGradient())
                .isEqualTo(new Gradient(JkDarkTheme.PRIMARY, JkDarkTheme.BRIGHT_MAGENTA));
    }

    @Test
    void spinner_runs_primary_to_accent() {
        assertThat(Theme.active().spinnerGradient()).isEqualTo(new Gradient(JkDarkTheme.PRIMARY, JkDarkTheme.ACCENT));
        assertThat(Theme.active().spinnerGradient()).isNotEqualTo(Theme.active().progressGradient());
    }
}
