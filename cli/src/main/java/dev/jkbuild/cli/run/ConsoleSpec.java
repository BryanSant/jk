// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.run.GoalResult;
import java.time.Duration;
import java.util.function.Function;

/**
 * Describes how a simple-task command should present itself in the console: the human verb shown
 * next to the spinner ("Locking", "Syncing", …) and the final result line to print, derived from
 * the {@link GoalResult}.
 *
 * <p>The {@code onSuccess} / {@code onFailure} mappers run after the goal settles, so they may
 * close over the command's goal/state to build a rich message (e.g. {@code "Resolved 13
 * dependencies"}). The framework always appends a dim italic {@code "took Xms"} suffix so
 * individual commands do not need to format duration themselves.
 *
 * <p>{@code softFailure}, when non-null, is consulted even on a successful {@link GoalResult}: a
 * non-null return renders the red failure chip with that exact sentence (no "Failed to &lt;verb&gt;"
 * derivation), overriding the normal success rendering. For a command whose goal genuinely
 * succeeded but which then discovers it cannot proceed — {@code jk run} building fine but finding
 * no runnable entry point — this settles as a failure without forcing the underlying build itself
 * to report one. A null return (or a null {@code softFailure} function) leaves the normal
 * success/failure decision to {@link GoalResult#success()}.
 */
public record ConsoleSpec(
        String verb,
        Function<GoalResult, String> onSuccess,
        Function<GoalResult, String> onFailure,
        boolean chip,
        boolean exec,
        Function<GoalResult, String> softFailure) {

    /**
     * Default presentation — the generic {@code ✓ <Verb> Successful: <msg>} finish. Set {@code chip =
     * true} (the 4-arg form) to settle through the goal-chip renderer instead ({@code ✓ Build ▶
     * <onSuccess>}); {@code onSuccess}/{@code onFailure} then return the tail that follows the chip's
     * verb. Set both {@code chip = true} and {@code exec = true} (the 5-arg form) to settle with
     * {@code Glyphs.PLAY} instead of {@code Glyphs.CHECK} — for commands that hand off to a
     * subprocess after the goal (e.g. {@code jk run}).
     */
    public ConsoleSpec(String verb, Function<GoalResult, String> onSuccess, Function<GoalResult, String> onFailure) {
        this(verb, onSuccess, onFailure, false, false);
    }

    public ConsoleSpec(
            String verb, Function<GoalResult, String> onSuccess, Function<GoalResult, String> onFailure, boolean chip) {
        this(verb, onSuccess, onFailure, chip, false);
    }

    public ConsoleSpec(
            String verb,
            Function<GoalResult, String> onSuccess,
            Function<GoalResult, String> onFailure,
            boolean chip,
            boolean exec) {
        this(verb, onSuccess, onFailure, chip, exec, null);
    }

    /**
     * Dim italic {@code "took Xms"} duration suffix — appended by the framework to every result line.
     */
    public static String took(Duration d) {
        return Theme.colorize(
                "took " + fmtDuration(d), Theme.active().darkGray().italic());
    }

    /**
     * A diagnostic error line: red {@code ‼ Error}, the phase in plain brackets, then the message on
     * its own line — e.g.
     *
     * <pre>‼ Error [compile-test]:
     * Foo.java:3: package … does not exist</pre>
     */
    public static String errorLine(String phase, String message) {
        return Theme.colorize(Glyphs.CROSS + " Error", Theme.active().error())
                + " ["
                + phase
                + "]:"
                + System.lineSeparator()
                + message;
    }

    /** Render an error diagnostic for the console, per its {@code code}. */
    public static String renderError(GoalResult.Diagnostic d) {
        return renderError(d.phase(), d.code(), d.message());
    }

    /** Render an error diagnostic from its parts (used by live + summary paths alike). */
    public static String renderError(String phase, String code, String message) {
        if ("verbatim".equals(code)) return message;
        if (isCompilerCode(code)) return CompilerDiagnostic.render(message);
        return errorLine(phase, message);
    }

    /** Render a warning diagnostic for the console, per its {@code code}. */
    public static String renderWarning(GoalResult.Diagnostic d) {
        if (isCompilerCode(d.code())) return compilerWarning(d.phase(), d.message());
        return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning())
                + " [" + d.phase() + "]: " + d.message();
    }

    /**
     * A compiler warning: a yellow {@code ‼ Warning [phase]:} header, then the compiler's verbatim
     * block colorized like an error (relative paths, etc.).
     */
    public static String compilerWarning(String phase, String message) {
        return Theme.colorize(Glyphs.BANG + " Warning", Theme.active().warning())
                + " ["
                + phase
                + "]:"
                + System.lineSeparator()
                + CompilerDiagnostic.render(message);
    }

    /** Compiler diagnostics (javac/kotlinc) carry a verbatim multi-line block. */
    public static boolean isCompilerCode(String code) {
        return "javac".equals(code) || "kotlinc".equals(code);
    }

    /**
     * Formats a {@code [k of N]} counter with darkGray brackets and a zero-padded numerator.
     * Used in build completion lines and similar indexed output.
     *
     * <p>Example: {@code countBracket(1, 16, theme)} → {@code "[01 of 16]"} with dark-gray brackets.
     */
    public static String countBracket(int n, int total, Theme t) {
        String num = String.format("%0" + Integer.toString(total).length() + "d", n);
        return Theme.colorize("[", t.darkGray()) + num + " of " + total + Theme.colorize("]", t.darkGray());
    }

    /**
     * Formats an absolute {@code [N]} counter with darkGray brackets — no denominator. Used when
     * the total is not known ahead of time (e.g. {@code jk lock} dependency resolution).
     *
     * <p>Example: {@code countBracket(42, theme)} → {@code "[42]"} with dark-gray brackets.
     */
    public static String countBracket(int n, Theme t) {
        return Theme.colorize("[", t.darkGray()) + n + Theme.colorize("]", t.darkGray());
    }

    /**
     * Human-friendly duration: {@code 712ms}, {@code 3.1s}, {@code 2m 4s}, {@code 1h 3m 2s}, {@code
     * 1d 12h 13m 5s}.
     */
    public static String fmtDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long totalSec = d.toSeconds();
        if (totalSec < 60) return String.format("%.1fs", ms / 1000.0);
        long days = totalSec / 86400;
        long hours = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        return minutes + "m " + seconds + "s";
    }
}
