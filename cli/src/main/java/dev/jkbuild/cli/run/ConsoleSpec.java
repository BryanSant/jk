// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.run.GoalResult;

import java.time.Duration;
import java.util.function.Function;

/**
 * Describes how a simple-task command should present itself in the console:
 * the human verb shown next to the spinner ("Locking", "Syncing", …) and the
 * final result line to print, derived from the {@link GoalResult}.
 *
 * <p>The {@code onSuccess} / {@code onFailure} mappers run after the goal
 * settles, so they may close over the command's goal/state to build a rich
 * message (e.g. {@code "Resolved 13 dependencies"}). The framework always
 * appends a dim {@code "in Xms"} suffix so individual commands do not need
 * to format duration themselves.
 */
public record ConsoleSpec(
        String verb,
        Function<GoalResult, String> onSuccess,
        Function<GoalResult, String> onFailure) {

    /** Dim {@code "in Xms"} suffix — appended by the framework to every result line. */
    public static String inTime(Duration d) {
        return Theme.colorize("in " + fmtDuration(d), Theme.active().darkGray());
    }

    /** Human-friendly duration: {@code 712ms}, {@code 3.1s}, {@code 2m 4s}, {@code 1h 3m 2s}, {@code 1d 12h 13m 5s}. */
    public static String fmtDuration(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + "ms";
        long totalSec = d.toSeconds();
        if (totalSec < 60) return String.format("%.1fs", ms / 1000.0);
        long days    = totalSec / 86400;
        long hours   = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0)  return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
        if (hours > 0) return hours + "h " + minutes + "m " + seconds + "s";
        return minutes + "m " + seconds + "s";
    }
}
