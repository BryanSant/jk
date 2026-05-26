// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedStyle;

import java.io.PrintStream;

/**
 * "Pinned to the bottom" status line for {@code jk test}. Renders one
 * single-line widget that combines an animated spinner (gradient frames),
 * a 20-segment progress bar, and an {@code [N of M]} counter with elapsed
 * wall time.
 *
 * <p>The defining feature is {@link #writeAbove(String)}: callers route
 * any output that would normally land on stdout (failure summaries, user
 * test prints, FAIL lines) through this method, which clears the pinned
 * line, prints the new line with a trailing newline, then redraws the
 * progress on the row below. The visual effect is that progress sits at
 * the bottom while other output scrolls past above it.
 *
 * <p>State transitions:
 * <pre>
 *   start()  →  "{spin} Testing: Initializing..."
 *   setTotal(N) →  "{spin} Testing {bar} 0% [0 of N] 0s"
 *   incrementCompleted() (×K)  →  "{spin} Testing {bar} 24% [K of N] Xs"
 *   finishSuccess() / finishFailure(F)  →  replaces the line with the final summary
 * </pre>
 *
 * <p>Silent on non-TTYs and under {@code --no-progress}: no spinner thread,
 * no escapes; {@code writeAbove} degrades to a plain {@code println},
 * {@code finish*} emit one final summary line.
 */
public final class TestProgress implements AutoCloseable {

    /** Animation frame interval in ms. Matches {@link Spinner} for visual consistency. */
    static final long FRAME_MS = 120L;

    /** Bar width — tighter than ProgressBar's 40 so the whole status fits in ~100 columns. */
    static final int BAR_SEGMENTS = 20;

    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    /** ANSI: erase current line + park cursor at column 1. */
    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";

    private final PrintStream out;
    private final Object lock = new Object();
    private final AttributedStyle[] spinnerColors;
    private final AttributedStyle[] barColors;
    private final boolean silent;

    /** Mutable state — all writes guarded by {@link #lock}. */
    private long startedAtNanos;  // set when the bar first appears (not at construction)
    private long total = -1;      // -1 = no bar yet; widget is invisible
    private long completed = 0;
    private int frame = 0;
    private boolean finished = false;
    private Thread animator;

    /**
     * Construct a progress widget. The widget is initially invisible — the
     * spinner doesn't start, no line is painted, and {@link #writeAbove}
     * just passes text through to stdout. {@link #setTotal} is what brings
     * the widget on screen (and starts the elapsed-time clock); that's
     * called after the discovery phase reports the test count, so there's
     * no "Initializing…" placeholder during the brief enumerate window.
     */
    public static TestProgress start(PrintStream out) {
        return new TestProgress(out);
    }

    private TestProgress(PrintStream out) {
        this.out = out;
        this.spinnerColors = Spinner.buildGradient(Spinner.FRAMES.length);
        this.barColors = ProgressBar.buildGradient(BAR_SEGMENTS);
        this.silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
    }

    /**
     * Switch on the progress display. Idempotent — repeated calls update
     * the denominator (e.g. dynamic tests registered mid-run). The first
     * call also kicks off the spinner-animation thread and starts the
     * elapsed-time clock.
     */
    public void setTotal(long total) {
        synchronized (lock) {
            if (finished || total < 0) return;
            boolean firstShow = this.total < 0;
            this.total = total;
            if (firstShow) {
                this.startedAtNanos = System.nanoTime();
                if (!silent) {
                    out.print(HIDE_CURSOR);
                    redraw();
                    startAnimatorLocked();
                }
            } else {
                redraw();
            }
        }
    }

