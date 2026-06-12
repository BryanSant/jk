// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure segmented-bar string renderer (no cursor/threads). */
class ProgressBarTest {

    @Test
    void renders_segments_percent_and_count() {
        String line = new ProgressBar().render(45, 100);
        String visible = stripAnsi(line);
        // 45% → round(0.45 * 40) = 18 filled, 22 empty.
        assertThat(visible).startsWith("▰".repeat(18) + "▱".repeat(22));
        assertThat(visible).contains("45%");
        assertThat(visible).contains("[45 of 100]");
    }

    @Test
    void zero_is_all_empty_and_full_is_all_filled() {
        assertThat(stripAnsi(new ProgressBar().render(0, 100)))
                .startsWith("▱".repeat(40)).contains("0%").contains("[0 of 100]");
        assertThat(stripAnsi(new ProgressBar().render(100, 100)))
                .startsWith("▰".repeat(40)).contains("100%").contains("[100 of 100]");
    }

    @Test
    void brackets_are_bright_black() {
        // darkGray = Jk Dark BRIGHT_BLACK #546E7A = 84;110;122.
        String line = new ProgressBar().render(1, 4);
        assertThat(line).contains("\033[38;2;84;110;122m[");
        assertThat(line).contains("\033[38;2;84;110;122m]");
    }

    @Test
    void fraction_helpers_clamp_and_guard_zero_denominator() {
        assertThat(ProgressBar.fraction(0, 0)).isZero();          // no divide-by-zero
        assertThat(ProgressBar.fraction(5, 10)).isEqualTo(0.5);
        assertThat(ProgressBar.fraction(20, 10)).isEqualTo(1.0);  // clamped
        assertThat(ProgressBar.percent(1, 3)).isEqualTo(33);
        assertThat(ProgressBar.filled(1, 2)).isEqualTo(20);
    }

    @Test
    void frontier_glyph_is_the_gradient_end() {
        // Jk Dark green #4CAF50 spanning −50% → +50%; the right-most filled glyph
        // is pinned to the bright end (#4CAF50 × 1.50, green clamped) at every fill.
        assertThat(frontierColor(new ProgressBar().render(2, 100)))   // 1 filled
                .isEqualTo("38;2;114;255;120");
        assertThat(frontierColor(new ProgressBar().render(100, 100))) // 40 filled
                .isEqualTo("38;2;114;255;120");
    }

    /** SGR of the right-most filled (▰) glyph in {@code raw}, or null. */
    private static String frontierColor(String raw) {
        var m = java.util.regex.Pattern
                .compile("\033\\[(38;2;\\d+;\\d+;\\d+)m▰")
                .matcher(raw);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
