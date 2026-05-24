// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SpinnerTest {

    @Test
    void step_cycles_through_frames_in_order() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        // Manually drive 7 frames; we should see each glyph in order.
        for (int i = 0; i < Spinner.FRAMES.length; i++) {
            s.step();
        }
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // Each frame line begins with "\r<frame> <message>" — search for the glyphs.
        for (String frame : Spinner.FRAMES) {
            assertThat(visible).contains(frame + " Working");
        }
    }

    @Test
    void update_changes_message_on_next_step() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "first");
        s.step();
        s.update("second");
        buf.reset();
        s.step();
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("second");
    }

    @Test
    void shrinking_message_pads_only_the_removed_tail() {
        String longMsg = "downloading temurin-25.tar.gz";
        String shortMsg = "done";
        int expectedShrink = longMsg.length() - shortMsg.length();

        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), longMsg);
        s.step();
        s.update(shortMsg);
        buf.reset();
        s.step();
        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        int idx = visible.indexOf(shortMsg);
        assertThat(idx).isGreaterThanOrEqualTo(0);
        long spaces = visible.substring(idx + shortMsg.length())
                .chars().takeWhile(c -> c == ' ').count();
        assertThat(spaces).isEqualTo(expectedShrink);
    }

    @Test
    void gradient_runs_from_blue_to_magenta() {
        var colors = Spinner.buildGradient(Spinner.FRAMES.length);
        // First frame: blue #3b82f6; last frame: magenta #d946ef.
        assertThat(colors[0].toAnsi()).isEqualTo("38;2;59;130;246");
        assertThat(colors[colors.length - 1].toAnsi()).isEqualTo("38;2;217;70;239");
    }

    @Test
    void close_clears_line_and_restores_cursor() {
        var buf = new ByteArrayOutputStream();
        var s = new Spinner(stream(buf), "Working");
        s.step();
        buf.reset();
        s.close();
        String out = buf.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("\r\033[K");  // clear current line
        assertThat(out).contains("\033[?25h"); // show cursor
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
