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
        // "✓ <goal> Successful: <message>", head in green.
        assertThat(visible).contains("✓ Syncing Successful: Finished syncing 13 artifacts");
        assertThat(raw).contains(Theme.colorize("✓ Syncing Successful", Theme.active().success()));
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
        // The notice itself is GlobalCancel's job; the component only settles.
        assertThat(visible).doesNotContain("Canceled");
        assertThat(buf.toString(StandardCharsets.UTF_8)).contains("\033[?25h");
        // …but the component supplies the goal-named cancel text.
        assertThat(cm.canceledMessage()).isEqualTo("Locking canceled by user");
    }

    @Test
    void non_animated_mode_prints_only_the_result_line() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), false); // pipe / --quiet
        cm.label("Locking");
        cm.tick();                       // animator never runs; harmless if poked
        cm.finishSuccess("done");

        String raw = buf.toString(StandardCharsets.UTF_8);
        assertThat(stripAnsi(raw)).contains("✓ Locking Successful: done");
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

    // --- goal-oriented mode ----------------------------------------------

    @Test
    void goal_header_bar_and_rows() {
        var cm = CommandManager.goal(stream(new ByteArrayOutputStream()), "Building", false);
        cm.progress(45, 100);
        cm.addPhase("acme:api", "parse-build");
        cm.phaseDone("acme:api", "parse-build", true);
        cm.phaseRunning("acme:api", "compile-java");
        cm.phaseMessage("acme:api", "compile-java", "javac 12 sources");

        String all = String.join("\n", stripAll(cm.renderGoalLines(120, 112_000)));
        // Header: name › member… (elapsed)
        assertThat(all).contains("Building").contains("acme:api").contains("(1m 52s)");
        // Bar with percent + count.
        assertThat(all).contains("45%").contains("[45 of 100]");
        // Active row (◻) on top with its message; completed row (✓) below.
        assertThat(all).contains("◻ acme:api › Compile java › javac 12 sources");
        assertThat(all).contains("✓ acme:api › Parse build");
        int active = all.indexOf("Compile java");
        int doneRow = all.indexOf("Parse build");
        assertThat(active).isLessThan(doneRow); // outstanding floats above completed
    }

    @Test
    void completed_rows_collapse_beyond_the_cap() {
        var cm = CommandManager.goal(stream(new ByteArrayOutputStream()), "Building", false);
        for (int i = 0; i < 9; i++) {
            cm.addPhase("m", "p" + i);
            cm.phaseDone("m", "p" + i, true);
        }
        cm.phaseRunning("m", "active");

        String all = String.join("\n", stripAll(cm.renderGoalLines(120, 0)));
        assertThat(all).contains("… +3 completed"); // 1 active + 6 shown + 3 hidden = 9 done
    }

    @Test
    void region_is_capped_to_terminal_height() {
        // A region taller than the viewport scrolls its top off-screen, where
        // the cursor-relative repaint/wipe can't reach it (lingering spinner on
        // cancel). So the whole region must fit within the terminal height.
        var cm = CommandManager.goal(stream(new ByteArrayOutputStream()), "Building", false);
        cm.height = 6;
        for (int i = 0; i < 20; i++) cm.addPhase("m", "p" + i);
        cm.phaseRunning("m", "p0");

        var lines = cm.renderGoalLines(120, 0);
        // header + bar + phase list, all within height (with a line of headroom).
        assertThat(lines.size()).isLessThanOrEqualTo(6 - 1);
        assertThat(String.join("\n", stripAll(lines))).contains("…"); // collapsed
    }

    @Test
    void first_row_carries_the_rail_connector() {
        var cm = CommandManager.goal(stream(new ByteArrayOutputStream()), "Building", false);
        cm.phaseRunning("m", "compile");
        var lines = cm.renderGoalLines(120, 0);
        // lines[0]=header, lines[1]=bar, lines[2]=first phase row.
        assertThat(stripAnsi(lines.get(2))).startsWith("╰─ ◻ m › Compile");
    }

    @Test
    void write_above_prints_the_line_then_repaints_the_region_below() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true, true, 80);
        cm.progress(1, 4);
        cm.phaseRunning("m", "compile");
        cm.tick();          // initial region paint
        buf.reset();

        cm.writeAbove("javac: warning in Foo.java");

        String visible = stripAnsi(buf.toString(StandardCharsets.UTF_8));
        // The log line appears, and the bar (region) is repainted after it.
        int log = visible.indexOf("javac: warning in Foo.java");
        int bar = visible.indexOf("▱");
        assertThat(log).isGreaterThanOrEqualTo(0);
        assertThat(bar).isGreaterThan(log); // region re-drawn below the log line
    }

    @Test
    void capture_output_routes_system_out_above_the_region_then_restores() {
        var buf = new ByteArrayOutputStream();
        var cm = new CommandManager(stream(buf), true, true, 80);
        cm.phaseRunning("m", "compile");
        cm.tick();
        buf.reset();

        java.io.PrintStream original = System.out;
        try (var scope = cm.captureOutput()) {
            System.out.println("from a phase");
        }
        assertThat(System.out).isSameAs(original);       // streams restored
        assertThat(stripAnsi(buf.toString(StandardCharsets.UTF_8)))
                .contains("from a phase");               // routed to the region's real stdout
    }

    @Test
    void fmt_elapsed_formats_minutes_and_seconds() {
        assertThat(CommandManager.fmtElapsed(112_000)).isEqualTo("1m 52s");
        assertThat(CommandManager.fmtElapsed(52_000)).isEqualTo("52s");
        assertThat(CommandManager.fmtElapsed(0)).isEqualTo("0s");
    }

    @Test
    void truncate_visible_cuts_at_column_keeping_escapes() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        String cut = CommandManager.truncateVisible(colored, 3);
        assertThat(stripAnsi(cut)).isEqualTo("abc");
        assertThat(cut).endsWith("\033[0m"); // reset appended on truncation
    }

    private static java.util.List<String> stripAll(java.util.List<String> lines) {
        return lines.stream().map(CommandManagerTest::stripAnsi).toList();
    }

    private static PrintStream stream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
