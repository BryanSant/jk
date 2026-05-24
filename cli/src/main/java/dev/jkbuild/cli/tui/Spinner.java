// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedStyle;

import java.io.PrintStream;

/**
 * Single-line animated spinner widget for indeterminate-progress CLI
 * operations. Cycles through {@code · ✢ ✳ ✶ ✻ ✽} on a daemon thread,
 * one frame every {@value #FRAME_MS} ms. Each frame is rendered in its
 * own color along a blue → magenta gradient (the reverse of the
 * {@code jk init} title gradient).
 *
 * <p>Layout: {@code <frame> <message>} on the current line.
 *
 * <p>The cursor is hidden between {@link #show(PrintStream, String)} and
 * {@link #close()}. {@link #update(String)} mutates the message
 * displayed on the next tick; shrinking the message overwrites the
 * previous trailing chars with spaces (no further). Both {@code update}
 * and {@code close} are safe to call from any thread.
 */
public final class Spinner implements AutoCloseable {

    /** Animation frames, cycled in order. */
    static final String[] FRAMES = {"·", "✢", "✳", "✶", "✻", "✽"};

    /** Interval between frames. */
    static final long FRAME_MS = 120L;

    // Reverse of the init title gradient: blue #3b82f6 → magenta #d946ef.
    private static final int START_R = 0x3b, START_G = 0x82, START_B = 0xf6;
    private static final int END_R = 0xd9, END_G = 0x46, END_B = 0xef;

    private static final String HIDE_CURSOR = "\033[?25l";
    private static final String SHOW_CURSOR = "\033[?25h";
    private static final String CLEAR_LINE = "\r\033[K";

    // OSC 9;4 — taskbar/tab status indicator (ConEmu, Windows Terminal,
    // WezTerm, ghostty, kitty ≥0.31, etc.). State 3 = indeterminate; the
    // host can show a pulsing/marquee animation while we spin. State 0
    // clears the indicator. Unsupported terminals swallow the sequence.
    static final String OSC_INDETERMINATE = "\033]9;4;3\007";
    static final String OSC_CLEAR = "\033]9;4;0\007";

    private final PrintStream out;
    private final AttributedStyle[] frameColors;
    private final Object lock = new Object();

    private volatile String message;
    private int frame = 0;
    private String lastMessage = "";
    private volatile boolean closed = false;
    private Thread animator;

    /** Start a new spinner on the caller's current line, hiding the cursor. */
    public static Spinner show(PrintStream out, String message) {
        Spinner s = new Spinner(out, message);
        s.start();
        return s;
    }

    Spinner(PrintStream out, String message) {
        this.out = out;
        this.message = message == null ? "" : message;
        this.frameColors = buildGradient(FRAMES.length);
    }

    private void start() {
        out.print(HIDE_CURSOR);
        out.print(OSC_INDETERMINATE);
        out.flush();
        animator = new Thread(this::loop, "jk-spinner");
        animator.setDaemon(true);
        animator.start();
    }

    private void loop() {
        while (!closed) {
            step();
            try {
                Thread.sleep(FRAME_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Update the message shown next to the spinner. Picked up on the next tick. */
    public void update(String message) {
        this.message = message == null ? "" : message;
    }

    /** Render the current frame and advance. Package-private for testing. */
    void step() {
        synchronized (lock) {
            if (closed) return;
            String currentMsg = message;
            // Re-assert the indeterminate state on every tick — some hosts
            // (and tab-switch / focus events) drop the indicator otherwise.
            // Unsupported terminals swallow the OSC silently.
            out.print(OSC_INDETERMINATE);
            out.print("\r");
            out.print(Theme.colorize(FRAMES[frame], frameColors[frame]));
            out.print(" ");
            out.print(currentMsg);
            int shrink = lastMessage.length() - currentMsg.length();
            if (shrink > 0) out.print(" ".repeat(shrink));
            out.flush();
            lastMessage = currentMsg;
            frame = (frame + 1) % FRAMES.length;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (animator != null) animator.interrupt();
        synchronized (lock) {
            // Clear the spinner line so it doesn't linger in the transcript.
            out.print(CLEAR_LINE);
            out.print(OSC_CLEAR);
            out.print(SHOW_CURSOR);
            out.flush();
        }
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
