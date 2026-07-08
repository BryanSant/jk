// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The pure segmented-bar string renderer (no cursor/threads). */
class ProgressBarTest {

    @Test
    void renders_blocks_then_spaces_then_percent_without_a_count() {
        String visible = stripAnsi(new ProgressBar().render(45, 100));
        // 45% → 0.45 * 40 = 18.0 whole cells, no fraction, 22 unreached spaces.
        assertThat(visible).startsWith("█".repeat(18) + " ".repeat(22));
        assertThat(visible).contains("45%");
        // The N-of-M count is gone.
        assertThat(visible).doesNotContain("[").doesNotContain(" of ");
    }

    @Test
    void fractional_frontier_uses_an_eighth_block() {
        // 2% → 0.8 of a cell → round(0.8*8)=6 eighths → ▊ (¾ block) as the first cell.
        String visible = stripAnsi(new ProgressBar().render(2, 100));
        assertThat(visible).startsWith("▊");
        assertThat(visible).doesNotContain("█"); // no whole cell yet
        assertThat(visible).contains("2%");
    }

    @Test
    void zero_is_all_spaces_and_full_is_all_blocks() {
        assertThat(stripAnsi(new ProgressBar().render(0, 100)))
                .startsWith(" ".repeat(40))
                .contains("0%")
                .doesNotContain("█");
        assertThat(stripAnsi(new ProgressBar().render(100, 100)))
                .startsWith("█".repeat(40))
                .contains("100%");
    }

    @Test
    void every_cell_is_underlined_including_the_unreached_spaces() {
        // 0% → 40 underlined spaces in the gradient's brightest (right-most) color.
        String line = new ProgressBar().render(0, 100);
        // attribute-leading SGR: underline (4) before the truecolor group; the
        // gradient's right-most end is now bright-magenta #E040FB → 224;64;251.
        assertThat(line).contains("\033[4;38;2;224;64;251m ");
    }

    @Test
    void fraction_helpers_clamp_and_guard_zero_denominator() {
        assertThat(ProgressBar.fraction(0, 0)).isZero(); // no divide-by-zero
        assertThat(ProgressBar.fraction(5, 10)).isEqualTo(0.5);
        assertThat(ProgressBar.fraction(20, 10)).isEqualTo(1.0); // clamped
        assertThat(ProgressBar.percent(1, 3)).isEqualTo(33);
        assertThat(ProgressBar.filled(1, 2)).isEqualTo(20);
    }

    @Test
    void moving_gradient_pins_the_frontier_to_the_bright_end() {
        // The right-most filled block is pinned to the gradient end at every fill,
        // and at 100% the left-most block sits at the gradient start.
        assertThat(blockColors(new ProgressBar().render(50, 100)).getLast())
                .isEqualTo("38;2;224;64;251"); // bright-magenta end
        List<String> full = blockColors(new ProgressBar().render(100, 100));
        assertThat(full.getLast()).isEqualTo("38;2;224;64;251"); // bright-magenta end on the right
        assertThat(full.getFirst()).isEqualTo("38;2;63;81;181"); // indigo start on the left
    }

    /** In-order truecolor SGRs of each whole-cell (█) glyph in {@code raw}. */
    private static List<String> blockColors(String raw) {
        var m = Pattern.compile("(38;2;\\d+;\\d+;\\d+)m█").matcher(raw);
        var out = new ArrayList<String>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
