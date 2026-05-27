// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import org.jline.utils.Signals;

import java.nio.file.Path;

/**
 * Single entry point CLI commands use to run a {@link Goal} against
 * the right set of console listeners — picks {@link ProgressBarListener}
 * (default TTY), {@link VerboseListener} ({@code --verbose}),
 * {@link NdjsonListener} ({@code --output json}), or
 * {@link SilentListener} (non-TTY, {@code --quiet}, or interactive
 * goal); always layers an {@link EventLogListener} on top so the run
 * lands in {@code <cacheRoot>/runs/}.
 *
 * <p>Also installs a SIGINT bridge for the goal's lifetime: Ctrl-C
 * calls {@link Goal#requestCancel} (cooperative), and after the goal
 * settles we restore the global cancel handler from
 * {@link dev.jkbuild.cli.tui.GlobalCancel}. If a phase ignores the
 * cooperative signal, the goal's own 200ms grace + thread interrupt
 * brings it down.
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
     * Pick listeners + run the goal under a SIGINT bridge. Returns the
     * goal's {@link GoalResult}; caller decides what exit code to
     * surface based on {@code result.success()}.
     */
    public static GoalResult run(Goal goal, Mode mode, Path cacheRoot) {
        // Always log every run for post-hoc debug. Best-effort: a
        // failed log open just leaves the listener out of the chain.
        EventLogListener log = EventLogListener.open(cacheRoot, goal.name());
        if (log != null) goal.addListener(log);

        GoalListener console = chooseConsoleListener(goal, mode);
        if (console != null) goal.addListener(console);

        installSigintBridge(goal);
        try {
            return goal.run();
        } finally {
            // Restore the global handler. JLine's Signals.register
            // doesn't expose a "chain back to previous" API, but
            // GlobalCancel.install() re-registers cleanly.
            dev.jkbuild.cli.tui.GlobalCancel.install();
        }
    }

    /**
     * Variant that derives the cache root from {@link JkDirs#cache}.
     * Use when the command doesn't have an explicit override.
     */
    public static GoalResult run(Goal goal, Mode mode) {
        return run(goal, mode, JkDirs.cache());
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

    private static GoalListener chooseConsoleListener(Goal goal, Mode mode) {
        // Interactive goals (wizards) must NOT render a progress bar —
        // the wizard owns the terminal. Same for JSON output (events
        // already go to stdout via NdjsonListener) and explicit quiet.
        if (goal.interactive()) return new SilentListener(System.out, System.err);
        return switch (mode) {
            case QUIET -> new SilentListener(System.out, System.err);
            case JSON -> new NdjsonListener(System.out);
            case VERBOSE -> new VerboseListener(System.out, System.err);
            case AUTO -> isInteractiveTerminal()
                    ? new ProgressBarListener(System.out, System.err,
                            goal.phases().stream().map(dev.jkbuild.run.Phase::name).toList())
                    : new SilentListener(System.out, System.err);
        };
    }

    /**
     * Register a Ctrl-C handler that flips the goal's cancel flag and
     * paints a brief notice on stderr. The goal's own scheduler
     * enforces the 200ms grace before interrupting threads.
     */
    private static void installSigintBridge(Goal goal) {
        Signals.register("INT", () -> {
            if (goal.snapshot().cancelled()) {
                // Second Ctrl-C → halt now.
                System.err.print("\n" + Theme.colorize("𝘅 Force-canceled", Theme.error()) + "\n");
                Runtime.getRuntime().halt(130);
            }
            goal.requestCancel();
            System.err.print("\n" + Theme.colorize("⌫ Canceling…", Theme.warning())
                    + " (Ctrl-C again to force)\n");
        });
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }
}
