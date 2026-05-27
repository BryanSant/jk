// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Combined spinner + phase label + progress bar listener.
 *
 * <p>Layout on each tick:
 * <pre>
 *   {glyph} {phase-label-padded} {24-bar-segments} - {step-message}
 * </pre>
 *
 * <ul>
 *   <li>The spinner glyph animates every 120 ms on a daemon thread.
 *       It does <em>not</em> emit OSC 9;4 — the bar segments own that.</li>
 *   <li>The bar emits OSC 9;4 on every {@link #progress} event so the
 *       terminal's taskbar indicator stays in sync with actual progress.</li>
 *   <li>The phase label is padded to the width of the widest phase name
 *       so the bar position never shifts between phases.</li>
 * </ul>
 *
 * <p>On success the widget line is wiped so the command's success
 * println occupies the same row. On failure the bar is repainted with
 * a red failure gradient + strikethrough label.
 */
public final class ProgressBarListener implements GoalListener {

    private static final int BAR_SEGS = 24;
    private static final String[] SPIN_FRAMES = {"·", "✢", "✳", "✶", "✻", "✽"};
    private static final long FRAME_MS = 120L;

    // Gradient: violet #8150fe → coral #e3475b (shared by spinner and bar).
    private static final int GRAD_SR = 0x81, GRAD_SG = 0x50, GRAD_SB = 0xfe;
    private static final int GRAD_ER = 0xe3, GRAD_EG = 0x47, GRAD_EB = 0x5b;

    // Failure gradient: dark red #7f1d1d → bright red #ef4444.
    private static final int FAIL_SR = 0x7f, FAIL_SG = 0x1d, FAIL_SB = 0x1d;
    private static final int FAIL_ER = 0xef, FAIL_EG = 0x44, FAIL_EB = 0x44;

    private static final char FILLED = '▰';
    private static final char EMPTY  = '▱';

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    // OSC 9;4 — emitted by the bar (not the spinner).
    private static final String OSC_CLEAR        = "\033]9;4;0\007";
    private static final String OSC_PROGRESS_FMT = "\033]9;4;1;%d\007";

    private final PrintStream out;
    private final PrintStream err;
    private final int maxPhaseWidth;
    private final AttributedStyle[] spinColors;
    private final AttributedStyle[] barColors;
    private final AttributedStyle[] failColors;
    private final ConcurrentMap<String, String> activeLabels = new ConcurrentHashMap<>();
    private final boolean silent;

    private volatile boolean closed = false;
    private Thread animator;

    // Rendering state — all accesses under synchronized(this).
    private int spinFrame = 0;
    private int currentPercent = 0;
    private String currentPhase = "";
    private int lastLabelLen = 0;
    private boolean drawn = false;

    public ProgressBarListener(PrintStream out, PrintStream err, List<String> phaseNames) {
        this.out = out;
        this.err = err;
        int w = 12;
        for (String n : phaseNames) if (n.length() > w) w = n.length();
        this.maxPhaseWidth = w;
        this.silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        this.spinColors = buildGradient(SPIN_FRAMES.length,
                GRAD_SR, GRAD_SG, GRAD_SB, GRAD_ER, GRAD_EG, GRAD_EB);
        this.barColors = buildGradient(BAR_SEGS,
                GRAD_SR, GRAD_SG, GRAD_SB, GRAD_ER, GRAD_EG, GRAD_EB);
        this.failColors = buildGradient(BAR_SEGS,
                FAIL_SR, FAIL_SG, FAIL_SB, FAIL_ER, FAIL_EG, FAIL_EB);
    }

    @Override
    public void goalStart(GoalView view) {
        if (silent) return;
        out.print(HIDE_CURSOR);
        out.flush();
        animator = new Thread(this::animateLoop, "jk-build-progress");
        animator.setDaemon(true);
        animator.start();
    }

    private void animateLoop() {
        while (!closed) {
            synchronized (this) {
                if (!closed) renderLine();
            }
            try {
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public synchronized void phaseStart(String phase, int scope) {
        currentPhase = phase;
    }

    @Override
    public synchronized void progress(String phase, int delta, GoalView view) {
        currentPercent = view.percent();
        // Emit OSC 9;4 immediately on progress — bar is the OSC sender, not the spinner.
        if (!silent) {
            out.print(String.format(OSC_PROGRESS_FMT, currentPercent));
            out.flush();
        }
    }

    @Override
    public synchronized void scopeUpdate(String phase, int delta, GoalView view) {
        currentPercent = view.percent();
    }

    @Override
    public void label(String phase, String label) {
        if (label == null || label.isEmpty()) {
            activeLabels.remove(phase);
        } else {
            activeLabels.put(phase, label);
        }
    }

    @Override
    public synchronized void warn(String phase, String code, String message) {
        String line = renderDiagnostic("⚠ Warning", Theme.warning().bold(),
                phase, code, message);
        writeAboveInternal(line);
    }

    @Override
    public synchronized void error(String phase, String code, String message) {
        String line = renderDiagnostic("✗ Error", Theme.error().bold(),
                phase, code, message);
        writeAboveInternal(line);
    }

    /** Must be called under {@code synchronized(this)}. */
    private void writeAboveInternal(String line) {
        if (silent) {
            err.println(line);
            return;
        }
        if (drawn) out.print("\r\033[K");
        out.println(line);
        drawn = false;
        renderLine();
    }

    @Override
    public void phaseFinish(String phase, PhaseStatus status, java.time.Duration duration) {
        activeLabels.remove(phase);
    }

    @Override
    public synchronized void goalFinish(GoalResult result) {
        closed = true;
        if (animator != null) animator.interrupt();
        if (silent) return;
        if (result.userCancelled()) {
            renderCanceled();
            err.println();
        } else if (!result.success()) {
            renderFailed();
        } else {
            // Success: wipe the widget line — the command's success println takes its place.
            if (drawn) out.print("\r\033[K");
            out.print(OSC_CLEAR);
            out.print(SHOW_CURSOR);
            out.flush();
        }
    }

    // --- rendering ---------------------------------------------------------

    /** Full-line render. Must be called under {@code synchronized(this)}. */
    private void renderLine() {
        int pct  = Math.max(0, Math.min(100, currentPercent));
        int segs = (int) Math.round(pct * BAR_SEGS / 100.0);
        String phase = currentPhase;
        String label = activeLabels.getOrDefault(phase, "");

        out.print("\r");
        // Spinner glyph — no OSC 9;4.
        out.print(Theme.colorize(SPIN_FRAMES[spinFrame], spinColors[spinFrame]));
        spinFrame = (spinFrame + 1) % SPIN_FRAMES.length;
        out.print(" ");
        // Phase label: bold + bright-green, padded to maxPhaseWidth.
        String phasePadded = String.format("%-" + maxPhaseWidth + "s", phase);
        out.print(Theme.colorize(phasePadded, Theme.brightGreen().bold()));
        out.print(" ");
        // Bar segments — OSC 9;4 is sent in progress() callbacks, not here.
        for (int i = 0; i < BAR_SEGS; i++) {
            boolean filled = i < segs;
            char c = filled ? FILLED : EMPTY;
            AttributedStyle style = filled ? barColors[i] : Theme.dim();
            out.print(Theme.colorize(String.valueOf(c), style));
        }
        // Step message.
        out.print(" - ");
        out.print(Theme.colorize(label, Theme.dim()));
        int shrink = lastLabelLen - label.length();
        if (shrink > 0) out.print(" ".repeat(shrink));
        out.flush();
        lastLabelLen = label.length();
        drawn = true;
    }

    private void renderFailed() {
        String label = activeLabels.getOrDefault(currentPhase, currentPhase);
        out.print("\r");
        out.print(Theme.colorize("Failed", Theme.error().bold()));
        out.print(" ");
        for (int i = 0; i < BAR_SEGS; i++) {
            out.print(Theme.colorize(String.valueOf(EMPTY), failColors[i]));
        }
        out.print(" - ");
        out.print(Theme.colorize(label, Theme.dim().crossedOut()));
        out.print("\033[K"); // wipe residue past the new label length
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println();
        out.flush();
    }

    private void renderCanceled() {
        int filled = (int) Math.round(currentPercent * BAR_SEGS / 100.0);
        String label = activeLabels.getOrDefault(currentPhase, currentPhase);
        AttributedStyle redStyle = Theme.error();
        out.print("\r");
        for (int i = 0; i < BAR_SEGS; i++) {
            char c = i < filled ? FILLED : EMPTY;
            out.print(Theme.colorize(String.valueOf(c), redStyle));
        }
        out.print(" - ");
        out.print(Theme.colorize(label, Theme.dim().crossedOut()));
        out.print("\033[K");
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        // No println — caller emits one for the "Canceled" suffix line.
        out.flush();
    }

    /**
     * Render an inline diagnostic line:
     * <pre>
     *   ✗ Error [phase/code]: <b>Summary</b> — Detail.
     * </pre>
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
        var sb = new org.jline.utils.AttributedStringBuilder();
        sb.append(prefix, prefixStyle);
        sb.append(" [").append(phase).append("/").append(code).append("]: ");
        sb.append(summary, Theme.focused());
        if (detail != null) {
            sb.append(" — ").append(detail, Theme.activeStep());
        }
        return sb.toAnsi();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        char first = s.charAt(0);
        return Character.isLowerCase(first)
                ? Character.toUpperCase(first) + s.substring(1) : s;
    }

    private static AttributedStyle[] buildGradient(int n,
            int sr, int sg, int sb, int er, int eg, int eb) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            int r = (int) Math.round(sr + t * (er - sr));
            int g = (int) Math.round(sg + t * (eg - sg));
            int b = (int) Math.round(sb + t * (eb - sb));
            a[i] = Theme.bright(r, g, b);
        }
        return a;
    }
}
