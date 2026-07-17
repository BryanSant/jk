// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.tui.CommandManager;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Two sequential modules feed one aggregate view: counts sum, rows merge. */
class AggregateModuleListenerTest {

    @Test
    void modules_accumulate_into_one_bar_and_merged_phase_list() {
        var buf = new ByteArrayOutputStream();
        CommandManager view =
                CommandManager.pipeline(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);

        // Module A: one step, ticks 10, runs to completion.
        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")));
        a.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", null, 10);
        a.progress("compile", 10, new PipelineView("build", 10, 10, 1, 1, false));
        a.stepFinish("compile", null, StepStatus.SUCCESS, Duration.ZERO);
        a.pipelineFinish(result(true));

        // Module B: one step, ticks 10, half done.
        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")));
        b.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        b.stepStart("test", null, 10);
        b.progress("test", 5, new PipelineView("build", 5, 10, 1, 0, false));

        // Aggregate now: numerator = 10 (A) + 5 (B), denominator = 10 + 10 = 20 → 75%.
        String all = String.join(
                "\n",
                view.renderPipelineLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("75%");
        // The step list renders only the active row: module B's running Test
        // shows (tagged by module); module A's finished Compile is not listed.
        assertThat(all).contains("g:web › Test");
        assertThat(all).doesNotContain("g:api");
    }

    @Test
    void concurrent_modules_sum_into_the_bar_and_each_shows_a_tree_row() {
        var buf = new ByteArrayOutputStream();
        CommandManager view =
                CommandManager.pipeline(new PrintStream(buf, true, StandardCharsets.UTF_8), "Building", false);
        var agg = new AggregateContext(view);
        agg.calibrate(20); // two modules, slice 10 each

        // Both modules running at the same time (neither finished), each half-done.
        var a = new AggregateModuleListener(agg, "g:api", List.of(step("compile", "Compile")), 10);
        a.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        a.stepStart("compile", null, 10);
        a.progress("compile", 5, new PipelineView("build", 5, 10, 1, 0, false));

        var b = new AggregateModuleListener(agg, "g:web", List.of(step("test", "Test")), 10);
        b.pipelineStart(new PipelineView("build", 0, 10, 1, 0, false));
        b.stepStart("test", null, 10);
        b.progress("test", 5, new PipelineView("build", 5, 10, 1, 0, false));

        // Aggregate = 5 (A) + 5 (B) of 20 → 50%, and both running rows render as a
        // tree (├─ for the first active module, ╰─ to close).
        String all = String.join(
                "\n",
                view.renderPipelineLines(120, 0).stream()
                        .map(AggregateModuleListenerTest::strip)
                        .toList());
        assertThat(all).contains("50%");
        assertThat(all).contains("g:api › Compile").contains("g:web › Test");
        assertThat(all).contains("├─").contains("╰─");
    }

    private static Step step(String name, String label) {
        return Step.builder(name).label(label).ticks(1).execute(ctx -> {}).build();
    }

    private static cc.jumpkick.run.PipelineResult result(boolean ok) {
        return new cc.jumpkick.run.PipelineResult(
                "build", ok, Duration.ZERO, List.of(), List.of(), List.of(), false, false);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "");
    }
}
