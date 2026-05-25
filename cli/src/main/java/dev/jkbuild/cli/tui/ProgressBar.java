// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-line progress bar widget for long-running CLI operations
 * (downloading JDKs, installing JDKs, running builds).
 *
 * <p>Layout: {@code ▰▰▰▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱  62%: <status>}
 *
 * <ul>
 *   <li>40 segments. Filled segments use a per-position gradient from
 *       violet {@code #8150fe} to coral {@code #e3475b} (the reverse of
 *       the wizard title gradient); empty segments are faint.</li>
 *   <li>The percent is right-aligned to 4 chars so the status doesn't
 *       jump columns as it advances.</li>
 *   <li>Redraws are surgical: only the segments that flipped, only the
 *       part of the line that changed. Shrinking status overwrites the
 *       previous trailing chars with spaces (no further).</li>
 *   <li>Cursor is hidden between {@link #show(PrintStream)} and
 *       {@link #close()} and restored on exit.</li>
 * </ul>
 *
 * <p>{@link #update(int, String)} is {@code synchronized} so producer
 * threads can pump updates directly — the typical pattern is a background
 * worker computing progress and the main thread polling, but either
 * direction is safe.
 */
public final class ProgressBar implements AutoCloseable {

    static final int SEGMENTS = 40;
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    /** Right-aligned percent column: "  5%", " 62%", "100%". */
    static final int PERCENT_WIDTH = 4;
    static final String GAP = "  ";
    static final String SEPARATOR = ": ";

    // Gradient: reverse of the wizard title — violet #8150fe → coral #e3475b.
    private static final int START_R = 0x81, START_G = 0x50, START_B = 0xfe;
    private static final int END_R = 0xe3, END_G = 0x47, END_B = 0x5b;

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

    // OSC 9;4 — ConEmu-introduced taskbar/tab progress indicator, now
    // supported by Windows Terminal, WezTerm, ghostty, kitty (≥0.31),
    // and others. Format: ESC ] 9 ; 4 ; <state> [ ; <percent> ] BEL.
    // States: 0 = clear, 1 = normal, 2 = error, 3 = indeterminate,
    // 4 = warning. We use 1 (normal) while the bar advances and 0
    // (clear) on close. BEL terminator is more universally honored
    // than ST in OSC handling. Terminals that don't recognise OSC 9;4
    // silently swallow the sequence in their OSC parser.
    static final String OSC_CLEAR = "\033]9;4;0\007";
    static final String OSC_PROGRESS_FMT = "\033]9;4;1;%d\007";

    /**
     * The bar currently on screen, if any. Read by the global Ctrl-C handler
     * (in {@link GlobalCancel}) so a cancellation can repaint the bar before
     * halting the process.
     */
    private static final AtomicReference<ProgressBar> ACTIVE = new AtomicReference<>();

    /** @return the visible bar, or {@code null} if none is active. */
    public static ProgressBar active() {
        return ACTIVE.get();
    }

    private final PrintStream out;
    private final AttributedStyle[] segmentColors;
    private final AttributedStyle emptyStyle = Theme.dim();

    private int lastFilled = 0;
    private String lastPercent = "";
    private String lastStatus = "";
    private boolean drawn = false;
    private boolean closed = false;

    private ProgressBar(PrintStream out) {
        this.out = out;
        this.segmentColors = buildGradient(SEGMENTS);
    }

    /** Start a new bar on the caller's current line, hiding the cursor. */
    public static ProgressBar show(PrintStream out) {
        ProgressBar pb = new ProgressBar(out);
        ACTIVE.set(pb);
        out.print(HIDE_CURSOR);
        out.flush();
        return pb;
    }

    /**
     * Update progress and status. {@code percent} is clamped to [0, 100];
     * {@code status} is rendered after the percent, separated by {@code ": "}.
     */
    public synchronized void update(int percent, String status) {
        if (closed) return;
        int clamped = Math.max(0, Math.min(100, percent));
        int filled = (int) Math.round(clamped * SEGMENTS / 100.0);
        String percentStr = String.format("%3d%%", clamped);
        String statusStr = status == null ? "" : status;

        out.printf(OSC_PROGRESS_FMT, clamped);
        if (!drawn) {
            renderInitial(filled, percentStr, statusStr);
        } else {
            renderDiff(filled, percentStr, statusStr);
        }
        out.flush();
        lastFilled = filled;
        lastPercent = percentStr;
        lastStatus = statusStr;
        drawn = true;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ACTIVE.compareAndSet(this, null);
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println();
        out.flush();
    }

    /**
     * Wipe the progress-bar line and replace it with {@code message} on
     * the same row, then close the bar. Use this when the bar shouldn't
     * linger in the transcript — e.g., a "Download finished" line takes
     * its place. Subsequent {@link #close()} calls are no-ops.
     */
    public synchronized void finish(String message) {
        if (closed) return;
        closed = true;
        ACTIVE.compareAndSet(this, null);
        out.print("\r\033[K");      // clear the bar line
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.println(message);
        out.flush();
    }

    /**
     * Repaint the bar as canceled: every segment in bright red, status struck
     * through. Used by the global SIGINT handler to mark the in-flight work
     * before the process halts. Leaves the cursor at the end of the bar line
     * with no trailing newline so the caller can emit a follow-up line.
     */
    public synchronized void renderCanceled() {
        if (closed) return;
        closed = true;
        ACTIVE.compareAndSet(this, null);
        AttributedStyle redStyle = Theme.error();
        AttributedStyle strikeStyle = Theme.dim().crossedOut();
        out.print("\r");
        for (int i = 0; i < SEGMENTS; i++) {
            char c = i < lastFilled ? FILLED_CHAR : EMPTY_CHAR;
            out.print(Theme.colorize(String.valueOf(c), redStyle));
        }
        out.print(GAP);
        out.print(Theme.colorize(lastPercent, Theme.settled()));
        out.print(SEPARATOR);
        out.print(Theme.colorize(lastStatus, strikeStyle));
        out.print("\033[K"); // wipe any residue past the (shorter) cancel status
        out.print(OSC_CLEAR);
        out.print(SHOW_CURSOR);
        out.flush();
    }

    private void renderInitial(int filled, String percent, String status) {
        out.print("\r");
        renderSegments(0, SEGMENTS, filled);
        out.print(GAP);
        out.print(Theme.colorize(percent, Theme.settled()));
        out.print(SEPARATOR);
        out.print(Theme.colorize(status, Theme.dim()));
    }

    private void renderDiff(int filled, String percent, String status) {
        // Segments: redraw only the range that actually changed.
        if (filled != lastFilled) {
            int from = Math.min(lastFilled, filled);
            int to = Math.max(lastFilled, filled);
            moveToCol(1 + from);
            renderSegments(from, to, filled);
        }
        // Percent: repaint the 4-char block (it's already fixed-width).
        if (!percent.equals(lastPercent)) {
            moveToCol(1 + SEGMENTS + GAP.length());
            out.print(Theme.colorize(percent, Theme.settled()));
        }
        // Status: repaint; overwrite shrinkage with the exact number of spaces.
        if (!status.equals(lastStatus)) {
            moveToCol(statusCol());
            out.print(Theme.colorize(status, Theme.dim()));
            int shrink = lastStatus.length() - status.length();
            if (shrink > 0) out.print(" ".repeat(shrink));
        }
    }

    private void renderSegments(int from, int to, int filled) {
        for (int i = from; i < to; i++) {
            boolean isFilled = i < filled;
            char c = isFilled ? FILLED_CHAR : EMPTY_CHAR;
            AttributedStyle style = isFilled ? segmentColors[i] : emptyStyle;
            out.print(Theme.colorize(String.valueOf(c), style));
        }
    }

    private static int statusCol() {
        // 1-based column where the status text begins.
        return 1 + SEGMENTS + GAP.length() + PERCENT_WIDTH + SEPARATOR.length();
    }

    private void moveToCol(int col) {
        out.print("\033[" + col + "G");
    }

    static AttributedStyle[] buildGradient(int n) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            int r = (int) Math.round(START_R + t * (END_R - START_R));
            int g = (int) Math.round(START_G + t * (END_G - START_G));
            int b = (int) Math.round(START_B + t * (END_B - START_B));
            a[i] = Theme.bright(r, g, b);
        }
        return a;
    }

}
