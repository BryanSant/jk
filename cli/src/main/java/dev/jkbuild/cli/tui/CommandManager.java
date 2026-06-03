// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The live console component for long-running commands. Two presentation modes:
 *
 * <ul>
 *   <li><b>Simple task</b> — an animated spinner + a human verb
 *       ({@code ✸ Locking…}); on completion the spinner freezes to its first
 *       glyph and a result line is printed below
 *       ({@code ✔ Finished syncing 13 artifacts} / {@code ✗ …}).</li>
 *   <li><b>Goal-oriented</b> — a spinner header
 *       ({@code ✸ Building › acme:api… (1m 52s)}), an aggregate
 *       {@link ProgressBar}, and a dynamic phase list whose completed rows sink
 *       to the bottom and collapse to {@code … +N completed}. On completion the
 *       whole region is replaced by a single {@code ✔}/{@code ✗} result line.</li>
 * </ul>
 *
 * <p>The {@code animate} flag decides whether anything is drawn live: on a TTY it
 * animates; under pipes / {@code --quiet} / {@code --no-progress} it stays put
 * and only the final result line is printed. Registers as the active
 * {@link LiveRegion} so a Ctrl-C repaints cleanly. The animator runs on a daemon
 * thread; all writes are guarded by one lock. The spinner frame set, interval,
 * and gradient are shared with {@link Spinner}.
 *
 * <p>Goal mode aggregates across callers: feed phases/progress for any number of
 * members into one instance and it renders a single bar + single list. Terminal
 * width is detected once at construction (a mid-run resize is tolerated — the
 * next repaint may be briefly imperfect).
 */
public final class CommandManager implements AutoCloseable, LiveRegion {

    private static final String[] FRAMES = Spinner.FRAMES;
    private static final long FRAME_MS = Spinner.FRAME_MS;
    private static final String ELLIPSIS = "…";
    private static final int DEFAULT_WIDTH = 80;
    /** Max phase rows shown before completed rows collapse into a "+N" line. */
    static final int MAX_ROWS = 8;

    private final PrintStream out;
    private final boolean animate;
    private final boolean goalMode;
    private final int width;
    private final AttributedStyle[] frameColors = Spinner.buildGradient(FRAMES.length);
    private final ProgressBar bar = new ProgressBar();

    private final Object lock = new Object();
    private volatile boolean stopped;   // animator should stop
    private boolean done;               // a terminal render already happened
    private int frame;
    private int linesDrawn;             // goal mode: lines in the live region
    private List<String> lastLines = List.of();  // goal mode: last painted lines, for diffing

    // simple mode
    private String label = "";

    // goal mode
    private String name = "";
    private String target = "";
    private long startNanos;
    private long numerator;
    private long denominator;
    private long finishSeq;
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private Thread animator;

    CommandManager(PrintStream out, boolean animate, boolean goalMode, int width) {
        this.out = out;
        this.animate = animate;
        this.goalMode = goalMode;
        this.width = width <= 0 ? DEFAULT_WIDTH : width;
    }

    /** Package-private convenience for simple-mode tests. */
    CommandManager(PrintStream out, boolean animate) {
        this(out, animate, false, DEFAULT_WIDTH);
    }

    // --- simple-task mode -------------------------------------------------

