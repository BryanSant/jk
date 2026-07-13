// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedStyle;
import org.jline.utils.NonBlockingReader;

/**
 * The live TUI for {@code jk engine stop}'s graceful drain: a two-line region redrawn in place —
 *
 * <pre>
 *   {spinner} Engine  Draining N job(s)… +14s
 *   Wait for jobs to finish, or press Ctrl-X to kill the engine now
 * </pre>
 *
 * — where the job count counts down (via {@link #setJobs}), the {@code +14s} is a yellow count-up,
 * and pressing <b>Ctrl-X</b> flips {@link #forceRequested()} so the command can force an immediate
 * shutdown. When the drain finishes, {@link #settleStopped} wipes the region and prints the settled
 * {@code ■ Engine  Engine stopped…} wedge.
 *
 * <p>Owns a raw JLine terminal in cbreak mode (ICANON/ECHO off, but ISIG left on so Ctrl-C still
 * reaches {@link GlobalCancel}). The animator daemon is the only writer; the key daemon only reads.
 * A silent no-op instance is returned when the terminal isn't interactive / {@code --no-progress}.
 */
public final class DrainView implements LiveRegion, AutoCloseable {

    private final Terminal terminal; // null → inactive no-op
    private final Attributes saved;
    private final NonBlockingReader reader;
    private final PrintWriter out;
    private final boolean nerdfont;
    private final long startNanos;

    private final Object lock = new Object();
    private final AtomicBoolean settled = new AtomicBoolean();
    private volatile int jobs;
    private volatile boolean forceRequested;
    private volatile boolean closed;
    private int linesDrawn;
    private int frame;
    private Thread animator;
    private Thread keys;
    private Thread restoreHook;

    private DrainView(Terminal terminal, Attributes saved, int jobs, boolean nerdfont, long startNanos) {
        this.terminal = terminal;
        this.saved = saved;
        this.reader = terminal == null ? null : terminal.reader();
        this.out = terminal == null ? null : terminal.writer();
        this.jobs = jobs;
        this.nerdfont = nerdfont;
        this.startNanos = startNanos;
    }

