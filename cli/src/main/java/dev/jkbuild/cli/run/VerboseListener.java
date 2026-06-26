// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-phase progress with one line per phase, like Cargo / uv.
 * Activated by {@code --verbose}. Phases scroll up as they complete;
 * the active phase shows its current label.
 */
public final class VerboseListener implements GoalListener {

    private final PrintStream out;
    private final PrintStream err;
    private final ConcurrentMap<String, String> labels = new ConcurrentHashMap<>();

    public VerboseListener(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void goalStart(GoalView view) {
        out.println(Theme.colorize("▶", Theme.active().activeStep())
                + " " + Theme.colorize(view.goalName(), Theme.active().focused())
                + " (" + view.phasesTotal() + " phase"
                + (view.phasesTotal() == 1 ? "" : "s") + ")");
    }

    @Override
    public void phaseStart(String phase, int scope) {
        out.println("  " + Theme.colorize("·", Theme.active().normalGray()) + " " + phase + " (scope: " + scope + ")");
    }

    @Override
    public void label(String phase, String label) {
        labels.put(phase, label);
    }

    @Override
    public void output(String phase, String line) {
        out.println(StackTraceHighlight.line(line));
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        String glyph =
                switch (status) {
                    case SUCCESS -> Theme.colorize("✓", Theme.active().completedStep());
                    case FAIL -> Theme.colorize("𝘅", Theme.active().error());
                    case CANCELLED -> Theme.colorize("·", Theme.active().normalGray());
                    default -> "·";
                };
        out.println("  " + glyph + " " + phase + "  "
                + Theme.colorize(
                        ConsoleSpec.fmtDuration(duration), Theme.active().darkGray()));
    }

    @Override
    public void warn(String phase, String code, String message) {
        String location = (code != null && !code.isBlank()) ? " " + phase + "/" + code : "";
        err.println("    " + Theme.colorize("⚠", Theme.active().warning()) + location + ": " + message);
    }

    @Override
    public void error(String phase, String code, String message) {
        if ("verbatim".equals(code)) {
            err.println(message);
        } else {
            err.println(
                    "    " + Theme.colorize("✗", Theme.active().error()) + " " + phase + "/" + code + ": " + message);
        }
    }

    @Override
    public void goalFinish(GoalResult result) {
        String summary = result.success()
                ? Theme.colorize("✓ done", Theme.active().completedStep())
                : Theme.colorize("𝘅 failed", Theme.active().error());
        out.println(summary + " (" + ConsoleSpec.fmtDuration(result.duration()) + ")");
    }
}
