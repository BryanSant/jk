// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Two sequential members feed one aggregate view: counts sum, rows merge. */
class AggregateMemberListenerTest {

    @Test
    void members_accumulate_into_one_bar_and_merged_phase_list() {
        var buf = new ByteArrayOutputStream();
        CommandManager view = CommandManager.goal(
                new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        // Member A: one phase, scope 10, runs to completion.
        var a = new AggregateMemberListener(agg, "g:api", List.of(phase("compile", "Compile")));
        a.goalStart(new GoalView("build", 0, 10, 1, 0, false));
        a.phaseStart("compile", 10);
        a.progress("compile", 10, new GoalView("build", 10, 10, 1, 1, false));
        a.phaseFinish("compile", PhaseStatus.SUCCESS, Duration.ZERO);
        a.goalFinish(result(true));

        // Member B: one phase, scope 10, half done.
        var b = new AggregateMemberListener(agg, "g:web", List.of(phase("test", "Test")));
        b.goalStart(new GoalView("build", 0, 10, 1, 0, false));
        b.phaseStart("test", 10);
        b.progress("test", 5, new GoalView("build", 5, 10, 1, 0, false));

        // Aggregate now: numerator = 10 (A) + 5 (B), denominator = 10 + 10 = 20 → 75%.
        String all = String.join("\n",
                view.renderGoalLines(120, 0).stream().map(AggregateMemberListenerTest::strip).toList());
        assertThat(all).contains("75%");
        // The phase list renders only the active row: member B's running Test
        // shows (tagged by member); member A's finished Compile is not listed.
        assertThat(all).contains("g:web › Test");
        assertThat(all).doesNotContain("g:api");
    }

    @Test
    void concurrent_members_sum_into_the_bar_and_each_shows_a_tree_row() {
        var buf = new ByteArrayOutputStream();
        CommandManager view = CommandManager.goal(
                new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);
        agg.calibrate(20);   // two members, slice 10 each

        // Both members running at the same time (neither finished), each half-done.
        var a = new AggregateMemberListener(agg, "g:api", List.of(phase("compile", "Compile")), 10);
        a.goalStart(new GoalView("build", 0, 10, 1, 0, false));
        a.phaseStart("compile", 10);
        a.progress("compile", 5, new GoalView("build", 5, 10, 1, 0, false));

        var b = new AggregateMemberListener(agg, "g:web", List.of(phase("test", "Test")), 10);
        b.goalStart(new GoalView("build", 0, 10, 1, 0, false));
        b.phaseStart("test", 10);
        b.progress("test", 5, new GoalView("build", 5, 10, 1, 0, false));

        // Aggregate = 5 (A) + 5 (B) of 20 → 50%, and both running rows render as a
        // tree (├─ for the first active member, ╰─ to close).
        String all = String.join("\n",
                view.renderGoalLines(120, 0).stream().map(AggregateMemberListenerTest::strip).toList());
        assertThat(all).contains("50%");
        assertThat(all).contains("g:api › Compile").contains("g:web › Test");
        assertThat(all).contains("├─").contains("╰─");
    }

    private static Phase phase(String name, String label) {
        return Phase.builder(name).label(label).scope(1).execute(ctx -> {}).build();
    }

    private static dev.jkbuild.run.GoalResult result(boolean ok) {
        return new dev.jkbuild.run.GoalResult("build", ok, Duration.ZERO,
                List.of(), List.of(), List.of(), false, false);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
