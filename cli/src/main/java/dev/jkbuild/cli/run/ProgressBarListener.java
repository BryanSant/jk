// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Gradient;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.GoalView;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseStatus;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Combined spinner + progress bar listener.
 *
 * <p>Line layout:
 * <pre>
 *   {spin} {24-bar} [pct%] N of M › Goal › Phase step-message
 * </pre>
 *
 * <ul>
 *   <li>Spinner glyph animates every 120 ms on a daemon thread — no OSC 9;4.</li>
 *   <li>Bar segments send OSC 9;4 on every {@link #progress} event.</li>
 *   <li>Percent box {@code [  0%]}: dark-gray brackets, bold-white value.</li>
 *   <li>{@code N of M}: normal-gray, not fixed-width.</li>
 *   <li>Separators {@code ›}: dark-gray.</li>
 *   <li>Goal label: bold + bright-green.</li>
 *   <li>Phase label: bold.</li>
 *   <li>Step message: normal-gray.</li>
 * </ul>
 *
 * <p>On failure: bar repaints in the failure gradient, goal label becomes
 * bold + bright-red with strikethrough, everything after it is struck through.
 */
public final class ProgressBarListener implements GoalListener {

    private static final int BAR_SEGS = 24;
    private static final String[] SPIN_FRAMES = {"·", "✶", "✸", "✹", "✺", "✹", "✷", "✶", "·"};
    private static final long FRAME_MS = 120L;

    private static final char FILLED = '▰';
    private static final char EMPTY  = '▱';

    private static final String HIDE_CURSOR    = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR    = Ansi.SHOW_CURSOR;
    private static final String OSC_CLEAR        = Ansi.TASKBAR_CLEAR;

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> phaseLabels; // phase name → display label
    private final AttributedStyle[] spinColors;
    private final AttributedStyle[] barColors;
    private final AttributedStyle[] failColors;
    private final ConcurrentMap<String, String> activeLabels = new ConcurrentHashMap<>();
    private final boolean silent;

    private volatile boolean closed = false;
    private Thread animator;

    // Rendering state — all access under synchronized(this).
    private int spinFrame = 0;
    private long currentNumerator   = 0;
    private long currentDenominator = 0;
    private String currentPhase     = "";
    private String goalDisplayName  = "";
    private int lastStepMsgLen      = 0;
    private boolean drawn           = false;

    public ProgressBarListener(PrintStream out, PrintStream err, List<Phase> phases) {
        this.out = out;
        this.err = err;
        Map<String, String> labels = new HashMap<>();
        for (Phase p : phases) labels.put(p.name(), p.label());
        this.phaseLabels = Map.copyOf(labels);
        this.silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        // spinner = primary→accent; bar = green→bright-green; fail = dark→bright red.
        this.spinColors = buildGradient(SPIN_FRAMES.length, Theme.active().spinnerGradient());
        this.barColors = buildGradient(BAR_SEGS, Theme.active().progressGradient());
        this.failColors = buildGradient(BAR_SEGS, Theme.active().failureGradient());
    }

    @Override
    public synchronized void goalStart(GoalView view) {
        goalDisplayName  = capitalizeFirst(view.goalName());
        currentNumerator   = view.numerator();
        currentDenominator = view.denominator();
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
        currentNumerator   = view.numerator();
        currentDenominator = view.denominator();
        if (!silent) {
            out.print(Ansi.taskbarProgress(Math.min(99, view.percent())));
            out.flush();
        }
    }

    @Override
    public synchronized void scopeUpdate(String phase, int delta, GoalView view) {
        currentNumerator   = view.numerator();
        currentDenominator = view.denominator();
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
    public synchronized void output(String phase, String line) {
        writeAboveInternal(line);
    }

    @Override
    public synchronized void warn(String phase, String code, String message) {
        writeAboveInternal(renderDiagnostic("⚠ Warning", Theme.active().warning().bold(),
                phase, code, message));
    }

    @Override
    public synchronized void error(String phase, String code, String message) {
        writeAboveInternal(renderDiagnostic("✗ Error", Theme.active().error().bold(),
                phase, code, message));
    }

    private void writeAboveInternal(String line) {
        if (silent) {
            err.println(line);
            return;
        }
        if (drawn) out.print(Ansi.CLEAR_LINE);
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
            if (drawn) out.print(Ansi.CLEAR_LINE);
            out.print(OSC_CLEAR);
            out.print(SHOW_CURSOR);
            out.flush();
        }
    }

    // --- rendering ---------------------------------------------------------

    /** Full-line render. Must be called under {@code synchronized(this)}. */
    private void renderLine() {
        long num  = currentNumerator;
        long den  = currentDenominator;
        // Cap at 99 % while the goal is still running — only goalFinish
        // (success) transitions to 100 %, at which point the bar is wiped
        // and the command's own success line takes its place.
        double fraction = den <= 0 ? 0.0 : Math.min(0.99, (double) num / den);
        int pct  = (int) Math.round(fraction * 100);
        int segs = (int) Math.round(fraction * BAR_SEGS);
        String phase   = currentPhase;
        String stepMsg = activeLabels.getOrDefault(phase, "");

        out.print("\r");

        // Spinner glyph — no OSC 9;4.
        out.print(Theme.colorize(SPIN_FRAMES[spinFrame], spinColors[spinFrame]));
        spinFrame = (spinFrame + 1) % SPIN_FRAMES.length;
        out.print(" ");

        // Progress bar — OSC 9;4 sent in progress() callbacks, not here.
        for (int i = 0; i < BAR_SEGS; i++) {
            boolean filled = i < segs;
            char c = filled ? FILLED : EMPTY;
            AttributedStyle style = filled ? barColors[i] : Theme.active().dim();
            out.print(Theme.colorize(String.valueOf(c), style));
        }
        out.print(" ");

        // Percent box: [dark-gray-bracket bold-white-pct dark-gray-bracket]
        String pctStr = String.format("%3d%%", pct);
        out.print(Theme.colorize("[", Theme.active().darkGray()));
        out.print(Theme.colorize(pctStr, Theme.active().focused()));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");

        // N of M — normal-gray, not fixed-width.
        String countStr = num + " of " + (den <= 0 ? "—" : String.valueOf(den));
        out.print(Theme.colorize(countStr, Theme.active().normalGray()));
        out.print(" ");

        // › Goal › Phase step-message
        String sep = Theme.colorize("›", Theme.active().darkGray());
        out.print(sep);
        out.print(" ");
        out.print(Theme.colorize(goalDisplayName, Theme.active().brightGreen().bold()));
        out.print(" ");
        out.print(sep);
        out.print(" ");
        String phaseDisplay = phaseLabels.getOrDefault(phase, phase);
        out.print(Theme.colorize(phaseDisplay, AttributedStyle.DEFAULT.bold()));
        if (!stepMsg.isEmpty()) {
            out.print(" ");
            out.print(Theme.colorize(stepMsg, Theme.active().normalGray()));
        }

        // Shrink: step message may get shorter.
        int shrink = lastStepMsgLen - stepMsg.length();
        if (shrink > 0) out.print(" ".repeat(shrink));

        out.flush();
        lastStepMsgLen = stepMsg.length();
        drawn = true;
    }

    private void renderFailed() {
        long num  = currentNumerator;
        long den  = currentDenominator;
        int pct   = den <= 0 ? 0 : (int) Math.round((double) num / den * 100);
        int segs  = (int) Math.round(pct * BAR_SEGS / 100.0);
        String phase        = currentPhase;
        String phaseDisplay = phaseLabels.getOrDefault(phase, phase);
        String stepMsg      = activeLabels.getOrDefault(phase,
                phaseLabels.getOrDefault(phase, phase));

        out.print("\r");

        // Spinner at last frame.
        int f = (spinFrame == 0 ? SPIN_FRAMES.length : spinFrame) - 1;
        out.print(Theme.colorize(SPIN_FRAMES[f], spinColors[f]));
        out.print(" ");

        // Fail gradient bar.
        for (int i = 0; i < BAR_SEGS; i++) {
            out.print(Theme.colorize(String.valueOf(EMPTY), failColors[i]));
        }
        out.print(" ");

        // Percent box (unchanged).
        String pctStr = String.format("%3d%%", pct);
        out.print(Theme.colorize("[", Theme.active().darkGray()));
        out.print(Theme.colorize(pctStr, Theme.active().focused()));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");

        // N of M (unchanged).
        String countStr = num + " of " + (den <= 0 ? "—" : String.valueOf(den));
        out.print(Theme.colorize(countStr, Theme.active().normalGray()));
        out.print(" ");

        // › — unchanged.
        out.print(Theme.colorize("›", Theme.active().darkGray()));
        out.print(" ");

        // Goal label: bold + bright-red + strikethrough.
        AttributedStyle failGoal = Theme.active().error().bold().crossedOut();
        out.print(Theme.colorize(goalDisplayName, failGoal));
        out.print(" ");

        // Everything after goal label is struck through.
        AttributedStyle strike = Theme.active().dim().crossedOut();
        out.print(Theme.colorize("›", Theme.active().darkGray().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize(phaseDisplay, strike));
        if (!stepMsg.isEmpty()) {
            out.print(" ");
            out.print(Theme.colorize(stepMsg, strike));
        }
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println();
        out.flush();
    }

    private void renderCanceled() {
        long num = currentNumerator;
        long den = currentDenominator;
        int pct  = den <= 0 ? 0 : (int) Math.round((double) num / den * 100);
        String phase        = currentPhase;
        String phaseDisplay = phaseLabels.getOrDefault(phase, phase);
        String stepMsg      = activeLabels.getOrDefault(phase, "");
        AttributedStyle redStyle = Theme.active().error();

        out.print("\r");
        int f = (spinFrame == 0 ? SPIN_FRAMES.length : spinFrame) - 1;
        out.print(Theme.colorize(SPIN_FRAMES[f], spinColors[f]));
        out.print(" ");
        for (int i = 0; i < BAR_SEGS; i++) {
            char c = i < (int) Math.round(pct * BAR_SEGS / 100.0) ? FILLED : EMPTY;
            out.print(Theme.colorize(String.valueOf(c), redStyle));
        }
        out.print(" ");
        String pctStr = String.format("%3d%%", pct);
        out.print(Theme.colorize("[", Theme.active().darkGray()));
        out.print(Theme.colorize(pctStr, Theme.active().focused()));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");
        out.print(Theme.colorize(num + " of " + (den <= 0 ? "—" : den), Theme.active().normalGray()));
        out.print(" ");
        AttributedStyle strike = Theme.active().dim().crossedOut();
        out.print(Theme.colorize("›", Theme.active().darkGray()));
        out.print(" ");
        out.print(Theme.colorize(goalDisplayName, Theme.active().warning().bold().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize("›", Theme.active().darkGray().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize(phaseDisplay, strike));
        if (!stepMsg.isEmpty()) {
            out.print(" ");
            out.print(Theme.colorize(stepMsg, strike));
        }
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.flush();
    }

    private static String renderDiagnostic(
            String prefix, AttributedStyle prefixStyle,
            String phase, String code, String message) {
        String summary = message == null ? "" : message;
        String detail  = null;
        int sep = summary.indexOf(" — ");
        if (sep >= 0) {
            detail  = capitalize(summary.substring(sep + 3));
            summary = summary.substring(0, sep);
        }
        summary = capitalize(summary);
        var sb = new org.jline.utils.AttributedStringBuilder();
        sb.append(prefix, prefixStyle);
        sb.append(" [").append(phase).append("/").append(code).append("]: ");
        sb.append(summary, Theme.active().focused());
        if (detail != null) sb.append(" — ").append(detail, Theme.active().activeStep());
        return sb.toAnsi();
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        char first = s.charAt(0);
        return Character.isLowerCase(first)
                ? Character.toUpperCase(first) + s.substring(1) : s;
    }

    private static AttributedStyle[] buildGradient(int n, Gradient gradient) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }
}
