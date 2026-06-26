// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpinnerProgressBarTest {

    @Test
    void show_hides_cursor() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(0, "Starting...");
        }
        String all = buf.toString(StandardCharsets.UTF_8);
        assertThat(all).startsWith("\033[?25l"); // hide cursor first
        assertThat(all).contains("\033[?25h"); // restored on close
    }

    @Test
    void initial_update_renders_segments_percent_and_status() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(60, "Downloading");
        }
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // 60% → 24 filled out of 40.
        assertThat(visible).contains("▰".repeat(24) + "▱".repeat(16));
        assertThat(visible).contains(" 60%: Downloading");
    }

    @Test
    void advancing_fill_repaints_the_whole_glyph_row() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(50, "step"); // 20 filled (out of 40)
            buf.reset();
            pb.update(60, "step"); // 24 filled
        }
        String diff = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // The moving gradient re-colors every filled glyph when the frontier
        // advances, so the row is repainted whole: 24 ▰ + 16 ▱.
        long filled = diff.chars().filter(c -> c == '▰').count();
        long empty = diff.chars().filter(c -> c == '▱').count();
        assertThat(filled).isEqualTo(24);
        assertThat(empty).isEqualTo(16);
    }

    @Test
    void unchanged_fill_does_not_repaint_the_glyph_row() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(60, "step"); // round(60 * 40 / 100) = 24 filled
            buf.reset();
            pb.update(61, "step"); // round(61 * 40 / 100) = 24 filled (unchanged)
        }
        String diff = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // No fill change → no glyphs redrawn (only the OSC progress update).
        assertThat(diff.chars().filter(c -> c == '▰' || c == '▱').count()).isZero();
    }

    @Test
    void rightmost_filled_glyph_is_always_the_gradient_end() {
        // The frontier glyph is pinned to the gradient end (bright-magenta)
        // at every fill level; the band trails leftward toward the indigo start.
        assertThat(frontierColor(5)).isEqualTo("38;2;224;64;251");
        assertThat(frontierColor(50)).isEqualTo("38;2;224;64;251");
        assertThat(frontierColor(100)).isEqualTo("38;2;224;64;251");
    }

    @Test
    void single_filled_glyph_uses_only_the_gradient_end() {
        // 2.5% → 1 filled glyph: it must be the bright-magenta end, not the indigo start.
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(2, "x"); // round(2 * 40 / 100) = 1 filled
        }
        var colors = filledGlyphColors(buf.toString(StandardCharsets.UTF_8));
        assertThat(colors).containsExactly("38;2;224;64;251");
    }

    @Test
    void diff_repaints_status_only_when_changed() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(50, "downloading");
            buf.reset();
            pb.update(50, "downloading"); // identical → no status repaint
        }
        // Only the cursor SGR / show-cursor on close should be present.
        String out = buf.toString(StandardCharsets.UTF_8);
        String visible = stripAnsi(out);
        assertThat(visible).doesNotContain("downloading");
    }

    @Test
    void shrinking_status_pads_only_the_removed_tail_with_spaces() {
        String longStatus = "downloading temurin-25.tar.gz";
        String shortStatus = "extracting";
        int expectedShrink = longStatus.length() - shortStatus.length();

        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(50, longStatus);
            buf.reset();
            pb.update(50, shortStatus);
        }
        String diff = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(diff).contains(shortStatus);
        // Exactly `expectedShrink` trailing spaces after the new status —
        // the gap between old and new status lengths.
        int idx = diff.indexOf(shortStatus);
        String trailing = diff.substring(idx + shortStatus.length());
        long spaces = trailing.chars().takeWhile(c -> c == ' ').count();
        assertThat(spaces).isEqualTo(expectedShrink);
    }

    @Test
    void update_emits_osc94_progress_indicator() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(0, "starting");
            pb.update(42, "downloading");
            pb.update(100, "done");
        }
        String all = buf.toString(StandardCharsets.UTF_8);
        // BEL-terminated ConEmu OSC 9;4;1;<n> on every update.
        assertThat(all).contains("\033]9;4;1;0\007");
        assertThat(all).contains("\033]9;4;1;42\007");
        assertThat(all).contains("\033]9;4;1;100\007");
    }

    @Test
    void finish_clears_line_and_writes_replacement_message() {
        var buf = new ByteArrayOutputStream();
        var pb = SpinnerProgressBar.show(stream(buf));
        pb.update(100, "downloading");
        buf.reset();
        pb.finish("✓ Download finished for Eclipse Temurin 21");

        String all = buf.toString(StandardCharsets.UTF_8);
        // CR + clear-to-end-of-line wipes the bar.
        assertThat(all).contains("\r\033[K");
        // OSC 9;4 cleared and cursor restored.
        assertThat(all).contains("\033]9;4;0\007");
        assertThat(all).contains("\033[?25h");
        // Replacement message lands on the same row.
        assertThat(stripAnsi(all)).contains("✓ Download finished for Eclipse Temurin 21");
        // Subsequent close() is a no-op.
        buf.reset();
        pb.close();
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void close_emits_osc94_clear() {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(50, "halfway");
        }
        String all = buf.toString(StandardCharsets.UTF_8);
        // Clear (state 0) appears once we close out of the bar.
        assertThat(all).contains("\033]9;4;0\007");
        // And it precedes the cursor-restore on the close path.
        assertThat(all.indexOf("\033]9;4;0\007")).isLessThan(all.indexOf("\033[?25h"));
    }

    @Test
    void gradient_runs_from_indigo_to_bright_magenta() {
        var colors = SpinnerProgressBar.buildGradient(20);
        assertThat(colors).hasSize(20);
        // Jk Dark indigo #3F51B5 → bright-magenta #E040FB.
        String first = colors[0].toAnsi();
        String last = colors[19].toAnsi();
        assertThat(first).isEqualTo("38;2;63;81;181"); // indigo start
        assertThat(last).isEqualTo("38;2;224;64;251"); // bright-magenta end
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    /** SGR of the right-most filled glyph (the frontier) at {@code percent}. */
    private static String frontierColor(int percent) {
        var buf = new ByteArrayOutputStream();
        try (var pb = SpinnerProgressBar.show(stream(buf))) {
            pb.update(percent, "x");
        }
        var colors = filledGlyphColors(buf.toString(StandardCharsets.UTF_8));
        return colors.isEmpty() ? null : colors.get(colors.size() - 1);
    }

    /** In-order truecolor SGRs of each filled (▰) glyph in {@code raw}. */
    private static java.util.List<String> filledGlyphColors(String raw) {
        var out = new java.util.ArrayList<String>();
        var m = java.util.regex.Pattern.compile("\033\\[(38;2;\\d+;\\d+;\\d+)m▰")
                .matcher(raw);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Drop CSI escapes so we can assert on the visible characters. */
    static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
