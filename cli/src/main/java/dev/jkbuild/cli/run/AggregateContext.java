// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.run.GoalResult;

import java.util.List;

/**
 * Shared sink for a workspace build: one {@link CommandManager} (goal mode) that
 * every member's goal feeds through an {@link AggregateMemberListener}, so all
 * members render into a single bar + phase list.
 *
 * <p>Members run sequentially. {@code completedBase} is the summed scope of the
 * members that have already finished; while member <i>k</i> runs, the aggregate
 * numerator/denominator are {@code completedBase + memberNumerator/Denominator}.
 * The denominator therefore grows as each member starts (a finished member's
 * full scope is folded into the base) — pre-summing every member's estimate up
 * front (to avoid the small dip when a member starts) is a follow-up that needs
 * goal construction split out of {@code BuildCommand.runForDir}.
 */
public final class AggregateContext {

    private final CommandManager cm;
    private long completedBase;
    private volatile List<GoalResult.Diagnostic> lastErrors = List.of();

    public AggregateContext(CommandManager cm) {
        this.cm = cm;
    }

    public CommandManager view() {
        return cm;
    }

    /** Scope of all members finished so far. */
    public synchronized long completedBase() {
        return completedBase;
    }

    /** Fold a finished member's final denominator into the base. */
    public synchronized void completeMember(long memberDenominator) {
        completedBase += memberDenominator;
    }

    /** Errors from the most recently failed member goal. */
    public List<GoalResult.Diagnostic> lastErrors() { return lastErrors; }

    public void notifyErrors(List<GoalResult.Diagnostic> errors) { this.lastErrors = errors; }
}