    /** Start simple-task mode: spinner (when {@code animate}) + {@code verb}. */
    public static CommandManager simple(PrintStream out, String verb, boolean animate) {
        CommandManager cm = new CommandManager(out, animate, false, DEFAULT_WIDTH);
        cm.label = verb;
        LiveRegion.setActive(cm);
        if (animate) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        }
        return cm;
    }

    /** Update the running verb/label (simple mode). */
    public void label(String verb) {
        synchronized (lock) {
            this.label = verb == null ? "" : verb;
        }
    }

    // --- goal-oriented mode -----------------------------------------------

    /**
     * Start goal-oriented mode. {@code name} is the verb shown in the header
     * (e.g. {@code "Building"}); set the active member with {@link #target}.
     */
    public static CommandManager goal(PrintStream out, String name, boolean animate) {
        CommandManager cm = new CommandManager(out, animate, true, animate ? detectWidth() : DEFAULT_WIDTH);
        cm.name = name;
        cm.startNanos = System.nanoTime();
        LiveRegion.setActive(cm);
        if (animate) {
            out.print(Ansi.HIDE_CURSOR);
            out.flush();
            cm.startAnimator();
        }
        return cm;
    }

    /** Header member, e.g. {@code "acme:api"}. */
    public void target(String member) {
        synchronized (lock) {
            this.target = member == null ? "" : member;
        }
    }

    /** Register a not-yet-started phase row with a humanized display name. */
    public void addPhase(String member, String phaseKey) {
        addPhaseLabeled(member, phaseKey, humanize(phaseKey));
    }

    /** Register a not-yet-started phase row with an explicit display label. */
    public void addPhaseLabeled(String member, String phaseKey, String display) {
        synchronized (lock) {
            rows.computeIfAbsent(key(member, phaseKey), k -> new Row(member, display));
        }
    }

    /** Mark a phase running and make it the header's active member. */
    public void phaseRunning(String member, String phaseKey) {
        synchronized (lock) {
            Row r = rows.computeIfAbsent(key(member, phaseKey),
                    k -> new Row(member, humanize(phaseKey)));
            r.state = RowState.ACTIVE;
            this.target = member;
        }
    }

    /** Set the current sub-task message for a running phase. */
    public void phaseMessage(String member, String phaseKey, String message) {
        synchronized (lock) {
            Row r = rows.get(key(member, phaseKey));
            if (r != null) r.message = message == null ? "" : message;
        }
    }

    /** Mark a phase finished (success or failure); it sinks to the completed group. */
    public void phaseDone(String member, String phaseKey, boolean ok) {
        synchronized (lock) {
            Row r = rows.computeIfAbsent(key(member, phaseKey),
                    k -> new Row(member, humanize(phaseKey)));
            r.state = ok ? RowState.DONE : RowState.FAILED;
            r.message = "";
            r.seq = ++finishSeq;
        }
    }

    /** Set the aggregate progress numerator/denominator for the bar. */
    public void progress(long numerator, long denominator) {
        synchronized (lock) {
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    // --- completion -------------------------------------------------------

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
            if (animate) {
                // Simple mode keeps the settled spinner line and prints the
                // result below it; goal mode replaces the whole region.
                if (goalMode) wipeRegion();
                else freezeSpinnerLine();
                out.print(Ansi.TASKBAR_CLEAR);
                out.print(Ansi.SHOW_CURSOR);
            }
            out.println(marker + " " + message);
            out.flush();
        }
    }

    @Override
    public void renderCanceled() {
        // Ctrl-C: wipe the live region; GlobalCancel prints "‼ Canceled by user".
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (!animate) return;
            if (goalMode) wipeRegion();
            else freezeSpinnerLine();
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    @Override
    public void close() {
        stopAnimator();
        synchronized (lock) {
            if (done) return;
            done = true;
            LiveRegion.clearActive(this);
            if (!animate) return;
            wipeRegion();
            out.print(Ansi.TASKBAR_CLEAR);
            out.print(Ansi.SHOW_CURSOR);
            out.flush();
        }
    }

    /** Simple mode: repaint the spinner line with its first (settled) glyph, then newline. */
    private void freezeSpinnerLine() {
        out.print('\r');
        out.print(Theme.colorize(FRAMES[0], frameColors[0]));
        out.print(' ');
        out.print(label);
        out.print(ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print('\n');
    }

    /** Erase the live region and park the cursor at its top-left. */
    private void wipeRegion() {
        if (goalMode) {
            if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
            out.print(Ansi.ERASE_DISPLAY_TO_END);
            linesDrawn = 0;
            lastLines = List.of();
        } else {
            out.print(Ansi.CLEAR_LINE);
        }
    }

    // --- rendering --------------------------------------------------------

    /** Render one frame in place and advance the spinner. Package-private for tests. */
    void tick() {
        synchronized (lock) {
            if (done || !animate) return;
            if (goalMode) {
                paintGoal();
            } else {
                out.print('\r');
                out.print(Theme.colorize(FRAMES[frame], frameColors[frame]));
                out.print(' ');
                out.print(label);
                out.print(ELLIPSIS);
                out.print(Ansi.ERASE_LINE_TO_END);
                out.print(Ansi.TASKBAR_INDETERMINATE);
            }
            out.flush();
            frame = (frame + 1) % FRAMES.length;
        }
    }

    /**
     * Repaint the multi-line goal region (must hold {@link #lock}), rewriting
     * only the lines that changed since the last paint to avoid flicker. The
     * spinner header changes every frame; the bar and phase rows only on real
     * updates, so a steady region mostly just rewrites its top line.
     *
     * <p>Cursor invariant: between paints the cursor is parked at the start of
     * the line immediately below the region. We move up to the first line, walk
     * down rewriting changed lines (and advancing past unchanged ones with a
     * bare newline), then clear any lines a now-shorter region left behind.
     */
    private void paintGoal() {
        List<String> lines = renderGoalLines(width, elapsedMillis());
        int prev = lastLines.size();
        if (prev > 0) out.print(Ansi.cursorUp(prev));   // to the top of the region
        for (int i = 0; i < lines.size(); i++) {
            boolean changed = i >= prev || !lines.get(i).equals(lastLines.get(i));
            if (changed) {
                out.print('\r');
                out.print(truncateVisible(lines.get(i), width));
                out.print(Ansi.ERASE_LINE_TO_END);  // wipe any tail from a longer prior line
            }
            out.print('\n');                          // advance to the next line / below region
        }
        // A shorter region than last time: erase the orphaned lines below.
        if (prev > lines.size()) out.print(Ansi.ERASE_DISPLAY_TO_END);
        out.print(Ansi.taskbarProgress(ProgressBar.percent(numerator, denominator)));
        lastLines = lines;
        linesDrawn = lines.size();
    }

    /**
     * Build the goal region's lines (header, bar, phase list). Pure — no cursor
     * control, no output. Package-private for tests; the {@code frame} field
     * and {@code elapsedMillis} are passed/read so tests are deterministic.
     */
    public List<String> renderGoalLines(int cols, long elapsedMillis) {
        AttributedStyle dim = Theme.active().darkGray();
        String sep = Theme.colorize("›", dim);
        List<String> lines = new ArrayList<>();

        // 1. Header: {spinner} {name} › {member}… (elapsed)
        StringBuilder header = new StringBuilder();
        header.append(Theme.colorize(FRAMES[frame], frameColors[frame])).append(' ')
                .append(Theme.colorize(name, Theme.active().settled()));
        if (!target.isEmpty()) {
            header.append(' ').append(sep).append(' ')
                    .append(Theme.colorize(target, Theme.active().settled()));
        }
        header.append(ELLIPSIS).append(' ')
                .append(Theme.colorize("(" + fmtElapsed(elapsedMillis) + ")", dim));
        lines.add(header.toString());

        // 2. Aggregate bar.
        lines.add(bar.render(numerator, denominator));

        // 3. Phase list: outstanding on top, completed sinking to the bottom.
        List<Row> outstanding = new ArrayList<>();
        List<Row> completed = new ArrayList<>();
        for (Row r : rows.values()) {
            (r.state == RowState.DONE || r.state == RowState.FAILED ? completed : outstanding).add(r);
        }
        outstanding.sort((a, b) -> Boolean.compare(b.state == RowState.ACTIVE, a.state == RowState.ACTIVE));
        completed.sort((a, b) -> Long.compare(a.seq, b.seq));

        int shownOutstanding = Math.min(outstanding.size(), MAX_ROWS - 1);
        int completedSlots = Math.max(0, MAX_ROWS - 1 - shownOutstanding);
        int shownCompleted = Math.min(completed.size(), completedSlots);
        // Newest completed are the most relevant; collapse the older overflow.
        int firstCompleted = completed.size() - shownCompleted;
        int hidden = (outstanding.size() - shownOutstanding) + firstCompleted;

        List<Row> visible = new ArrayList<>();
        for (int i = 0; i < shownOutstanding; i++) visible.add(outstanding.get(i));
        for (int i = firstCompleted; i < completed.size(); i++) visible.add(completed.get(i));

        for (int i = 0; i < visible.size(); i++) {
            String prefix = i == 0 ? Theme.colorize("╰─ ", dim) : "   ";
            lines.add(prefix + renderRow(visible.get(i), sep));
        }
        if (hidden > 0) {
            lines.add("   " + Theme.colorize("… +" + hidden + " completed", dim));
        }
        return lines;
    }

    private static String renderRow(Row r, String sep) {
        String glyph = switch (r.state) {
            case DONE -> Theme.colorize(Glyphs.CHECK, Theme.active().success());
            case FAILED -> Theme.colorize(Glyphs.CROSS, Theme.active().error());
            default -> Theme.colorize(Glyphs.PENDING, Theme.active().settled());
        };
        StringBuilder sb = new StringBuilder();
        sb.append(glyph).append(' ')
                .append(Theme.colorize(r.member, Theme.active().settled()))
                .append(' ').append(sep).append(' ')
                .append(Theme.colorize(r.phase, Theme.active().settled()));
        if (r.message != null && !r.message.isEmpty()) {
            sb.append(' ').append(sep).append(' ')
                    .append(Theme.colorize(r.message, Theme.active().darkGray()));
        }
        return sb.toString();
    }

    private long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // --- animation --------------------------------------------------------

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

    // --- helpers ----------------------------------------------------------

    private static String key(String member, String phaseKey) {
        return member + ' ' + phaseKey;
    }

    /** "compile-java" → "Compile java"; "runTests" → "RunTests" (best-effort). */
    static String humanize(String phaseKey) {
        if (phaseKey == null || phaseKey.isEmpty()) return "";
        String spaced = phaseKey.replace('-', ' ').replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** "(1m 52s)" content: minutes+seconds past a minute, else just seconds. */
    static String fmtElapsed(long millis) {
        long totalSec = Math.max(0, millis) / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /**
     * Truncate an ANSI-colored string to {@code maxCols} visible columns,
     * copying escape sequences without counting them and appending a reset if
     * the text was cut. Treats every visible codepoint as one column (good
     * enough for our ASCII + single-width glyphs).
     */
    static String truncateVisible(String s, int maxCols) {
        if (maxCols <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int visible = 0;
        boolean truncated = false;
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '\033') { // copy the whole CSI sequence verbatim
                int j = i + 1;
                if (j < s.length() && s.charAt(j) == '[') {
                    j++;
                    while (j < s.length() && !Character.isLetter(s.charAt(j))) j++;
                    if (j < s.length()) j++; // include the final letter
                }
                sb.append(s, i, j);
                i = j;
            } else {
                if (visible >= maxCols) { truncated = true; break; }
                sb.append(c);
                visible++;
                i++;
            }
        }
        if (truncated) sb.append(Ansi.RESET);
        return sb.toString();
    }

    /**
     * Terminal width, detected once, leak-free. We deliberately do NOT build a
     * JLine terminal here: JLine probes the terminal with capability queries
     * (DA1 {@code \e[c}, mode reports like {@code \e[?2027$p}), and a transient
     * build-then-close races the async replies — they arrive after we exit and
     * the shell echoes them as garbage. Instead ask the tty directly via
     * {@code stty size} (an ioctl, no escape sequences), then {@code $COLUMNS},
     * then a conservative default. Only called when animating (interactive tty).
     */
    private static int detectWidth() {
        try {
            Process p = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/tty")))
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
            p.waitFor();
            String[] parts = out.split("\\s+"); // "<rows> <cols>"
            if (parts.length == 2) {
                int cols = Integer.parseInt(parts[1]);
                if (cols > 0) return cols;
            }
        } catch (Exception ignored) {
            // no /dev/tty, no stty (e.g. Windows), or unparsable — fall through
        }
        try {
            String cols = System.getenv("COLUMNS");
            if (cols != null) {
                int v = Integer.parseInt(cols.trim());
                if (v > 0) return v;
            }
        } catch (NumberFormatException ignored) {
            // COLUMNS not a number — fall through
        }
        return DEFAULT_WIDTH;
    }

    private enum RowState { PENDING, ACTIVE, DONE, FAILED }

    private static final class Row {
        final String member;
        final String phase;
        RowState state = RowState.PENDING;
        String message = "";
        long seq;

        Row(String member, String phase) {
            this.member = member;
            this.phase = phase;
        }
    }
}
