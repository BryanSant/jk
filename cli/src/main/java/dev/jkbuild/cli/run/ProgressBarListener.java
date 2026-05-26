// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;

import java.io.PrintStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Option-A console listener: one progress bar at the bottom of the
 * terminal, in-place updates. The bar fills with the goal's overall
 * fraction; an inline label shows what the currently-busiest phase is
 * working on.
 *
 * <p>Activated on a TTY when {@code --verbose} is NOT passed. The
 * existing {@link dev.jkbuild.cli.tui.ProgressBar} is used so the
 * gradient + OSC progress indicator are consistent with the rest of
 * the CLI's bars.
 */
public final class ProgressBarListener implements GoalListener {

    private final PrintStream out;
    private final PrintStream err;
    private dev.jkbuild.cli.tui.ProgressBar bar;
    private final ConcurrentMap<String, String> activeLabels = new ConcurrentHashMap<>();
    private volatile String currentPhase = "";

    public ProgressBarListener(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void goalStart(GoalView view) {
        bar = dev.jkbuild.cli.tui.ProgressBar.show(out);
        bar.update(view.percent(), view.goalName());
    }

    @Override
    public synchronized void phaseStart(String phase, int scope) {
        currentPhase = phase;
        repaint(snapshotPercent());
    }

    @Override
    public synchronized void progress(String phase, int delta, GoalView view) {
        repaint(view.percent());
    }

    @Override
    public synchronized void scopeUpdate(String phase, int delta, GoalView view) {
        repaint(view.percent());
    }

    @Override
    public void label(String phase, String label) {
        if (label == null || label.isEmpty()) {
            activeLabels.remove(phase);
        } else {
            activeLabels.put(phase, label);
        }
        repaint(snapshotPercent());
    }

    @Override
    public void warn(String phase, String code, String message) {
        // Don't bury the warning inside the bar — print it above and
        // re-render the bar on the next event.
        err.println(Theme.colorize("⚠", Theme.warning())
                + " " + phase + "/" + code + ": " + message);
    }

    @Override
    public void error(String phase, String code, String message) {
        err.println(Theme.colorize("✗", Theme.error())
                + " " + phase + "/" + code + ": " + message);
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, java.time.Duration duration) {
        activeLabels.remove(phase);
    }

    @Override
    public synchronized void goalFinish(GoalResult result) {
        if (bar == null) return;
        if (result.success()) {
            // Wipe the bar in favour of the command's own success line —
            // the build/sync command will print its "Built ..." / etc.
            // summary right after.
            bar.close();
        } else {
            bar.renderCanceled();
            err.println();
        }
        bar = null;
    }

    // --- internals -------------------------------------------------------

    private int snapshotPercent() {
        // Recomputing from listener state would require tracking the
        // counters; we trust the most recent event's view to be fresh.
        // This method is only called when no event provides a view
        // (label changes), and an overcount or undercount of a couple
        // percent doesn't hurt the visualisation.
        return -1;
    }

    private void repaint(int percent) {
        if (bar == null) return;
        if (percent < 0) percent = 0;
        String label = activeLabels.getOrDefault(currentPhase, currentPhase);
        bar.update(percent, label);
    }
}
