// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import java.io.PrintStream;
import org.jline.utils.AttributedStyle;

/**
 * Animated JDK download progress bar styled like the {@code jk build} goal header: the blue goal
 * chip ({@code ✷ JDK ▶}), a block-bar fill, a white percent, and the download label — all on one
 * carriage-returned line.
 *
 * <p>On completion, the bar is wiped and replaced with a {@link GoalWedge} check-chip result line:
 * {@code ✓ JDK ▶ Finished downloading [bold]Eclipse Temurin 26[/]}.
 *
 * <p>Registers as the active {@link LiveRegion} so the global Ctrl-C handler can repaint it on
 * cancel. Thread-safe: the download callback may call {@link #update} from any thread.
 */
public final class JdkDownloadBar implements AutoCloseable, LiveRegion {

    private static final String[] FRAMES = Spinner.FRAMES;

    private final PrintStream out;
    private final String displayName; // "Eclipse Temurin 26"
    private final boolean nerdfont;
    private final boolean silent;
    private final AttributedStyle[] frameColors = Spinner.buildGradient(FRAMES.length);
    private final AttributedStyle[] failColors;
    private final ProgressBar bar = new ProgressBar();

    private int frame;
    private long numerator;
    private long denominator;
    private boolean drawn;
    private boolean closed;
    private boolean installing;
    private Thread animator;

    private JdkDownloadBar(PrintStream out, String displayName, boolean nerdfont, boolean silent) {
        this.out = out;
        this.displayName = displayName;
        this.nerdfont = nerdfont;
        this.silent = silent;
        this.failColors = SpinnerProgressBar.buildGradient(
                ProgressBar.SEGMENTS, Theme.active().failureGradient());
    }

    /**
     * Start a new download bar for {@code displayName} (e.g. "Eclipse Temurin 26"). If {@code
     * --no-progress} is set, returns a silent no-op instance.
     */
    public static JdkDownloadBar show(PrintStream out, String displayName) {
        boolean silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdfont, silent);
        LiveRegion.setActive(db);
        if (!silent) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            db.startAnimator();
        }
        return db;
    }

    /** Report download progress; safe to call from any thread. */
    public synchronized void update(long bytesDownloaded, long total) {
        if (closed || silent) return;
        this.numerator = bytesDownloaded;
        this.denominator = total;
        if (total > 0) {
            out.print(Ansi.taskbarProgress((int) Math.min(100, bytesDownloaded * 100L / total)));
        }
    }

    /**
     * Start an "Installing…" chip spinner using the same chip-with-frame pattern as the download bar
     * — the spinner frame cycles inside the {@code ✸ JDK ▶} chip. Call {@link #close()} on the
     * returned handle when installation completes.
     */
    public static JdkDownloadBar showInstalling(PrintStream out, String displayName) {
        boolean silent = dev.jkbuild.config.ActiveConfig.get().noProgressOr(false);
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        JdkDownloadBar db = new JdkDownloadBar(out, displayName, nerdfont, silent);
        db.installing = true;
        LiveRegion.setActive(db);
        if (!silent) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            db.startAnimator();
        }
        return db;
    }

    /**
     * Wipe the bar line and restore the cursor. The caller is responsible for printing the final
     * result line (e.g. "available at …"), which naturally takes the place of the cleared bar on
     * screen.
     */
    public synchronized void finish() {
        if (closed) return;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (silent) return;
        if (drawn) out.print(Ansi.CLEAR_LINE);
        out.print(Ansi.TASKBAR_CLEAR);
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
    }

    @Override
    public void close() {
        finish();
    }

    @Override
    public boolean renderCanceled() {
        if (closed) return false;
        closed = true;
        stopAnimator();
        LiveRegion.clearActive(this);
        if (silent) return false;
        // Repaint every segment in failure red.
        out.print("\r");
        for (int i = 0; i < ProgressBar.SEGMENTS; i++) {
            out.print(Theme.colorize(String.valueOf(ProgressBar.FILLED_CHAR), failColors[i]));
        }
        out.print(Ansi.TASKBAR_CLEAR);
        out.print(Ansi.SHOW_CURSOR);
        out.flush();
        return true;
    }

    // ── animation ──────────────────────────────────────────────────────────

    private void startAnimator() {
        animator = new Thread(
                () -> {
                    while (!closed) {
                        try {
                            Thread.sleep(Spinner.FRAME_MS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        synchronized (this) {
                            if (!closed) {
                                repaint();
                                frame = (frame + 1) % FRAMES.length;
                            }
                        }
                    }
                },
                "jk-jdk-download");
        animator.setDaemon(true);
        animator.start();
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.interrupt();
            animator = null;
        }
    }

    private void repaint() {
        out.print("\r");
        out.print(buildLine());
        out.print(Ansi.ERASE_LINE_TO_END);
        out.flush();
        drawn = true;
    }

    private String buildLine() {
        Theme t = Theme.active();
        String verb = installing ? "Installing " : "Downloading ";
        String label = Theme.colorize(verb + displayName, t.normalGray());

        if (nerdfont) {
            var chip = t.goalChip();
            String chipStr = Theme.colorize(" ", chip)
                    + Theme.colorize(FRAMES[frame], chip)
                    + Theme.colorize(" JDK ", chip)
                    + Theme.colorize(Glyphs.SEGMENT_END_NERD, t.bright(t.planBadgeColor()));
            if (installing) {
                return chipStr + " " + label;
            }
            Rgb lead = bar.leadColor(numerator, denominator);
            var cap = t.withBackground(t.bright(t.planBadgeColor()), lead);
            return Theme.colorize(" ", chip)
                    + Theme.colorize(FRAMES[frame], chip)
                    + Theme.colorize(" ", chip)
                    + Theme.colorize("JDK", chip)
                    + Theme.colorize(" ", chip)
                    + Theme.colorize(Glyphs.SEGMENT_END_NERD, cap)
                    + bar.render(numerator, denominator)
                    + " "
                    + Theme.colorize("·", t.darkGray())
                    + " "
                    + label;
        } else {
            if (installing) {
                return Theme.colorize(FRAMES[frame], frameColors[frame]) + " JDK  " + label;
            }
            return Theme.colorize(FRAMES[frame], frameColors[frame])
                    + " JDK "
                    + bar.render(numerator, denominator)
                    + " "
                    + Theme.colorize("·", t.darkGray())
                    + " "
                    + label;
        }
    }
}
