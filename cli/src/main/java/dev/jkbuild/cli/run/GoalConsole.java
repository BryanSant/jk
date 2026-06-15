// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;

import java.nio.file.Path;
import java.util.List;

/**
 * Single entry point CLI commands use to run a {@link Goal} against
 * the right set of console listeners — picks {@link ProgressBarListener}
 * (default TTY), {@link VerboseListener} ({@code --verbose}),
 * {@link NdjsonListener} ({@code --output json}), or
 * {@link SilentListener} (non-TTY, {@code --quiet}, or interactive
 * goal); always layers an {@link EventLogListener} on top so the run
 * lands in {@code <cacheRoot>/runs/}.
 *
 * <p>Ctrl-C during a goal is handled by the app-level
 * {@link dev.jkbuild.cli.tui.GlobalCancel} handler (installed at startup):
 * it repaints the in-flight progress bar as canceled, prints
 * {@code ‼ Canceled by user}, and halts. There is no cooperative
 * unwind — a hard cancel is immediate and predictable.
 */
public final class GoalConsole {

    /** Output mode requested by the user. */
    public enum Mode {
        /** Default: progress bar on a TTY, silent on pipes. */
        AUTO,
        /** Per-phase lines (today's {@code --verbose}). */
        VERBOSE,
        /** Silent (today's {@code --quiet}, or interactive goals). */
        QUIET,
        /** NDJSON to stdout (today's {@code --output json}). */
        JSON
    }

    private GoalConsole() {}

    /**
     * Pick listeners + run the goal. Ctrl-C is handled by the app-level
     * {@link dev.jkbuild.cli.tui.GlobalCancel} handler. Returns the goal's
     * {@link GoalResult}; caller decides what exit code to surface based on
     * {@code result.success()}.
     */
    public static GoalResult run(Goal goal, Mode mode, Path cacheRoot) {
        // Always log every run for post-hoc debug. Best-effort: a
        // failed log open just leaves the listener out of the chain.
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);

        GoalListener console = chooseConsoleListener(goal, mode);
        if (console != null) goal.addListener(console);

        return goal.run();
    }

    /**
     * Variant that derives the cache root from {@link JkDirs#cache}.
     * Use when the command doesn't have an explicit override.
     */
    public static GoalResult run(Goal goal, Mode mode) {
        return run(goal, mode, JkDirs.cache());
    }

    /**
     * Simple-task variant: render the goal as a spinner + verb (on a TTY) and a
     * {@code ✓}/{@code ✗} result line from {@code spec}, instead of the
     * phase-by-phase progress bar. {@code --output json} still emits NDJSON and
     * {@code --verbose} still prints per-phase lines; otherwise the
     * {@link SimpleTaskListener} owns the output (animating only on a TTY).
     */
    public static GoalResult run(Goal goal, Mode mode, Path cacheRoot, ConsoleSpec spec) {
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);

        GoalListener console = switch (mode) {
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            // AUTO animates only on an interactive TTY; QUIET / pipes print the
            // result line without a spinner.
            case AUTO -> new SimpleTaskListener(System.out, System.err, spec, isInteractiveTerminal());
            case QUIET -> new SimpleTaskListener(System.out, System.err, spec, false);
        };
        goal.addListener(console);
        return goal.run();
    }

    /**
     * Translate {@code GlobalOptions} flags into a {@link Mode}. Lives
     * here so every command picks the same precedence:
     * {@code --output json} &gt; {@code --quiet} &gt;
     * {@code --no-progress} &gt; {@code --verbose} &gt; default.
     *
     * <p>{@code --output json} wins over the visualization flags
     * because it's an explicit "I want machine-readable output" —
     * the user's other preferences don't override that.
     */
    public static Mode modeFor(dev.jkbuild.cli.GlobalOptions opts) {
        if (opts == null) return Mode.AUTO;
        if (opts.outputIsJson()) return Mode.JSON;
        if (opts.quiet) return Mode.QUIET;
        if (opts.noProgress) return Mode.QUIET;
        if (opts.verbose) return Mode.VERBOSE;
        return Mode.AUTO;
    }

    /**
     * Goal-oriented variant: render the goal with the new {@link CommandManagerListener}
     * (spinner header + aggregate bar + dynamic phase list) attributed to
     * {@code member} (the project's {@code group:artifact}), then a
     * {@code ✓}/{@code ✗} result line from {@code spec}. {@code --output json}
     * still emits NDJSON and {@code --verbose} still prints per-phase lines.
     */
    public static GoalResult runGoal(Goal goal, Mode mode, Path cacheRoot,
                                     ConsoleSpec spec, String member) {
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);

        GoalListener console = switch (mode) {
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> new CommandManagerListener(System.out, System.err, spec, member,
                    goal.phases(), isInteractiveTerminal());
            case QUIET -> new CommandManagerListener(System.out, System.err, spec, member,
                    goal.phases(), false);
        };
        goal.addListener(console);
        return goal.run();
    }

    /**
     * Run {@code goal} with no console output (only the event log), returning its
     * result. For builds whose progress must NOT render to the terminal — e.g.
     * composite dependency units built concurrently, where N live progress bars
     * can't share one terminal region; the caller prints a compact summary line
     * per unit instead.
     */
    public static GoalResult runGoalSilently(Goal goal, Path cacheRoot) {
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);
        goal.addListener(new SilentListener(System.out, System.err));
        return goal.run();
    }

    private static GoalListener chooseConsoleListener(Goal goal, Mode mode) {
        // Interactive goals (wizards) must NOT render a progress bar —
        // the wizard owns the terminal. Same for JSON output (events
        // already go to stdout via NdjsonListener) and explicit quiet.
        if (goal.interactive()) return new SilentListener(System.out, System.err, true);
        return switch (mode) {
            case QUIET -> new SilentListener(System.out, System.err);
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> isInteractiveTerminal()
                    ? new ProgressBarListener(System.out, System.err, goal.phases())
                    : new SilentListener(System.out, System.err);
        };
    }

    /**
     * Run a workspace member's goal into a shared {@link AggregateContext} — its
     * events feed the one aggregate {@link dev.jkbuild.cli.tui.CommandManager}
     * (bar + phase list) instead of a per-member view. The shared view is
     * settled by the caller after the last member. Always records the event log.
     */
    public static GoalResult runGoalInto(Goal goal, Path cacheRoot, String member,
                                         AggregateContext agg) {
        return runGoalInto(goal, cacheRoot, member, agg, 0);
    }

    /**
     * As {@link #runGoalInto(Goal, Path, String, AggregateContext)}, but with the
     * member's reserved {@code slice} of the calibrated total (its pre-scan
     * estimate). The slice scales the member's own 0→100% into its share of the
     * aggregate bar so the bar advances cumulatively without backtracking. Pass
     * the same estimate that was summed into {@link AggregateContext#calibrate}.
     */
    public static GoalResult runGoalInto(Goal goal, Path cacheRoot, String member,
                                         AggregateContext agg, long slice) {
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);
        goal.addListener(new AggregateMemberListener(agg, member, goal.phases(), slice));
        return goal.run();
    }

    /** True when stdout is an interactive terminal (not a pipe, dumb, or CI). */
    public static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }
}
