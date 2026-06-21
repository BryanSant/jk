// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.GlobalConfig;
import org.jline.utils.AttributedStyle;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
    /** Flush a captured partial line (no newline yet) after this much quiet. */
    private static final long STALE_FLUSH_MS = 360;
    private static final String ELLIPSIS = "…";
    private static final int DEFAULT_WIDTH = 80;
    private static final int DEFAULT_HEIGHT = 24;
    /** Max phase rows shown before completed rows collapse into a "+N" line. */
    static final int MAX_ROWS = 8;
    /** Max completed lines shown below the active tree before a "… plus N more …" footer. */
    static final int MAX_COMPLETIONS = 5;

    private final PrintStream out;
    private final boolean animate;
    private final boolean goalMode;
    private final int width;
    /**
     * Terminal rows. The whole region must fit within this — a region taller
     * than the viewport scrolls its top into scrollback, and cursor-relative
     * repaint/wipe ({@code cursorUp(n)}) clamps at the viewport top and can no
     * longer reach it (leaving stale lines, e.g. a lingering spinner on cancel).
     */
    int height = DEFAULT_HEIGHT;  // package-private: tests set it directly
    /** [global].nerdfont — gates the powerline pill header. Package-private: tests set it directly. */
    boolean nerdfont = GlobalConfig.nerdfont();
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
    private double peakFraction;   // monotonic-display floor: the bar never renders below this
    private boolean etaEnabled;    // show the [hh:mm:ss] countdown — only when learned timings exist
    private long finishSeq;

    /** A weight unit is ≈150 ms (the weight model / interpolation constant); the bridge from bar weight to remaining ms. */
    private static final long MS_PER_WEIGHT = 150L;
    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** Pre-formatted completion lines, oldest→newest; bounded to {@link #MAX_COMPLETIONS}. */
    private final List<String> recentCompletions = new ArrayList<>();
    private int completedCount;

    private Thread animator;

    // output capture: while active, System.out/err are redirected so process
    // output prints above the region (see captureOutput / restoreStreams).
    private PrintStream savedOut;
    private PrintStream savedErr;
    private volatile LineSink sink;   // read by the animator thread for stale flushing
    private boolean capturing;

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
        int[] size = animate ? detectSize() : new int[]{DEFAULT_HEIGHT, DEFAULT_WIDTH};
        CommandManager cm = new CommandManager(out, animate, true, size[1]);
        cm.height = size[0];
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

    /** Terminal width detected at construction (columns). */
    public int width() { return width; }

    /** Aggregate progress numerator currently driving the bar (for tests/inspection). */
    public long numerator() { return numerator; }

    /** Aggregate progress denominator currently driving the bar (for tests/inspection). */
    public long denominator() { return denominator; }

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

    /**
     * Enable the {@code [hh:mm:ss]} countdown in the header. The caller turns this on
     * only when learned timings exist for the build — without them the weight total
     * is a static guess and a remaining-time estimate would mislead.
     */
    public void enableEta(boolean enabled) {
        synchronized (lock) {
            this.etaEnabled = enabled;
        }
    }

    /** Set the aggregate progress numerator/denominator for the bar. */
    public void progress(long numerator, long denominator) {
        synchronized (lock) {
            // Monotonic display guard: at a STABLE total, never let the rendered
            // fraction slide backward — a residual reweight that drops num/den holds
            // at the peak until real progress passes it. But when the total GROWS
            // (the uncalibrated path discovering more members, or genuine new work)
            // the fraction legitimately rebases, so reset the peak instead of pinning
            // at 100%. The calibrated workspace build fixes its total up front
            // (Phase 1.5), so there the total is stable and the guard is always live.
            double f = denominator > 0 ? (double) numerator / denominator : 0.0;
            if (denominator > this.denominator) {
                peakFraction = f;                       // total grew → rebase
            } else if (denominator > 0 && f < peakFraction) {
                numerator = Math.round(peakFraction * denominator);   // hold the peak
            } else {
                peakFraction = f;
            }
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    /**
     * Record a finished unit's pre-formatted completion line in the live
     * completed-tail rendered below the active rows (newest first, capped to
     * {@link #MAX_COMPLETIONS}; the rest collapse into a "… plus N more …"
     * footer). Callers that aren't animating should print append-only instead
     * (see {@link #animating()}) — this only feeds the live region.
     */
    public void addCompletion(String line) {
        synchronized (lock) {
            if (done) return;
            completedCount++;
            recentCompletions.add(line);
            if (recentCompletions.size() > MAX_COMPLETIONS) recentCompletions.remove(0);
        }
    }

    /** True when the region animates live (interactive tty); false under pipes / {@code --quiet}. */
    public boolean animating() {
        return animate;
    }

    // --- completion -------------------------------------------------------

    /** Settle with {@code ✔ <goal> Successful: <message>} (the head in green). */
    public void finishSuccess(String message) {
        finishSuccess(message, List.of());
    }

    /**
     * Like {@link #finishSuccess(String)}, but first prints {@code above} —
     * buffered subprocess output (compiler warnings, &c.) — as scrollback above
     * the result line, so the {@code ✔ Successful} summary is the last thing the
     * user sees. The lines land after the live region is wiped, under one lock,
     * so they never interleave with the bar.
     */
    public void finishSuccess(String message, List<String> above) {
        String head = Glyphs.CHECK + (goalName().isEmpty() ? "" : " " + goalName()) + " Successful";
        settle(Theme.colorize(head, Theme.active().success()) + ": " + message, above);
    }

    /** Settle with a red cross and a failure message. */
    public void finishFailure(String message) {
        finishFailure(message, List.of());
    }

    /** Like {@link #finishFailure(String)}, with buffered output printed above the result line. */
    public void finishFailure(String message, List<String> above) {
        settle(Theme.colorize(Glyphs.CROSS, Theme.active().error()) + " " + message, above);
    }

    /** The goal/verb name shown in the header ("Building", "Locking", …). */
    private String goalName() {
        String n = goalMode ? name : label;
        return n == null ? "" : n;
    }

    private void settle(String line) {
        settle(line, List.of());
    }

    private void settle(String line, List<String> above) {
        restoreStreams();   // flush any captured output above the region first
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
            // Deferred subprocess output (e.g. compiler warnings) prints as
            // scrollback above the result line, with a blank separator, so the
            // settle line stays the last thing on screen.
            if (above != null && !above.isEmpty()) {
                for (String s : above) out.println(s);
                out.println();
            }
            out.println(line);
            out.flush();
        }
    }

    /** Cancel line text (shown by {@link GlobalCancel}): {@code <goal> canceled by user}. */
    @Override
    public String canceledMessage() {
        return goalName().isEmpty() ? "Canceled by user" : goalName() + " canceled by user";
    }

    @Override
    public void renderCanceled() {
        // Ctrl-C: hand the streams back so GlobalCancel's notice goes to the real
        // stderr below the wiped region, then wipe.
        restoreStreams();
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
        restoreStreams();
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
            // Return to column 0 first: cursorUp preserves the column, and on a
            // Ctrl-C the tty has just echoed "^C" at the cursor (two columns in),
            // so a bare ERASE_DISPLAY_TO_END would leave the first two columns of
            // the top line — the spinner glyph — on screen.
            out.print('\r');
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
            if (goalMode) paintGoal();
            else paintSimple();
            out.flush();
            frame = (frame + 1) % FRAMES.length;
        }
    }

    /** Repaint the single simple-mode spinner line in place (must hold {@link #lock}). */
    private void paintSimple() {
        out.print('\r');
        out.print(Theme.colorize(FRAMES[frame], frameColors[frame]));
        out.print(' ');
        out.print(label);
        out.print(ELLIPSIS);
        out.print(Ansi.ERASE_LINE_TO_END);
        out.print(Ansi.TASKBAR_INDETERMINATE);
    }

    /**
     * Print {@code text} as a permanent line <em>above</em> the live region, then
     * repaint the region just below it — so process/phase output scrolls up and
     * the {@code CommandManager} view stays pinned to the bottom. No-op-ish
     * (plain {@code println}) when not animating or already settled.
     */
    public void writeAbove(String text) {
        synchronized (lock) {
            if (done || !animate) {
                out.println(text);
                out.flush();
                return;
            }
            // Erase the live region back to its top.
            if (goalMode) {
                if (linesDrawn > 0) out.print(Ansi.cursorUp(linesDrawn));
                out.print(Ansi.ERASE_DISPLAY_TO_END);
            } else {
                out.print(Ansi.CLEAR_LINE);
            }
            // Emit the text where the region's top was — it becomes scrollback.
            out.print(text);
            out.print('\n');
            // Repaint the region fresh, immediately below the emitted text.
            if (goalMode) {
                lastLines = List.of();
                linesDrawn = 0;
                paintGoal();
            } else {
                paintSimple();
            }
            out.flush();
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
     * Build the goal region's lines (header-with-bar, active phase tree, completed
     * tail). Pure — no cursor control, no output. Package-private for tests; the
     * {@code frame} field and {@code elapsedMillis} are passed/read so tests are
     * deterministic.
     */
    public List<String> renderGoalLines(int cols, long elapsedMillis) {
        AttributedStyle dim = Theme.active().darkGray();
        String sep = Theme.colorize("›", dim);
        List<String> lines = new ArrayList<>();

        // 1. Header: {spinner} {name} {bar} …elapsed…. The aggregate bar is inlined
        // after the goal name; the elapsed trails the bar in bright-black italic
        // (…52s… rather than a parenthesised suffix). The member moved to the
        // active row, so the header carries no phase detail.
        lines.add(goalHeader(elapsedMillis));

        // 2. Active phase tree: only the currently-running phase(s). Pending and
        // per-phase completed rows aren't shown — most phases finish in well under
        // a second, so the tree of boxes/checkmarks was render cost without telling
        // the user anything they'd act on. Unit-level completions are summarized in
        // the completed tail below; the aggregate bar conveys overall progress.
        List<Row> active = new ArrayList<>();
        for (Row r : rows.values()) {
            if (r.state == RowState.ACTIVE) active.add(r);
        }
        // Budget the region to the viewport: header (1) + active rows + the
        // completed tail (+ an optional footer), within height-1 so a line of
        // headroom keeps the region where cursor-relative repaint can reach it.
        int budget = Math.max(1, height - 2);   // lines available below the header
        int shown = Math.min(active.size(), Math.min(MAX_ROWS, budget));
        for (int i = 0; i < shown; i++) {
            // Tree branches: ├─ for every active row but the last, ╰─ to close.
            // A leading space indents the whole region by one column (the header's
            // matching indent is part of its colored pill).
            String prefix = Theme.colorize(i == shown - 1 ? " ╰─ " : " ├─ ", dim);
            lines.add(prefix + renderActiveRow(active.get(i), sep));
        }
        budget -= shown;

        // 3. Completed tail: recently-finished units below the active tree, newest
        // first, indented four spaces (aligning under the ╰─ branch content with the
        // region's one-column indent). When more have completed than fit, a
        // bright-black italic "… plus N more …" footer (indented further) collapses
        // the overflow.
        if (completedCount > 0 && budget > 0) {
            boolean overflow = completedCount > Math.min(MAX_COMPLETIONS, budget);
            int cap = Math.max(0, Math.min(MAX_COMPLETIONS, overflow ? budget - 1 : budget));
            int compShown = Math.min(recentCompletions.size(), cap);
            int have = recentCompletions.size();
            for (int i = 0; i < compShown; i++) {
                lines.add("    " + recentCompletions.get(have - 1 - i));
            }
            int more = completedCount - compShown;
            if (more > 0) {
                lines.add(Theme.colorize("      … plus " + more + " more …", dim.italic()));
            }
        }
        return lines;
    }

    /**
     * The goal header line: {@code {spinner} {name} {bar} …elapsed…}.
     *
     * <p>With a Nerd Font ({@code [global].nerdfont}) the spinner (glyph-cycling
     * only — same bright-white foreground as the name, no color animation) + name form
     * a pill filled with the accent (the bar gradient's bright end), closed by a
     * U+E0B0 powerline cap whose <em>foreground</em> is that same pill color (so its
     * body blends with the chip) and whose <em>background</em> tracks the bar's lead
     * color, tapering the chip into the first bar cell; the cap is underlined to sit
     * flush with the bar's underscored track. Without a Nerd Font it's the plain
     * animated spinner + a bright-white name, as before. Either way a leading space
     * gives the whole region its one-column indent — on the pill background in Nerd
     * Font mode, so the chip reaches the left edge.
     */
    private String goalHeader(long elapsedMillis) {
        AttributedStyle dim = Theme.active().darkGray();
        String barStr = bar.render(numerator, denominator);
        StringBuilder h = new StringBuilder();
        if (nerdfont) {
            // Pill background = the accent (the bar gradient's bright end).
            Rgb chipBg = Theme.active().progressGradient().end();
            AttributedStyle chip = Theme.active().withBackground(Theme.active().brightWhite(), chipBg); // bright-white on accent
            Rgb lead = bar.leadColor(numerator, denominator);
            // Cap foreground = the pill color (so its body blends with the chip);
            // background = the bar's lead color, tapering the pill into the bar.
            AttributedStyle cap = Theme.active()
                    .withBackground(Theme.active().bright(chipBg), lead).underline();
            // The spinner shares the chip style: only its glyph cycles, in the same
            // bright-white as the name — no color animation inside the pill. A leading
            // space (also on the pill background) gives the region its one-column
            // indent while extending the pill to the left edge.
            h.append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(FRAMES[frame], chip))
                    .append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(name, chip))
                    .append(Theme.colorize(" ", chip))
                    .append(Theme.colorize(Glyphs.SEGMENT_END_NERD, cap))
                    .append(barStr);
        } else {
            // Plain leading space to match the region's one-column indent.
            h.append(' ')
                    .append(Theme.colorize(FRAMES[frame], frameColors[frame])).append(' ')
                    .append(Theme.colorize(name, Theme.active().focused()))
                    .append(' ').append(barStr);
        }
        // ETA countdown between the percent and the elapsed: `{bar} 7% [00:01:02] …4s…`.
        // Only when learned timings exist (etaEnabled) — the weight total is then a
        // calibrated remaining-time estimate; remaining = (denominator − numerator)
        // weights × ≈150 ms. The numerator is monotonic, so the clock counts down.
        if (etaEnabled && denominator > 0 && numerator < denominator) {
            h.append(' ').append(clock((denominator - numerator) * MS_PER_WEIGHT));
        }
        h.append(' ').append(Theme.colorize(
                ELLIPSIS + fmtElapsed(elapsedMillis) + ELLIPSIS, dim.italic()));
        return h.toString();
    }

    /** {@code [hh:mm:ss]} remaining — bright-black brackets/colons, gray digits. */
    private static String clock(long remainingMs) {
        long s = Math.max(0, remainingMs) / 1000;
        AttributedStyle bracket = Theme.active().darkGray();   // [ : ]
        AttributedStyle digit = Theme.active().normalGray();   // the numbers
        return Theme.colorize("[", bracket)
                + Theme.colorize(String.format("%02d", s / 3600), digit)
                + Theme.colorize(":", bracket)
                + Theme.colorize(String.format("%02d", (s % 3600) / 60), digit)
                + Theme.colorize(":", bracket)
                + Theme.colorize(String.format("%02d", s % 60), digit)
                + Theme.colorize("]", bracket);
    }

    /**
     * A running phase row: {@code <group>:<artifact> › <Phase>[ › <message>]} —
     * no status glyph, member coordinate colored (cyan group, bright-cyan
     * artifact). No trailing ellipsis.
     */
    private static String renderActiveRow(Row r, String sep) {
        StringBuilder sb = new StringBuilder();
        sb.append(coloredMember(r.member))
                .append(' ').append(sep).append(' ')
                .append(Theme.colorize(r.phase, Theme.active().settled()));
        if (r.message != null && !r.message.isEmpty()) {
            sb.append(' ').append(sep).append(' ')
                    .append(Theme.colorize(r.message, Theme.active().settled()));
        }
        return sb.toString();
    }

    /** {@code group:artifact} → cyan group + bright-cyan artifact; plain if no colon. */
    public static String coloredMember(String member) {
        int colon = member.indexOf(':');
        if (colon < 0) return Theme.colorize(member, Theme.active().settled());
        return Theme.colorize(member.substring(0, colon), Theme.active().cyan())
                + ":"
                + Theme.colorize(member.substring(colon + 1), Theme.active().brightCyan());
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
                // Flush a captured partial line that's gone quiet (no newline),
                // OUTSIDE the render lock so the order matches phase writes
                // (sink → lock) and can't deadlock with tick() (lock only).
                LineSink s = sink;
                if (s != null) s.maybeFlushStale(STALE_FLUSH_MS);
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
     *
     * <p>JLine can do this width-aware ({@code AttributedString.fromAnsi} /
     * {@code WCWidth}), but measured at +187–312 KB on the native image — its
     * ANSI parser / width tables aren't otherwise reachable — to gain East-Asian
     * wide-glyph handling that jk's ASCII coordinates and single-width
     * box/spinner glyphs never need. Not worth the binary growth, so this stays
     * hand-rolled by design.
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
     * Terminal size {@code {rows, cols}}, detected once, leak-free. We
     * deliberately do NOT build a JLine terminal: JLine probes the terminal with
     * capability queries (DA1 {@code \e[c}, mode reports like {@code \e[?2027$p}),
     * and a transient build-then-close races the async replies — they arrive
     * after we exit and the shell echoes them as garbage. Instead ask the tty
     * directly via {@code stty size} (an ioctl, no escape sequences), then the
     * {@code $LINES}/{@code $COLUMNS} env, then conservative defaults. Only
     * called when animating (interactive tty).
     */
    /** Terminal width in columns ({@code stty size} → {@code $COLUMNS} → {@value #DEFAULT_WIDTH}). */
    public static int detectColumns() {
        return detectSize()[1];
    }

    private static int[] detectSize() {
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
                int rows = Integer.parseInt(parts[0]);
                int cols = Integer.parseInt(parts[1]);
                if (rows > 0 && cols > 0) return new int[]{rows, cols};
            }
        } catch (Exception ignored) {
            // no /dev/tty, no stty (e.g. Windows), or unparsable — fall through
        }
        return new int[]{envInt("LINES", DEFAULT_HEIGHT), envInt("COLUMNS", DEFAULT_WIDTH)};
    }

    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            if (v != null) {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            }
        } catch (NumberFormatException ignored) {
            // not a number — use the fallback
        }
        return fallback;
    }

    /** Restores {@code System.out}/{@code System.err} when closed (no checked exception). */
    public interface OutputScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Redirect {@code System.out}/{@code System.err} so any process/phase output
     * is line-buffered and printed <em>above</em> the live region via
     * {@link #writeAbove}, keeping the region pinned to the bottom. The region
     * itself keeps painting to the original (captured) stdout, so there's no
     * recursion. Close the returned scope (try-with-resources) to restore the
     * streams and flush any trailing partial line. No-op when not animating.
     */
    public OutputScope captureOutput() {
        synchronized (lock) {
            if (!animate || capturing) return () -> { };
            savedOut = System.out;
            savedErr = System.err;
            sink = new LineSink(this);
            PrintStream redirect = new PrintStream(sink, true, StandardCharsets.UTF_8);
            System.setOut(redirect);
            System.setErr(redirect);
            capturing = true;
        }
        return this::restoreStreams;
    }

    /**
     * Restore the real {@code System.out}/{@code System.err} and flush any
     * trailing partial line above the region. Idempotent — called by the
     * {@link OutputScope}, and defensively when the region settles (so a Ctrl-C
     * mid-goal hands the streams back before {@link GlobalCancel} prints).
     */
    private void restoreStreams() {
        LineSink toFlush = null;
        synchronized (lock) {
            if (!capturing) return;
            capturing = false;
            System.setOut(savedOut);
            System.setErr(savedErr);
            toFlush = sink;
        }
        if (toFlush != null) toFlush.flushPartial();  // may writeAbove (re-locks)
    }

    /** Buffers redirected bytes and forwards each completed line to {@link #writeAbove}. */
    private static final class LineSink extends OutputStream {
        private final CommandManager cm;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private long lastWriteNanos;   // when the current partial line last grew

        LineSink(CommandManager cm) {
            this.cm = cm;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                buf.write(b);
                lastWriteNanos = System.nanoTime();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int start = off;
            for (int i = off; i < off + len; i++) {
                if (b[i] == '\n') {
                    buf.write(b, start, i - start);
                    emit();
                    start = i + 1;
                }
            }
            if (start < off + len) {
                buf.write(b, start, off + len - start);
                lastWriteNanos = System.nanoTime();
            }
        }

        /**
         * Flush a buffered partial line that hasn't grown for {@code ms} (a
         * sliding window reset on each write) — so output without a trailing
         * newline still appears in a timely manner instead of stalling.
         */
        synchronized void maybeFlushStale(long ms) {
            if (buf.size() == 0) return;
            if (System.nanoTime() - lastWriteNanos < ms * 1_000_000L) return;
            emit();
        }

        synchronized void flushPartial() {
            if (buf.size() > 0) emit();
        }

        private void emit() {
            String s = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            cm.writeAbove(s);
        }
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
