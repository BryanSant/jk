// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace progress bar must calibrate to the aggregate tick total up front
 * and advance cumulatively — the denominator stays fixed and the numerator only
 * grows across the member boundary, instead of resetting per member.
 */
class AggregateProgressTest {

    private static final Pattern COUNT = Pattern.compile("(\\d+) of (\\d+)");

    @Test
    void calibrated_bar_keeps_a_fixed_denominator_and_advances_cumulatively() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);

        // Pre-scan summed two members' estimates: 40 + 60 = 100.
        agg.calibrate(100);
        assertThat(agg.total()).isEqualTo(100);
        assertThat(barCount(cm)).isEqualTo("0 of 100");

        // Member A owns a slice of 40; its own 0→100% scales into that slice.
        AggregateMemberListener a = new AggregateMemberListener(agg, "mod-a", List.of(), 40);
        a.goalStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo("0 of 100");
        a.progress("compile", 10, view(10, 40));   // 25% of A's slice → +10
        assertThat(barCount(cm)).isEqualTo("10 of 100");
        a.goalFinish(success());           // advances the base by A's slice (40)

        // Member B starts where A's slice ended — no reset, denominator unchanged.
        AggregateMemberListener b = new AggregateMemberListener(agg, "mod-b", List.of(), 60);
        b.goalStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo("40 of 100");
        b.progress("compile", 30, view(30, 60));   // 50% of B's slice → +30
        assertThat(barCount(cm)).isEqualTo("70 of 100");
    }

    @Test
    void overrun_clamps_to_the_slice_and_never_grows_the_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(50);

        // A member that runs past its (under-)estimate must not stretch the
        // denominator: its progress clamps to its slice, so the bar pins at the
        // slice cap and the total stays fixed at 50.
        AggregateMemberListener m = new AggregateMemberListener(agg, "mod", List.of(), 50);
        m.goalStart(view(0, 50));
        m.progress("x", 80, view(80, 50));
        assertThat(barCount(cm)).isEqualTo("50 of 50");
    }

    @Test
    void member_boundary_does_not_backtrack_when_a_member_overruns() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(100);   // A slice 40, B slice 60

        // A overruns its own estimate (live numerator 70 > denominator 40), but
        // its contribution is clamped to its 40-tick slice.
        AggregateMemberListener a = new AggregateMemberListener(agg, "mod-a", List.of(), 40);
        a.goalStart(view(0, 40));
        a.progress("x", 70, view(70, 40));
        assertThat(barCount(cm)).isEqualTo("40 of 100");   // clamped to the slice
        a.goalFinish(success());

        // B must start at the slice boundary (40), not drop below it — the
        // pre-fix code would have shown the prior live numerator and then reset.
        AggregateMemberListener b = new AggregateMemberListener(agg, "mod-b", List.of(), 60);
        b.goalStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo("40 of 100");
    }

    @Test
    void uncalibrated_falls_back_to_the_growing_per_member_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);   // no calibrate()

        AggregateMemberListener a = new AggregateMemberListener(agg, "mod-a", List.of());
        a.goalStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo("0 of 40");
        a.progress("x", 10, view(10, 40));
        assertThat(barCount(cm)).isEqualTo("10 of 40");
        a.goalFinish(success());

        AggregateMemberListener b = new AggregateMemberListener(agg, "mod-b", List.of());
        b.goalStart(view(0, 60));
        // base 40 + member denominator 60 → grows to 100 (the pre-fix behavior).
        assertThat(barCount(cm)).isEqualTo("40 of 100");
    }

    // --- helpers -----------------------------------------------------------

    private static CommandManager newView() {
        return CommandManager.goal(new PrintStream(new ByteArrayOutputStream()), "Build", false);
    }

    private static GoalView view(long numerator, long denominator) {
        return new GoalView("member", numerator, denominator, 1, 0, false);
    }

    private static GoalResult success() {
        return new GoalResult("member", true, Duration.ZERO, List.of(), List.of(), List.of(), false);
    }

    /** Pull the bar's "{numerator} of {denominator}" text out of the rendered region. */
    private static String barCount(CommandManager cm) {
        List<String> lines = cm.renderGoalLines(80, 0);
        Matcher m = COUNT.matcher(lines.get(1)); // line 0 = header, line 1 = bar
        assertThat(m.find()).as("bar line should carry an N-of-M count").isTrue();
        return m.group(1) + " of " + m.group(2);
    }
}
