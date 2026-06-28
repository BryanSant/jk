// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Gradient;
import dev.jkbuild.cli.theme.Theme;
import java.io.PrintStream;
import org.jline.utils.AttributedStyle;

/**
 * Single-line progress bar widget for long-running CLI operations (downloading JDKs, installing
 * JDKs). Registers as the active {@link LiveRegion} so the global Ctrl-C handler can repaint it on
 * cancel.
 *
 * <p>Layout: {@code ▰▰▰▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱ 62%: <status>}
 *
 * <ul>
 *   <li>40 segments. Filled segments use a <em>moving</em> gradient anchored to the progress
 *       frontier: the right-most filled glyph is always the gradient end (orange {@code #ff8b1a})
 *       and the glyphs to its left trail back toward the gradient start (magenta {@code #e600ff}).
 *       Only the right end of the pre-computed 40-color gradient is used at first; the magenta end
 *       is introduced gradually as the bar fills, so the gradient looks pushed rightward as
 *       progress advances. Empty segments are faint.
 *   <li>The percent is right-aligned to 4 chars so the status doesn't jump columns as it advances.
 *   <li>Percent and status redraws are surgical — only the part of the line that changed; shrinking
 *       status overwrites the previous trailing chars with spaces (no further). The glyph row, by
 *       contrast, is repainted whole whenever the fill count changes, because the moving gradient
 *       re-colors every filled glyph at once.
 *   <li>Cursor is hidden between {@link #show(PrintStream)} and {@link #close()} and restored on
 *       exit.
 * </ul>
 *
 * <p>{@link #update(int, String)} is {@code synchronized} so producer threads can pump updates
 * directly — the typical pattern is a background worker computing progress and the main thread
 * polling, but either direction is safe.
 */
public final class SpinnerProgressBar implements AutoCloseable, LiveRegion {

    static final int SEGMENTS = 40;
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    /** Right-aligned percent column: " 5%", " 62%", "100%". */
    static final int PERCENT_WIDTH = 4;

    static final String GAP = "  ";
    static final String SEPARATOR = ": ";

    private static final String HIDE_CURSOR = Ansi.HIDE_CURSOR;
    private static final String SHOW_CURSOR = Ansi.SHOW_CURSOR;

    // OSC 9;4 — ConEmu-introduced taskbar/tab progress indicator, now
    // supported by Windows Terminal, WezTerm, ghostty, kitty (≥0.31),
    // and others. Format: ESC ] 9 ; 4 ; <state> [ ; <percent> ] BEL.
    // States: 0 = clear, 1 = normal, 2 = error, 3 = indeterminate,
    // 4 = warning. We use 1 (normal) while the bar advances and 0
    // (clear) on close. BEL terminator is more universally honored
    // than ST in OSC handling. Terminals that don't recognise OSC 9;4
    // silently swallow the sequence in their OSC parser.
    static final String OSC_CLEAR = Ansi.TASKBAR_CLEAR;

    private final PrintStream out;
    private final AttributedStyle[] segmentColors;
    private final AttributedStyle[] failColors;

    /** Empty glyphs take the gradient's left-most (darkest) color, not a neutral dim. */
    private final AttributedStyle emptyStyle;

    private final boolean silent;

    private int lastFilled = 0;
    private String lastPercent = "";
    private String lastStatus = "";
    private boolean drawn = false;
    private boolean closed = false;
    private int lastPercentVal = 0;

    private SpinnerProgressBar(PrintStream out, boolean silent) {
        this.out = out;
        this.silent = silent;
        // The fill runs green → bright-green (bright-green pinned at the frontier).
        this.segmentColors = buildGradient(SEGMENTS, Theme.active().progressGradient());
        this.failColors = buildGradient(SEGMENTS, Theme.active().failureGradient());
        this.emptyStyle = segmentColors[0];
    }

    /**
     * Start a new bar on the caller's current line, hiding the cursor. If the resolved config has
     * {@code --no-progress}, returns a silent instance whose {@link #update} / {@link #close} are
     * no-ops.
     */
    public static SpinnerProgressBar show(PrintStream out) {
        boolean silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        SpinnerProgressBar pb = new SpinnerProgressBar(out, silent);
        LiveRegion.setActive(pb);
        if (!silent) {
            out.print(HIDE_CURSOR);
            out.flush();
        }
        return pb;
    }

