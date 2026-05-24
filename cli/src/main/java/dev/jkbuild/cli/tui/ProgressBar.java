// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedStyle;

import java.io.PrintStream;

/**
 * Single-line progress bar widget for long-running CLI operations
 * (downloading JDKs, installing JDKs, running builds).
 *
 * <p>Layout: {@code ▰▰▰▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱  62%: <status>}
 *
 * <ul>
 *   <li>20 segments. Filled segments use a per-position gradient from
 *       dark green {@code #166534} to bright green {@code #4ade80};
 *       empty segments are faint.</li>
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

    static final int SEGMENTS = 20;
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    /** Right-aligned percent column: "  5%", " 62%", "100%". */
    static final int PERCENT_WIDTH = 4;
    static final String GAP = "  ";
    static final String SEPARATOR = ": ";

    // Gradient: dark green #166534 → bright green #4ade80.
    private static final int START_R = 0x16, START_G = 0x65, START_B = 0x34;
    private static final int END_R = 0x4a, END_G = 0xde, END_B = 0x80;

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

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
        out.print(SHOW_CURSOR);
        out.println();
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
