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
        // 60% → 12 filled out of 20.
        assertThat(visible).contains("▰".repeat(12) + "▱".repeat(8));
        assertThat(visible).contains(" 60%: Downloading");
    }

    @Test
    void diff_only_repaints_changed_segment_range() {
        var buf = new ByteArrayOutputStream();
        try (var pb = ProgressBar.show(stream(buf))) {
            pb.update(50, "step");   // 10 filled
            buf.reset();
            pb.update(60, "step");   // 12 filled — only segments 10..11 should flip
        }
        String diff = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // The second update should print exactly two ▰ chars (the two
        // segments that just flipped) and not redraw any ▱ segments.
        long filled = diff.chars().filter(c -> c == '▰').count();
        long empty = diff.chars().filter(c -> c == '▱').count();
        assertThat(filled).isEqualTo(2);
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
    void gradient_runs_from_dark_to_bright_green() {
        var colors = ProgressBar.buildGradient(20);
        assertThat(colors).hasSize(20);
        // First color should be the dark-green endpoint, last the bright.
        String first = colors[0].toAnsi();
        String last = colors[19].toAnsi();
        assertThat(first).isEqualTo("38;2;22;101;52");   // #166534
        assertThat(last).isEqualTo("38;2;74;222;128");   // #4ade80
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    /** Drop CSI escapes so we can assert on the visible characters. */
    static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
