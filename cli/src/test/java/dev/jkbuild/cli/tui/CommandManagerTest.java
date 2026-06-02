// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Theme;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Simple-task mode of the CommandManager component. */
class CommandManagerTest {

    @Test
    void tick_renders_first_glyph_and_verb() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Locking");
        cm.tick(); // frame 0 = "·"
        assertThat(stripAnsi(buf.toString(StandardCharsets.UTF_8))).contains("· Locking…");
    }

    @Test
    void finish_success_freezes_spinner_then_prints_green_check_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishSuccess("Finished syncing 13 artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        String visible = stripAnsi(raw);
        // Frozen spinner (first glyph) + verb on its own line, result line below.
        assertThat(visible).contains("· Syncing…");
        assertThat(visible).contains("✔ Finished syncing 13 artifacts");
        // The check is green (success = bold green).
        assertThat(raw).contains(Theme.colorize("✔", Theme.active().success()));
        assertThat(raw).contains("\033[?25h"); // cursor restored
    }

    @Test
    void finish_failure_prints_red_cross_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Syncing");
        cm.finishFailure("Failed to sync remote artifacts");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(stripAnsi(raw)).contains("✗ Failed to sync remote artifacts");
        assertThat(raw).contains(Theme.colorize("✗", Theme.active().error()));
    }

    @Test
    void render_canceled_settles_spinner_without_printing_the_cancel_notice() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Locking");
        cm.renderCanceled();

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        assertThat(visible).contains("· Locking…");
        // "‼ Canceled by user" is GlobalCancel's job, not the component's.
        assertThat(visible).doesNotContain("Canceled");
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\033[?25h");
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), false); // pipe / --quiet
        cm.label("Locking");
        cm.tick();                       // animator never runs; harmless if poked
        cm.finishSuccess("done");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(stripAnsi(raw)).contains("✔ done");
        assertThat(stripAnsi(raw)).doesNotContain("Locking…"); // no spinner line
        assertThat(raw).doesNotContain("\033[?25h");           // never hid the cursor
    }

    @Test
    void finish_is_idempotent() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true);
        cm.label("Building");
        cm.finishSuccess("Built x");
        buf.reset();
        cm.finishFailure("ignored");
        cm.close();
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
