// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.PhaseStatus;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

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
    public synchronized void warn(String phase, String code, String message) {
        String line = renderDiagnostic("⚠ Warning", Theme.warning().bold(),
                phase, code, message);
        if (bar != null) {
            bar.writeAbove(line);
        } else {
            err.println(line);
        }
    }

    @Override
    public synchronized void error(String phase, String code, String message) {
        String line = renderDiagnostic("✗ Error", Theme.error().bold(),
                phase, code, message);
        if (bar != null) {
            bar.writeAbove(line);
        } else {
            err.println(line);
        }
    }

    /**
     * Render an inline diagnostic line in the canonical shape:
     * <pre>
     *   ✗ Error [phase/code]: <b>Summary</b> — Detail.
     * </pre>
     * The message is split on the first {@code " — "}: text before
     * becomes the bold-white summary, text after stays in default
     * weight. Both halves get their first letter auto-capitalized so
     * the rendering looks like a proper sentence regardless of how
     * the diagnostic was authored.
     */
    private static String renderDiagnostic(
            String prefix, AttributedStyle prefixStyle,
            String phase, String code, String message) {
        String summary = message == null ? "" : message;
        String detail = null;
        int sep = summary.indexOf(" — ");
        if (sep >= 0) {
            detail = capitalize(summary.substring(sep + 3));
            summary = summary.substring(0, sep);
        }
        summary = capitalize(summary);
        AttributedStringBuilder sb = new AttributedStringBuilder();
        sb.append(prefix, prefixStyle);
        sb.append(" [").append(phase).append("/").append(code).append("]: ");
        sb.append(summary, Theme.focused());
        if (detail != null) {
            sb.append(" — ").append(detail);
        }
        return sb.toAnsi();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        char first = s.charAt(0);
        if (!Character.isLowerCase(first)) return s;
        return Character.toUpperCase(first) + s.substring(1);
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, java.time.Duration duration) {
        activeLabels.remove(phase);
    }

    @Override
    public synchronized void goalFinish(GoalResult result) {
        if (bar == null) return;
        if (result.userCancelled()) {
            // Explicit Ctrl-C — preserve the red struck-through shape so
            // the user can see where the run was when they aborted it.
            // (Goal.cancelled() also flips on internal short-circuit after
            // a phase failure, so we use the more specific userCancelled
            // flag to avoid mis-rendering plain failures as user cancels.)
            bar.renderCanceled();
            err.println();
        } else if (!result.success()) {
            // Plain failure: paint "Failed" + struck-through label.
            // The bar IS the failure summary now — commands no longer
            // print their own redundant "jk X failed: …" block.
            bar.renderFailed();
        } else {
            // Success: wipe the bar entirely. The command's own
            // success line ("Built …", "Wrote …") takes its place.
            bar.close();
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
