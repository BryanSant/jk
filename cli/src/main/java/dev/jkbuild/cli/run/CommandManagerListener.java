// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

/**
 * Console listener for goal-oriented commands ({@code jk build} and friends):
 * drives a {@link CommandManager} in goal mode — a spinner header, an aggregate
 * progress bar, and a dynamic phase list. On completion the live region is
 * replaced by a {@code ✓}/{@code ✗} result line built from the
 * {@link ConsoleSpec} mappers.
 *
 * <p>All phases of this goal are attributed to a single {@code module} (the
 * project's {@code group:artifact}). Workspace aggregation across modules feeds
 * one shared {@link CommandManager} from several goals; that path is built on
 * the same component.
 */
public final class CommandManagerListener implements GoalListener {

    private final PrintStream out;
    private final ConsoleSpec spec;
    private final String module;
    private final List<Phase> phases;
    private final boolean animate;

    private CommandManager cm;
    private CommandManager.OutputScope capture;

    public CommandManagerListener(PrintStream out, ConsoleSpec spec,
                                  String module, List<Phase> phases, boolean animate) {
        this.out = out;
        this.spec = spec;
        this.module = module;
        this.phases = phases;
        this.animate = animate;
    }

    @Override
    public void goalStart(GoalView view) {
        cm = CommandManager.goal(out, spec.verb(), animate);
        cm.target(module);
        for (Phase p : phases) {
            cm.addPhaseLabeled(module, p.name(), display(p));
        }
        cm.progress(view.numerator(), view.denominator());
        // Route phase/process output above the pinned region for the goal's lifetime.
        capture = cm.captureOutput();
    }

    @Override
    public void phaseStart(String phase, int scope) {
        cm.phaseRunning(module, phase);
    }

    @Override
    public void label(String phase, String label) {
        cm.phaseMessage(module, phase, label);
    }

    @Override
    public void output(String phase, String line) {
        cm.writeAbove(line);
    }

    @Override
    public void progress(String phase, int delta, GoalView view) {
        cm.progress(view.numerator(), view.denominator());
    }

    @Override
    public void scopeUpdate(String phase, int delta, GoalView view) {
        cm.progress(view.numerator(), view.denominator());
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, Duration duration) {
        cm.phaseDone(module, phase, status == PhaseStatus.SUCCESS);
    }

    @Override
    public void goalFinish(GoalResult result) {
        // Restore the real streams before settling so the result line isn't
        // itself routed back above the (closing) region.
        if (capture != null) capture.close();
        if (cm == null) cm = CommandManager.goal(out, spec.verb(), animate);
        String suffix = " " + ConsoleSpec.took(result.duration());
        // All diagnostics print ABOVE the result line (which stays last) — warnings
        // first, then errors nearest the line — so the failure route reads just like
        // the success route and the outcome is the last thing on screen.
        List<String> above = new java.util.ArrayList<>();
        for (GoalResult.Diagnostic d : result.warnings()) {
            above.add(ConsoleSpec.renderWarning(d));
        }
        for (GoalResult.Diagnostic d : result.errors()) {
            above.add(ConsoleSpec.renderError(d));
        }
        if (result.success()) {
            String tail = spec.onSuccess().apply(result) + suffix;
            if (spec.chip() && spec.exec()) cm.finishGoalExec(tail, above);
            else if (spec.chip())            cm.finishGoalSuccess(tail, above);
            else                             cm.finishSuccess(tail, above);
        } else {
            if (spec.chip()) cm.finishGoalFailure(spec.onFailure().apply(result) + suffix, above);
            else cm.finishFailure(spec.onFailure().apply(result) + suffix, above);
        }
    }

    private static String display(Phase p) {
        return p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
    }
}
