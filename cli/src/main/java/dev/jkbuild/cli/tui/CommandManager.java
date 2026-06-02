// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;

/**
 * The live console component for long-running commands. Two presentation modes:
 *
 * <ul>
 *   <li><b>Simple task</b> — an animated spinner + a human verb
 *       ({@code ✸ Locking…}); on completion the spinner freezes to its first
 *       glyph and a result line is printed below
 *       ({@code ✔ Finished syncing 13 artifacts} / {@code ✗ …}).</li>
 *   <li><b>Goal-oriented</b> — spinner header + aggregate {@link ProgressBar}
 *       + a dynamic phase list. (Added in a later step.)</li>
 * </ul>
 *
 * <p>Registers as the active {@link LiveRegion} so a Ctrl-C repaints it cleanly.
 * The spinner animation runs on a daemon thread; all terminal writes are guarded
 * by a single lock so concurrent progress events and the animator never
 * interleave mid-escape. The frame set, interval, and gradient are shared with
 * {@link Spinner}.
 */
public final class CommandManager implements AutoCloseable, LiveRegion {

    private static final String[] FRAMES = Spinner.FRAMES;
    private static final long FRAME_MS = Spinner.FRAME_MS;
    private static final String ELLIPSIS = "…";

    private final PrintStream out;
    private final boolean silent;
    private final AttributedStyle[] frameColors = Spinner.buildGradient(FRAMES.length);

    private final Object lock = new Object();
    private volatile boolean stopped;   // animator should stop
    private boolean done;               // a terminal render already happened
    private int frame;

    /** Simple-mode label, e.g. "Locking". Guarded by {@link #lock}. */
    private String label = "";

    private Thread animator;

    /** Package-private: tests construct directly and drive {@link #tick()} by hand. */
    CommandManager(PrintStream out, boolean silent) {
        this.out = out;
        this.silent = silent;
    }

    // --- simple-task mode -------------------------------------------------

    /**
     * Start simple-task mode: an animated spinner followed by {@code verb}
     * (e.g. {@code "Locking"}). Finish with {@link #finishSuccess}/
     * {@link #finishFailure}; use try-with-resources for the abort case.
     */
    public static CommandManager simple(PrintStream out, String verb) {
        boolean silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        CommandManager cm = new CommandManager(out, silent);
        cm.label = verb;
        LiveRegion.setActive(cm);
        if (!silent) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        }
        return cm;
    }

    /** Update the running verb/label (picked up on the next frame). */
    public void label(String verb) {
        synchronized (lock) {
            this.label = verb == null ? "" : verb;
        }
    }

    /** Settle with a green check and a success message. */
    public void finishSuccess(String message) {
        finish(Theme.colorize(Glyphs.CHECK, Theme.active().success()), message);
    }

    /** Settle with a red cross and a failure message. */
    public void finishFailure(String message) {
        finish(Theme.colorize(Glyphs.CROSS, Theme.active().error()), message);
    }

    private void finish(String marker, String message) {
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (!silent) {
                freezeSpinnerLine();   // leaves cursor at the start of a fresh line
                out.print(Ansi.TASKBAR_CLEAR);
                out.print(Ansi.SHOW_CURSOR);
            }
            out.println(marker + " " + message);
            out.flush();
        }
    }

    @Override
    public void renderCanceled() {
        // Ctrl-C: settle the spinner to its first glyph on its own line; the
        // caller (GlobalCancel) prints "‼ Canceled by user" on the line below.
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (silent) return;
            freezeSpinnerLine();
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    @Override
    public void close() {
        // No explicit finish ran (early return / exception): wipe the spinner
        // line and restore the cursor without leaving a dangling partial frame.
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (silent) return;
            out.print(Ansi.CLEAR_LINE);
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    /** Repaint the spinner line with its first (settled) glyph, then newline. */
    private void freezeSpinnerLine() {
        out.print('\r');
        out.print(Theme.colorize(FRAMES[0], frameColors[0]));
        out.print(' ');
        out.print(label);
        out.print(ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print('\n');
    }

    // --- animation --------------------------------------------------------

    /** Render one spinner frame in place and advance. Package-private for tests. */
    void tick() {
        synchronized (lock) {
            if (done || silent) return;
            out.print('\r');
            out.print(Theme.colorize(FRAMES[frame], frameColors[frame]));
            out.print(' ');
            out.print(label);
            out.print(ELLIPSIS);
            out.print(Ansi.ERASE_LINE_TO_END);
            out.print(Ansi.TASKBAR_INDETERMINATE);
            out.flush();
            frame = (frame + 1) % FRAMES.length;
        }
    }

    private void startAnimator() {
        animator = new Thread(this::loop, "jk-command-manager");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        try {
            while (!stopped) {
                tick();
                Thread.sleep(FRAME_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopAnimator() {
        stopped = true;
        Thread a;
        synchronized (lock) {
            a = animator;
            animator = null;
        }
        if (a != null) {
            a.interrupt();
            try {
                a.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
