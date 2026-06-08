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
 * replaced by a {@code ✔}/{@code ✗} result line built from the
 * {@link ConsoleSpec} mappers.
 *
 * <p>All phases of this goal are attributed to a single {@code member} (the
 * project's {@code group:artifact}). Workspace aggregation across members feeds
 * one shared {@link CommandManager} from several goals; that path is built on
 * the same component.
 */
public final class CommandManagerListener implements GoalListener {

    private final PrintStream out;
    private final PrintStream err;
    private final ConsoleSpec spec;
    private final String member;
    private final List<Phase> phases;
    private final boolean animate;

    private CommandManager cm;
    private CommandManager.OutputScope capture;

    public CommandManagerListener(PrintStream out, PrintStream err, ConsoleSpec spec,
                                  String member, List<Phase> phases, boolean animate) {
        this.out = out;
        this.err = err;
        this.spec = spec;
        this.member = member;
        this.phases = phases;
        this.animate = animate;
    }

    @Override
    public void goalStart(GoalView view) {
        cm = CommandManager.goal(out, spec.verb(), animate);
        cm.target(member);
        for (Phase p : phases) {
            cm.addPhaseLabeled(member, p.name(), display(p));
        }
        cm.progress(view.numerator(), view.denominator());
        // Route phase/process output above the pinned region for the goal's lifetime.
        capture = cm.captureOutput();
    }

    @Override
    public void phaseStart(String phase, int scope) {
        cm.phaseRunning(member, phase);
    }

    @Override
    public void label(String phase, String label) {
        cm.phaseMessage(member, phase, label);
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
        cm.phaseDone(member, phase, status == PhaseStatus.SUCCESS);
    }

    @Override
    public void goalFinish(GoalResult result) {
        // Restore the real streams before settling so the result line and
        // diagnostics aren't themselves routed back above the (closing) region.
        if (capture != null) capture.close();
        if (cm == null) cm = CommandManager.goal(out, spec.verb(), animate);
        String suffix = " " + ConsoleSpec.inTime(result.duration());
        if (result.success()) {
            cm.finishSuccess(spec.onSuccess().apply(result) + suffix);
        } else {
            cm.finishFailure(spec.onFailure().apply(result) + suffix);
        }
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("verbatim".equals(d.code())) {
                err.println(d.message());
            } else {
                err.println("error[" + d.phase() + "/" + d.code() + "]: " + d.message());
            }
        }
        for (GoalResult.Diagnostic d : result.warnings()) {
            err.println("warn[" + d.phase() + "/" + d.code() + "]: " + d.message());
        }
    }

    private static String display(Phase p) {
        return p.label() != null && !p.label().isEmpty() ? p.label() : p.name();
    }
}
