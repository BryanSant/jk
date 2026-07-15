// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import build.jumpkick.plugin.build.Phase;
import build.jumpkick.cli.Ansi;
import build.jumpkick.cli.theme.Gradient;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.PipelineView;
import build.jumpkick.run.Step;
import build.jumpkick.run.StepStatus;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jline.utils.AttributedStyle;

/**
 * Combined spinner + progress bar listener.
 *
 * <p>Line layout:
 *
 * <pre>
 *   {spin} {24-bar} [pct%] N of M › Pipeline › Step step-message
 * </pre>
 *
 * <ul>
 *   <li>Spinner glyph animates every 120 ms on a daemon thread — no OSC 9;4.
 *   <li>Bar segments send OSC 9;4 on every {@link #progress} event.
 *   <li>Percent box {@code [ 0%]}: dark-gray brackets, bold-white value.
 *   <li>{@code N of M}: normal-gray, not fixed-width.
 *   <li>Separators {@code ›}: dark-gray.
 *   <li>Pipeline label: bold + bright-green.
 *   <li>Step label: bold.
 *   <li>Step message: normal-gray.
 * </ul>
 *
 * <p>On failure: bar repaints in the failure gradient, pipeline label becomes bold + bright-red with
 * strikethrough, everything after it is struck through.
 */
public final class ProgressBarListener implements PipelineListener {

    private static final int BAR_SEGS = 24;
    private static final String[] SPIN_FRAMES = {"·", "✶", "✸", "✹", "✺", "✹", "✷", "✶", "·"};
    private static final long FRAME_MS = 120L;

    private static final char FILLED = '▰';
    private static final char EMPTY = '▱';

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;
    private static final String OSC_CLEAR = Ansi.TASKBAR_CLEAR;

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> stepLabels; // step name → display label
    private final AttributedStyle[] spinColors;
    private final AttributedStyle[] barColors;
    private final AttributedStyle[] failColors;
    private final ConcurrentMap<String, String> activeLabels = new ConcurrentHashMap<>();
    private final boolean silent;

    private volatile boolean closed = false;
    private Thread animator;

    // Rendering state — all access under synchronized(this).
    private int spinFrame = 0;
    private long currentNumerator = 0;
    private long currentDenominator = 0;
    private String currentStep = "";
    private String pipelineDisplayName = "";
    private int lastStepMsgLen = 0;
    private boolean drawn = false;

    public ProgressBarListener(PrintStream out, PrintStream err, List<Step> steps) {
        this.out = out;
        this.err = err;
        Map<String, String> labels = new HashMap<>();
        for (Step p : steps) labels.put(p.name(), p.label());
        this.stepLabels = Map.copyOf(labels);
        this.silent = build.jumpkick.config.SessionContext.current().config().noProgressOr(false);
        // spinner = primary→accent; bar = green→bright-green; fail = dark→bright red.
        this.spinColors = buildGradient(SPIN_FRAMES.length, Theme.active().spinnerGradient());
        this.barColors = buildGradient(BAR_SEGS, Theme.active().progressGradient());
        this.failColors = buildGradient(BAR_SEGS, Theme.active().failureGradient());
    }