    private void startAnimatorLocked() {
        animator = new Thread(() -> {
            while (!finished) {
                try {
                    Thread.sleep(FRAME_MS);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (lock) {
                    if (finished) return;
                    frame = (frame + 1) % Spinner.FRAMES.length;
                    redraw();
                }
            }
        }, "jk-test-progress");
        animator.setDaemon(true);
        animator.start();
    }

    /** Bump the completed counter and repaint. */
    public void incrementCompleted() {
        synchronized (lock) {
            if (finished) return;
            completed++;
            redraw();
        }
    }

    /**
     * Write {@code line} above the pinned status. Clears the status row,
     * prints {@code line + \n}, then redraws the status on the row below.
     * Before the widget has shown its first frame (i.e. before
     * {@link #setTotal} has been called) there's no pinned row to clear —
     * the line just passes through to stdout. Safe to call from any thread.
     */
    public void writeAbove(String line) {
        synchronized (lock) {
            if (silent || finished || total < 0) {
                out.println(line);
                return;
            }
            out.print(CLEAR_LINE);
            out.println(line);
            redraw();
        }
    }

    /** Replace the status line with "✓ All tests successful - {elapsed}". */
    public void finishSuccess() {
        finishWith(Theme.colorize("✓", Theme.brightGreen().bold())
                + " All tests successful - " + elapsedHuman());
    }

    /** Replace the status line with "✗ {failed} test(s) failed - {elapsed}". */
    public void finishFailure(long failed) {
        finishWith(Theme.colorize("✗", Theme.error())
                + " " + failed + (failed == 1 ? " test failed - " : " tests failed - ")
                + elapsedHuman());
    }

    /** Replace the status line with a custom message (used for "no tests"). */
    public void finishWithMessage(String message) {
        finishWith(message);
    }

    @Override
    public void close() {
        // Defensive — finishSuccess/finishFailure should be called explicitly.
        // close() catches the case where they weren't (exception path).
        synchronized (lock) {
            if (finished) return;
            finished = true;
            if (animator != null) animator.interrupt();
            if (!silent) {
                out.print(CLEAR_LINE);
                out.print(SHOW_CURSOR);
                out.flush();
            }
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void finishWith(String summary) {
        synchronized (lock) {
            if (finished) return;
            finished = true;
            if (animator != null) animator.interrupt();
            if (silent || total < 0) {
                // Widget never appeared (silent, or finished before setTotal
                // — empty suite, error before discovery). No pinned line to
                // clear and the cursor was never hidden.
                out.println(summary);
                out.flush();
                return;
            }
            out.print(CLEAR_LINE);
            out.println(summary);
            out.print(SHOW_CURSOR);
            out.flush();
        }
    }

    private void redraw() {
        // total < 0 means the widget hasn't been activated yet — stay silent
        // (no "Initializing..." placeholder; discovery is fast enough that
        // the empty terminal during enumeration is the right UX).
        if (silent || finished || total < 0) return;
        out.print(CLEAR_LINE);
        out.print(Theme.colorize(Spinner.FRAMES[frame], spinnerColors[frame]));
        out.print(" ");
        out.print(Theme.colorize("Testing", Theme.focused()));
        int percent = total == 0 ? 100 : (int) Math.min(100, completed * 100 / total);
        int filled = (int) Math.round(percent * BAR_SEGMENTS / 100.0);
        out.print(" ");
        renderBar(filled);
        out.print(" ");
        out.print(Theme.colorize(String.format("%3d%%", percent), Theme.settled()));
        out.print(" ");
        out.print(Theme.colorize("[" + completed + " of " + total + "]", Theme.dim()));
        out.print(" ");
        out.print(Theme.colorize(elapsedHuman(), Theme.darkGray()));
        out.flush();
    }

    private void renderBar(int filled) {
        for (int i = 0; i < BAR_SEGMENTS; i++) {
            boolean on = i < filled;
            char c = on ? FILLED_CHAR : EMPTY_CHAR;
            AttributedStyle style = on ? barColors[i] : Theme.dim();
            out.print(Theme.colorize(String.valueOf(c), style));
        }
    }

    private String elapsedHuman() {
        // startedAtNanos == 0 means the widget never activated; treat as 0s.
        long secs = startedAtNanos == 0 ? 0 : (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
        if (secs < 60) return secs + "s";
        long m = secs / 60;
        long s = secs % 60;
        return m + "m " + s + "s";
    }
}
