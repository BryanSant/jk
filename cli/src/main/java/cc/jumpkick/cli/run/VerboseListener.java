// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.PipelineView;
import cc.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-step progress with one line per step, like Cargo / uv. Activated by {@code --verbose}.
 * Steps scroll up as they complete; the active step shows its current label.
 */
public final class VerboseListener implements PipelineListener {

    private final PrintStream out;
    private final PrintStream err;
    private final ConcurrentMap<String, String> labels = new ConcurrentHashMap<>();

    public VerboseListener(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void pipelineStart(PipelineView view) {
        out.println(Theme.colorize("▶", Theme.active().activeStep())
                + " "
                + Theme.colorize(view.pipelineName(), Theme.active().focused())
                + " ("
                + view.stepsTotal()
                + " step"
                + (view.stepsTotal() == 1 ? "" : "s")
                + ")");
    }

    /** Renders a step's place in the run hierarchy as {@code phase/step} (a redundant {@code phase-}
     * prefix on the step name is dropped, so phase {@code compile} + step {@code compile-java} →
     * {@code compile/java}); the bare step name when it has no phase. */
    static String qualified(String step, Phase phase) {
        if (phase == null) return step;
        String pw = phase.wireName();
        String shortName = step.startsWith(pw + "-") ? step.substring(pw.length() + 1) : step;
        return pw + "/" + shortName;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        out.println("  " + Theme.colorize("·", Theme.active().normalGray()) + " " + qualified(step, phase)
                + " (ticks: " + ticks + ")");
    }

    @Override
    public void label(String step, String label) {
        labels.put(step, label);
    }

    @Override
    public void output(String step, String line) {
        out.println(StackTraceHighlight.line(line));
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        String glyph =
                switch (status) {
                    case SUCCESS -> Theme.colorize(Glyphs.CHECK, Theme.active().completedStep());
                    case FAIL -> Theme.colorize(Glyphs.CROSS, Theme.active().error());
                    case CANCELLED -> Theme.colorize("·", Theme.active().normalGray());
                    default -> "·";
                };
        out.println("  "
                + glyph
                + " "
                + qualified(step, phase)
                + "  "
                + Theme.colorize(
                        ConsoleSpec.fmtDuration(duration), Theme.active().darkGray()));
    }

    @Override
    public void warn(String step, String code, String message) {
        String location = (code != null && !code.isBlank()) ? " " + step + "/" + code : "";
        err.println("    " + Theme.colorize(Glyphs.BANG, Theme.active().warning()) + location + ": " + message);
    }

    @Override
    public void error(String step, String code, String message) {
        if ("verbatim".equals(code)) {
            err.println(message);
        } else {
            err.println(
                    "    " + Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + step + "/" + code + ": " + message);
        }
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        String summary = result.success()
                ? Theme.colorize(Glyphs.CHECK + " done", Theme.active().completedStep())
                : Theme.colorize(Glyphs.CROSS + " failed", Theme.active().error());
        out.println(summary + " (" + ConsoleSpec.fmtDuration(result.duration()) + ")");
    }
}