    /**
     * Update progress and status. {@code percent} is clamped to [0, 100]; {@code status} is rendered
     * after the percent, separated by {@code ": "}.
     */
    public synchronized void update(int percent, String status) {
        if (closed || silent) return;
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = (int) Math.round(clamped * SEGMENTS / 100.0);
        String percentStr = String.format("%3d%%", clamped);
        String statusStr = status == null ? "" : status;

        out.print(Ansi.taskbarProgress(clamped));
        if (!drawn) {
            renderInitial(filled, clamped, percentStr, statusStr);
        } else {
            renderDiff(filled, clamped, percentStr, statusStr);
        }
        out.flush();
        lastFilled = filled;
        lastPercent = percentStr;
        lastPercentVal = clamped;
        lastStatus = statusStr;
        drawn = true;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return;
        // Wipe the bar line entirely on the way out. Without the
        // CR + erase-line, a partial bar (e.g. a goal that failed
        // before any progress) would stay in the transcript next to
        // the failure summary that follows.
        if (drawn) out.print(Ansi.CLEAR_LINE);
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.flush();
    }

    /**
     * Wipe the progress-bar line and replace it with {@code message} on the same row, then close the
     * bar. Use this when the bar shouldn't linger in the transcript — e.g., a "Download finished"
     * line takes its place. Subsequent {@link #close()} calls are no-ops.
     */
    public synchronized void finish(String message) {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) {
            // With --no-progress the bar never drew, so we don't need to wipe
            // a line — but the final message is still useful to the user, so
            // emit it as a plain println.
            out.println(message);
            out.flush();
            return;
        }
        out.print(Ansi.CLEAR_LINE); // clear the bar line
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println(message);
        out.flush();
    }

    /**
     * Print {@code line} on its own row <em>above</em> the bar, without disturbing the bar's pinned
     * position. Wipes the current bar line, prints the message + newline, then redraws the bar in
     * place. Used by listeners to surface warnings / errors mid-run so they land cleanly in the
     * scroll-back instead of being glued to the end of the carriage-returned bar line. Safe to call
     * from any thread (synchronizes on this).
     *
     * <p>If the bar is silent or already closed, the message passes through as a plain {@code
     * println} on the bar's stream — there's no pinned row to clear.
     */
    public synchronized void writeAbove(String line) {
        if (silent || closed || !drawn) {
            out.println(line);
            out.flush();
            return;
        }
        out.print(Ansi.CLEAR_LINE); // wipe the bar line
        out.println(line); // emit the hoisted message
        // Force a fresh full redraw at the same position. Without resetting
        // `drawn`, renderDiff would no-op on identical state and the bar
        // wouldn't reappear.
        drawn = false;
        renderInitial(lastFilled, lastPercentVal, lastPercent, lastStatus);
        out.print(Ansi.taskbarProgress((int) Math.round(lastFilled * 100.0 / SEGMENTS)));
        out.flush();
        drawn = true;
    }

    /**
     * Repaint the bar as a final "Failed" line: bold red "Failed" prefix, the bar at its last filled
     * state in normal colors, status struck through. Caller-facing: terminates the line with a
     * newline so the shell prompt that follows doesn't overlay the bar.
     *
     * <p>Distinct from {@link #renderCanceled} which exists for explicit Ctrl-C — that one paints
     * every segment red and leaves the cursor mid-line for a follow-up "Force-cancelled" notice.
     */
    public synchronized void renderFailed() {
        if (closed) return;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return;
        AttributedStyle strikeStyle = Theme.active().dim().crossedOut();
        out.print("\r");
        out.print(Theme.colorize(Glyphs.CROSS + " Failed", Theme.active().error().bold()));
        out.print(" ");
        // Every segment painted with the dark-red→bright-red gradient.
        // We don't care about filled vs empty here — the bar's role at
        // this point is to mark *where* the goal stopped, not to
        // continue showing in-flight progress.
        for (int i = 0; i < SEGMENTS; i++) {
            out.print(Theme.colorize(String.valueOf(EMPTY_CHAR), failColors[i]));
        }
        out.print(GAP);
        out.print(Theme.colorize(lastPercent, percentStyle(lastPercentVal)));
        out.print(SEPARATOR);
        out.print(Theme.colorize(lastStatus, strikeStyle));
        out.print(Ansi.ERASE_LINE_TO_END); // wipe any residue past the (shorter) status
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println();
        out.flush();
    }

    /**
     * Repaint the bar as canceled: every segment in bright red, status struck through. Used by the
     * global SIGINT handler to mark the in-flight work before the process halts. Leaves the cursor at
     * the end of the bar line with no trailing newline so the caller can emit a follow-up line.
     */
    public synchronized boolean renderCanceled() {
        if (closed) return false;
        closed = true;
        LiveRegion.clearActive(this);
        if (silent) return false;
        AttributedStyle redStyle = Theme.active().error();
        AttributedStyle strikeStyle = Theme.active().dim().crossedOut();
        out.print("\r");
        for (int i = 0; i < SEGMENTS; i++) {
            char c = i < lastFilled ? FILLED_CHAR : EMPTY_CHAR;
            out.print(Theme.colorize(String.valueOf(c), redStyle));
        }
        out.print(GAP);
        out.print(Theme.colorize(lastPercent, percentStyle(lastPercentVal)));
        out.print(SEPARATOR);
        out.print(Theme.colorize(lastStatus, strikeStyle));
        out.print(Ansi.ERASE_LINE_TO_END); // wipe any residue past the (shorter) cancel status
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.flush();
        // Cursor is left mid-line with no newline; GlobalCancel prints the
        // "‼ Canceled by user" notice on the next line.
        return false;
    }

    private void renderInitial(int filled, int percent, String percentStr, String status) {
        out.print("\r");
        renderSegments(0, SEGMENTS, filled);
        out.print(GAP);
        out.print(Theme.colorize(percentStr, percentStyle(percent)));
        out.print(SEPARATOR);
        out.print(Theme.colorize(status, Theme.active().dim()));
    }

    private void renderDiff(int filled, int percent, String percentStr, String status) {
        // Segments: the moving gradient re-colors every filled glyph when
        // the frontier advances, so there's no surgical sub-range to redraw.
        // Jump to the first glyph and overwrite the whole row in place.
        if (filled != lastFilled) {
            moveToCol(1);
            renderSegments(0, SEGMENTS, filled);
        }
        // Percent: repaint the 4-char block (it's already fixed-width).
        if (!percentStr.equals(lastPercent)) {
            moveToCol(1 + SEGMENTS + GAP.length());
            out.print(Theme.colorize(percentStr, percentStyle(percent)));
        }
        // Status: repaint; overwrite shrinkage with the exact number of spaces.
        if (!status.equals(lastStatus)) {
            moveToCol(statusCol());
            out.print(Theme.colorize(status, Theme.active().dim()));
            int shrink = lastStatus.length() - status.length();
            if (shrink > 0) out.print(" ".repeat(shrink));
        }
    }

    private void renderSegments(int from, int to, int filled) {
        for (int i = from; i < to; i++) {
            boolean isFilled = i < filled;
            char c = isFilled ? FILLED_CHAR : EMPTY_CHAR;
            AttributedStyle style = isFilled ? filledColor(i, filled) : emptyStyle;
            out.print(Theme.colorize(String.valueOf(c), style));
        }
    }

    /**
     * Color for the filled glyph at zero-based position {@code i} when {@code filled} glyphs are lit.
     * The right-most filled glyph ({@code i == filled - 1}) maps to the last pre-computed color (the
     * gradient end, orange); each glyph to the left steps one entry back toward the gradient start
     * (magenta). With {@code filled} small only the orange tail of the gradient shows; the magenta
     * head appears as the bar fills, so the band looks pushed rightward by the frontier.
     */
    private AttributedStyle filledColor(int i, int filled) {
        return segmentColors[SEGMENTS - filled + i];
    }

    private static int statusCol() {
        // 1-based column where the status text begins.
        return 1 + SEGMENTS + GAP.length() + PERCENT_WIDTH + SEPARATOR.length();
    }

    private void moveToCol(int col) {
        out.print(Ansi.cursorToColumn(col));
    }

    static AttributedStyle[] buildGradient(int n) {
        return buildGradient(n, Theme.active().progressGradient());
    }

    static AttributedStyle[] buildGradient(int n, Gradient gradient) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }

    private AttributedStyle percentStyle(int pct) {
        double t = Math.max(0.0, Math.min(1.0, (double) pct / 100.0));
        return Theme.active().bright(Theme.active().progressGradient().at(t)).bold();
    }
}