    @Override
    public synchronized void pipelineStart(PipelineView view) {
        pipelineDisplayName = capitalizeFirst(view.pipelineName());
        currentNumerator = view.numerator();
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
    public synchronized void stepStart(String step, Phase phase, int ticks) {
        currentStep = step;
    }

    @Override
    public synchronized void progress(String step, int delta, PipelineView view) {
        currentNumerator = view.numerator();
        currentDenominator = view.denominator();
        if (!silent) {
            out.print(Ansi.taskbarProgress(Math.min(99, view.percent())));
            out.flush();
        }
    }

    @Override
    public synchronized void tickUpdate(String step, int delta, PipelineView view) {
        currentNumerator = view.numerator();
        currentDenominator = view.denominator();
    }

    @Override
    public void label(String step, String label) {
        if (label == null || label.isEmpty()) {
            activeLabels.remove(step);
        } else {
            activeLabels.put(step, label);
        }
    }

    @Override
    public synchronized void output(String step, String line) {
        writeAboveInternal(StackTraceHighlight.line(line));
    }

    @Override
    public synchronized void warn(String step, String code, String message) {
        if (ConsoleSpec.isCompilerCode(code)) {
            writeAboveInternal(ConsoleSpec.compilerWarning(step, message));
        } else {
            writeAboveInternal(
                    renderDiagnostic(Glyphs.BANG + " Warning", Theme.active().warning().bold(), step, code, message));
        }
    }

    @Override
    public synchronized void error(String step, String code, String message) {
        // Per-test failures (code "test-failure") are rendered in full by the
        // run-tests step's failure block; the diagnostic is kept only for
        // --output json, so don't also print it inline here.
        if ("test-failure".equals(code)) {
            return;
        }
        if ("verbatim".equals(code) || ConsoleSpec.isCompilerCode(code)) {
            writeAboveInternal(ConsoleSpec.renderError(step, code, message));
        } else {
            writeAboveInternal(
                    renderDiagnostic(Glyphs.CROSS + " Error", Theme.active().error().bold(), step, code, message));
        }
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
    public void stepFinish(String step, Phase phase, StepStatus status, java.time.Duration duration) {
        activeLabels.remove(step);
    }

    @Override
    public synchronized void pipelineFinish(PipelineResult result) {
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
        long num = currentNumerator;
        long den = currentDenominator;
        // Cap at 99 % while the pipeline is still running — only pipelineFinish
        // (success) transitions to 100 %, at which point the bar is wiped
        // and the command's own success line takes its place.
        double fraction = den <= 0 ? 0.0 : Math.min(0.99, (double) num / den);
        int pct = (int) Math.round(fraction * 100);
        int segs = (int) Math.round(fraction * BAR_SEGS);
        String step = currentStep;
        String stepMsg = activeLabels.getOrDefault(step, "");

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
        out.print(Theme.colorize(pctStr, percentStyle(pct)));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");

        // N of M — normal-gray, not fixed-width.
        String countStr = num + " of " + (den <= 0 ? "—" : String.valueOf(den));
        out.print(Theme.colorize(countStr, Theme.active().normalGray()));
        out.print(" ");

        // › Pipeline › Step step-message
        String sep = Theme.colorize("›", Theme.active().darkGray());
        out.print(sep);
        out.print(" ");
        out.print(Theme.colorize(pipelineDisplayName, Theme.active().brightGreen().bold()));
        out.print(" ");
        out.print(sep);
        out.print(" ");
        String stepDisplay = stepLabels.getOrDefault(step, step);
        out.print(Theme.colorize(stepDisplay, Theme.active().focused()));
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
        long num = currentNumerator;
        long den = currentDenominator;
        int pct = den <= 0 ? 0 : (int) Math.round((double) num / den * 100);
        int segs = (int) Math.round(pct * BAR_SEGS / 100.0);
        String step = currentStep;
        String stepDisplay = stepLabels.getOrDefault(step, step);
        String stepMsg = activeLabels.getOrDefault(step, stepLabels.getOrDefault(step, step));

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
        out.print(Theme.colorize(pctStr, percentStyle(pct)));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");

        // N of M (unchanged).
        String countStr = num + " of " + (den <= 0 ? "—" : String.valueOf(den));
        out.print(Theme.colorize(countStr, Theme.active().normalGray()));
        out.print(" ");

        // › — unchanged.
        out.print(Theme.colorize("›", Theme.active().darkGray()));
        out.print(" ");

        // Pipeline label: bold + bright-red + strikethrough.
        AttributedStyle failPipeline = Theme.active().error().bold().crossedOut();
        out.print(Theme.colorize(pipelineDisplayName, failPipeline));
        out.print(" ");

        // Everything after pipeline label is struck through.
        AttributedStyle strike = Theme.active().dim().crossedOut();
        out.print(Theme.colorize("›", Theme.active().darkGray().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize(stepDisplay, strike));
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
        int pct = den <= 0 ? 0 : (int) Math.round((double) num / den * 100);
        String step = currentStep;
        String stepDisplay = stepLabels.getOrDefault(step, step);
        String stepMsg = activeLabels.getOrDefault(step, "");
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
        out.print(Theme.colorize(pctStr, percentStyle(pct)));
        out.print(Theme.colorize("]", Theme.active().darkGray()));
        out.print(" ");
        out.print(Theme.colorize(
                num + " of " + (den <= 0 ? "—" : den), Theme.active().normalGray()));
        out.print(" ");
        AttributedStyle strike = Theme.active().dim().crossedOut();
        out.print(Theme.colorize("›", Theme.active().darkGray()));
        out.print(" ");
        out.print(
                Theme.colorize(pipelineDisplayName, Theme.active().warning().bold().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize("›", Theme.active().darkGray().crossedOut()));
        out.print(" ");
        out.print(Theme.colorize(stepDisplay, strike));
        if (!stepMsg.isEmpty()) {
            out.print(" ");
            out.print(Theme.colorize(stepMsg, strike));
        }
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.flush();
    }

    /** Shared with {@link AggregateModuleListener} so workspace + single-project render alike. */
    static String renderDiagnostic(
            String prefix, AttributedStyle prefixStyle, String step, String code, String message) {
        String summary = message == null ? "" : message;
        String detail = null;
        int sep = summary.indexOf(" — ");
        if (sep >= 0) {
            detail = capitalize(summary.substring(sep + 3));
            summary = summary.substring(0, sep);
        }
        // Only capitalize when the summary looks like a sentence start (first char
        // is a plain letter not followed by a hyphen — artifact names like
        // "jk-audit-runner" should stay lowercase).
        if (!summary.isEmpty()
                && Character.isLowerCase(summary.charAt(0))
                && (summary.length() < 2 || summary.charAt(1) != '-')) {
            summary = capitalize(summary);
        }
        var sb = new org.jline.utils.AttributedStringBuilder();
        sb.append(prefix, prefixStyle);
        // Omit [step/code] when code is absent — keeps simple informational
        // warnings (e.g. missing worker jars) uncluttered.
        if (code != null && !code.isBlank()) {
            sb.append(" [").append(step).append("/").append(code).append("]");
        }
        sb.append(": ");
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
        return Character.isLowerCase(first) ? Character.toUpperCase(first) + s.substring(1) : s;
    }

    private static AttributedStyle[] buildGradient(int n, Gradient gradient) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }

    private static AttributedStyle percentStyle(int pct) {
        double t = Math.max(0.0, Math.min(1.0, (double) pct / 100.0));
        return Theme.active().bright(Theme.active().progressGradient().at(t)).bold();
    }
}
