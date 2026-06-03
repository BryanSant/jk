// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.run.GoalResult;

import java.util.function.Function;

/**
 * Describes how a simple-task command should present itself in the console:
 * the human verb shown next to the spinner ("Locking", "Syncing", …) and the
 * final result line to print, derived from the {@link GoalResult}.
 *
 * <p>The {@code onSuccess} / {@code onFailure} mappers run after the goal
 * settles, so they may close over the command's goal/state to build a rich
 * message (e.g. {@code "Resolved 13 dependencies in 717ms"}). A mapper may
 * embed ANSI (e.g. a dim "in 717ms") — the result line is printed verbatim
 * after the {@code ✔}/{@code ✗} marker.
 */
public record ConsoleSpec(
        String verb,
        Function<GoalResult, String> onSuccess,
        Function<GoalResult, String> onFailure) {
}
