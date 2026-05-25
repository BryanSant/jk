// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressBarTest {

    @Test
    void show_hides_cursor() {
        var buf = new ByteArrayOutputStream();
        try (var pb = ProgressBar.show(stream(buf))) {
            pb.update(0, "Starting...");
        }
        String all = buf.toString(StandardCharsets.UTF_8);
        assertThat(all).startsWith("\033[?25l"); // hide cursor first
        assertThat(all).contains("\033[?25h"); // restored on close
    }

    @Test
    void initial_update_renders_segments_percent_and_status() {
        var buf = new ByteArrayOutputStream();
        try (var pb = ProgressBar.show(stream(buf))) {
            pb.update(60, "Downloading");
        }
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // 60% → 24 filled out of 40.
        assertThat(visible).contains("▰".repeat(24) + "▱".repeat(16));
        assertThat(visible).contains(" 60%: Downloading");
    }

    @Test
    void diff_only_repaints_changed_segment_range() {
        var buf = new ByteArrayOutputStream();
        try (var pb = ProgressBar.show(stream(buf))) {
            pb.update(50, "step");   // 20 filled (out of 40)
            buf.reset();
            pb.update(60, "step");   // 24 filled — segments 20..23 should flip
        }
        String diff = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // The second update should print exactly four ▰ chars (the four
        // segments that just flipped) and not redraw any ▱ segments.
        long filled = diff.chars().filter(c -> c == '▰').count();
        long empty = diff.chars().filter(c -> c == '▱').count();
        assertThat(filled).isEqualTo(4);
        assertThat(empty).isZero();
    }

    @Test
    void diff_repaints_status_only_when_changed() {
        var buf = new ByteArrayOutputStream();
        try (var pb = ProgressBar.show(stream(buf))) {
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
        try (var pb = ProgressBar.show(stream(buf))) {
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
        try (var pb = ProgressBar.show(stream(buf))) {
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
        var pb = ProgressBar.show(stream(buf));
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
        try (var pb = ProgressBar.show(stream(buf))) {
            pb.update(50, "halfway");
        }
        String all = buf.toString(StandardCharsets.UTF_8);
        // Clear (state 0) appears once we close out of the bar.
        assertThat(all).contains("\033]9;4;0\007");
        // And it precedes the cursor-restore on the close path.
        assertThat(all.indexOf("\033]9;4;0\007"))
                .isLessThan(all.indexOf("\033[?25h"));
    }

    @Test
    void gradient_runs_from_violet_to_coral() {
        var colors = ProgressBar.buildGradient(20);
        assertThat(colors).hasSize(20);
        // Reverse of the wizard title: violet #8150fe → coral #e3475b.
        String first = colors[0].toAnsi();
        String last = colors[19].toAnsi();
        assertThat(first).isEqualTo("38;2;129;80;254");  // #8150fe
        assertThat(last).isEqualTo("38;2;227;71;91");    // #e3475b
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    /** Drop CSI escapes so we can assert on the visible characters. */
    static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
