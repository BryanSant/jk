// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import java.io.PrintStream;

/**
 * An animated GoalWedge chip for an indeterminate step: a blue {@code {spinner} {verb}  {message}}
 * chip that spins in place, then settles into a green {@code ✓ {verb}  {tail}} chip on success (or
 * simply clears if it wasn't marked succeeded). Unlike {@link Spinner} (a plain {@code {frame}
 * message} line), this is a full powerline chip, so a client-side step reads the same as a goal.
 *
 * <p>Silent — emits nothing — when the terminal isn't ANSI or {@code --no-progress} is set; callers
 * check {@link #active()} to decide whether to print a plain fallback line instead.
 */
public final class ChipSpinner implements AutoCloseable {

    private final PrintStream out; // null when silent
    private final String verb;
    private final boolean nerdfont;
    private final String message;
    private final Object lock = new Object();
    private volatile boolean closed;
    private volatile String successTail;
    private int frame;
    private Thread animator;

    private ChipSpinner(PrintStream out, String verb, boolean nerdfont, String message) {
        this.out = out;
        this.verb = verb;
        this.nerdfont = nerdfont;
        this.message = message;
    }

    /** Start the animated chip. Returns a silent, no-op instance when non-interactive/no-progress. */
    public static ChipSpinner show(PrintStream out, String verb, boolean nerdfont, String message) {
        boolean silent = out == null
                || !Theme.active().isAnsi()
                || dev.jkbuild.config.SessionContext.current().config().noProgressOr(false);
        ChipSpinner s = new ChipSpinner(silent ? null : out, verb, nerdfont, message);
        if (!silent) s.start();
        return s;
    }

    /** True when this spinner is actually rendering (interactive) — false for the silent no-op. */
    public boolean active() {
        return out != null;
    }

    /** Mark success; {@link #close()} then settles into the green {@code ✓} chip with {@code tail}. */
    public void succeed(String tail) {
        this.successTail = tail;
    }

    private void start() {
        out.print(Ansi.HIDE_CURSOR);
        out.flush();
        animator = new Thread(this::loop, "jk-chip-spinner");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        while (!closed) {
            synchronized (lock) {
                if (closed) break;
                out.print("\r");
                out.print(Ansi.ERASE_LINE_TO_END);
                out.print(GoalWedge.chipLine(Spinner.FRAMES[frame], verb, nerdfont, message));
                out.flush();
                frame = (frame + 1) % Spinner.FRAMES.length;
            }
            try {
                Thread.sleep(Spinner.FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void close() {
        if (out == null) return;
        closed = true;
        if (animator != null) animator.interrupt();
        synchronized (lock) {
            out.print("\r");
            out.print(Ansi.ERASE_LINE_TO_END);
            if (successTail != null) {
                out.print(GoalWedge.chipLine(Glyphs.CHECK, verb, nerdfont, successTail));
                out.print(System.lineSeparator());
            }
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }
}