    /** Start the live drain region, or a silent no-op instance when non-interactive / no-progress. */
    public static DrainView start(int initialJobs, boolean nerdfont) {
        long now = System.nanoTime();
        if (!interactive()) return new DrainView(null, null, initialJobs, nerdfont, now);
        try {
            Terminal t = Wizard.openTerminal();
            Attributes saved = t.getAttributes();
            Attributes raw = new Attributes(saved);
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false); // byte-at-a-time
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false); // don't echo the keypress
            // ISIG stays on: Ctrl-C keeps raising SIGINT so GlobalCancel settles this region.
            t.setAttributes(raw);
            Wizard.drainInput(t.reader(), 40); // flush terminal probe replies
            DrainView v = new DrainView(t, saved, initialJobs, nerdfont, now);
            v.out.print(Ansi.HIDE_CURSOR);
            v.out.flush();
            LiveRegion.setActive(v);
            v.restoreHook = new Thread(v::restoreTerminalQuietly, "jk-drain-restore");
            Runtime.getRuntime().addShutdownHook(v.restoreHook);
            v.animator = new Thread(v::animate, "jk-drain-anim");
            v.animator.setDaemon(true);
            v.animator.start();
            v.keys = new Thread(v::readKeys, "jk-drain-keys");
            v.keys.setDaemon(true);
            v.keys.start();
            return v;
        } catch (Exception e) {
            return new DrainView(null, null, initialJobs, nerdfont, now); // degrade to no-op
        }
    }

    public boolean active() {
        return terminal != null;
    }

    public void setJobs(int n) {
        this.jobs = n;
    }

    public boolean forceRequested() {
        return forceRequested;
    }

    // --- rendering -----------------------------------------------------------

    private void animate() {
        while (!closed) {
            synchronized (lock) {
                if (closed) break;
                paint();
                frame = (frame + 1) % Spinner.FRAMES.length;
            }
            try {
                Thread.sleep(Spinner.FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void paint() {
        StringBuilder sb = new StringBuilder();
        if (linesDrawn > 0) sb.append(Ansi.cursorUp(linesDrawn));
        for (String line : lines()) {
            sb.append('\r').append(line).append(Ansi.ERASE_LINE_TO_END).append('\n');
        }
        out.print(sb);
        out.flush();
        linesDrawn = 2;
    }

    private String[] lines() {
        int n = jobs;
        String elapsed = Theme.colorize(
                "+" + fmtElapsed((System.nanoTime() - startNanos) / 1_000_000), Theme.active().warning());
        String l1 = GoalWedge.chipLine(
                Spinner.FRAMES[frame],
                "Engine",
                nerdfont,
                "Draining " + n + " job" + (n == 1 ? "" : "s") + "… " + elapsed);
        String hint = Theme.colorize("Wait for jobs to finish, or press ", Theme.active().dim())
                + Theme.colorize("Ctrl-X", AttributedStyle.DEFAULT.bold())
                + Theme.colorize(" to kill the engine now", Theme.active().dim());
        return new String[] {l1, hint};
    }

    private void readKeys() {
        while (!closed) {
            KeyReader.Key key;
            try {
                key = KeyReader.readOrNull(reader, 100);
            } catch (RuntimeException e) {
                return; // reader closed/failed — stop polling
            }
            if (key instanceof KeyReader.Key.CtrlX) {
                forceRequested = true;
                return;
            }
        }
    }

    // --- settle / teardown ---------------------------------------------------

    /** Wipe the live region and print the settled stop wedge line. Idempotent. */
    public void settleStopped(String wedgeLine) {
        if (terminal == null) return;
        if (!settled.compareAndSet(false, true)) return;
        stopAnimator();
        synchronized (lock) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            out.print('\r');
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            out.print(wedgeLine);
            out.print(System.lineSeparator());
            out.flush();
            linesDrawn = 0;
        }
    }

    @Override
    public void close() {
        if (terminal == null) return;
        stopAnimator();
        if (keys != null) keys.interrupt();
        synchronized (lock) {
            if (!settled.get()) {
                // No settle line was printed — just wipe the live region.
                if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
                out.print('\r');
                out.print(Ansi.ERASE_DISPLAY_TO_END);
                out.flush();
                linesDrawn = 0;
            }
        }
        restoreTerminalQuietly();
        LiveRegion.clearActive(this);
        removeHook();
    }

    @Override
    public boolean renderCanceled() {
        // Ctrl-C during the wait: the engine keeps draining in the background (correct) — leave a
        // clean note. GlobalCancel halts right after, bypassing shutdown hooks, so restore here.
        if (terminal == null) return true;
        stopAnimator();
        synchronized (lock) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            out.print('\r');
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            out.print("jk engine: still draining in the background");
            out.print(System.lineSeparator());
            out.flush();
            linesDrawn = 0;
        }
        restoreTerminalQuietly();
        return true;
    }

    private void stopAnimator() {
        closed = true;
        if (animator != null) animator.interrupt();
        try {
            if (animator != null) animator.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final AtomicBoolean restored = new AtomicBoolean();

    private void restoreTerminalQuietly() {
        if (terminal == null || !restored.compareAndSet(false, true)) return;
        try {
            out.print(Ansi.SHOW_CURSOR);
            out.print(Ansi.RESET);
            out.flush();
            Wizard.restoreCooked(terminal, saved);
            terminal.close();
        } catch (Exception ignored) {
            // best-effort restore
        }
    }

    private void removeHook() {
        if (restoreHook == null) return;
        try {
            Runtime.getRuntime().removeShutdownHook(restoreHook);
        } catch (IllegalStateException ignored) {
            // already shutting down
        }
    }

    // --- helpers -------------------------------------------------------------

    private static boolean interactive() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null
                && !dev.jkbuild.config.SessionContext.current().config().noProgressOr(false);
    }

    /** {@code 14s} / {@code 1m 02s} / {@code 1h 05m 09s}. */
    private static String fmtElapsed(long millis) {
        long s = millis / 1000;
        if (s < 60) return s + "s";
        long m = s / 60, sec = s % 60;
        if (m < 60) return m + "m " + String.format("%02d", sec) + "s";
        long h = m / 60;
        m %= 60;
        return h + "h " + String.format("%02d", m) + "m " + String.format("%02d", sec) + "s";
    }
}
