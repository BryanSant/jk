// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workspace progress bar must calibrate to the aggregate tick total up front and advance
 * cumulatively — the denominator stays fixed and the numerator only grows across the module
 * boundary, instead of resetting per module.
 */
class AggregateProgressTest {

    @Test
    void calibrated_bar_keeps_a_fixed_denominator_and_advances_cumulatively() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);

        // Pre-scan summed two modules' estimates: 40 + 60 = 100.
        agg.calibrate(100);
        assertThat(agg.total()).isEqualTo(100);
        assertThat(barCount(cm)).isEqualTo("0 of 100");

        // Module A owns a slice of 40; its own 0→100% scales into that slice.
        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.goalStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo("0 of 100");
        a.progress("compile", 10, view(10, 40)); // 25% of A's slice → +10
        assertThat(barCount(cm)).isEqualTo("10 of 100");
        a.goalFinish(success()); // advances the base by A's slice (40)

        // Module B starts where A's slice ended — no reset, denominator unchanged.
        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.goalStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo("40 of 100");
        b.progress("compile", 30, view(30, 60)); // 50% of B's slice → +30
        assertThat(barCount(cm)).isEqualTo("70 of 100");
    }

    @Test
    void overrun_clamps_to_the_slice_and_never_grows_the_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(50);

        // A module that runs past its (under-)estimate must not stretch the
        // denominator: its progress clamps to its slice, so the bar pins at the
        // slice cap and the total stays fixed at 50.
        AggregateModuleListener m = new AggregateModuleListener(agg, "mod", List.of(), 50);
        m.goalStart(view(0, 50));
        m.progress("x", 80, view(80, 50));
        assertThat(barCount(cm)).isEqualTo("50 of 50");
    }

    @Test
    void module_boundary_does_not_backtrack_when_a_module_overruns() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(100); // A slice 40, B slice 60

        // A overruns its own estimate (live numerator 70 > denominator 40), but
        // its contribution is clamped to its 40-tick slice.
        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.goalStart(view(0, 40));
        a.progress("x", 70, view(70, 40));
        assertThat(barCount(cm)).isEqualTo("40 of 100"); // clamped to the slice
        a.goalFinish(success());

        // B must start at the slice boundary (40), not drop below it — the
        // pre-fix code would have shown the prior live numerator and then reset.
        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.goalStart(view(0, 60));
        assertThat(barCount(cm)).isEqualTo("40 of 100");
    }

    @Test
    void uncalibrated_falls_back_to_the_growing_per_module_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm); // no calibrate()

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of());
        a.goalStart(view(0, 40));
        assertThat(barCount(cm)).isEqualTo("0 of 40");
        a.progress("x", 10, view(10, 40));
        assertThat(barCount(cm)).isEqualTo("10 of 40");
        a.goalFinish(success());

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of());
        b.goalStart(view(0, 60));
        // base 40 + module denominator 60 → grows to 100 (the pre-fix behavior).
        assertThat(barCount(cm)).isEqualTo("40 of 100");
    }

    @Test
    void module_reweight_resizes_its_slice_and_the_aggregate_total() {
        CommandManager cm = newView();
        AggregateContext agg = new AggregateContext(cm);
        agg.calibrate(100); // A slice 40, B slice 60

        AggregateModuleListener a = new AggregateModuleListener(agg, "mod-a", List.of(), 40);
        a.goalStart(view(0, 40)); // initial denominator == reserved slice
        assertThat(agg.total()).isEqualTo(100);

        // A's compile turns out to be a cheap restore: its goal denominator drops
        // 40 → 3, which must shrink both its slice and the aggregate total.
        a.progress("compile", 3, view(3, 3));
        assertThat(agg.total()).isEqualTo(63); // 100 − 37
        a.goalFinish(success());
        assertThat(barCount(cm)).isEqualTo("3 of 63"); // base advanced by the shrunk slice

        AggregateModuleListener b = new AggregateModuleListener(agg, "mod-b", List.of(), 60);
        b.goalStart(view(0, 60));
        b.progress("x", 60, view(60, 60));
        assertThat(barCount(cm)).isEqualTo("63 of 63"); // 3 + 60, bar reaches 100%
    }

    // --- helpers -----------------------------------------------------------

    private static CommandManager newView() {
        return CommandManager.goal(new PrintStream(new ByteArrayOutputStream()), "Build", false);
    }

    private static GoalView view(long numerator, long denominator) {
        return new GoalView("module", numerator, denominator, 1, 0, false);
    }

    private static GoalResult success() {
        return new GoalResult("module", true, Duration.ZERO, List.of(), List.of(), List.of(), false);
    }

    /**
     * The aggregate numerator/denominator currently driving the bar. (The bar no longer prints an
     * N-of-M count, so read the values the view holds directly.)
     */
    private static String barCount(CommandManager cm) {
        return cm.numerator() + " of " + cm.denominator();
    }
}
