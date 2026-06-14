// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Terminal result of a {@link Goal#run}. Lists every phase that ran
 * (with status + duration) plus the accumulated warnings and errors
 * emitted across all phases.
 */
public record GoalResult(
        String goalName,
        boolean success,
        Duration duration,
        List<PhaseReport> phases,
        List<Diagnostic> warnings,
        List<Diagnostic> errors,
        boolean cancelled,
        boolean userCancelled) {

    public GoalResult {
        Objects.requireNonNull(goalName, "goalName");
        Objects.requireNonNull(duration, "duration");
        phases = List.copyOf(phases);
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    /**
     * 7-arg compatibility constructor. {@code cancelled=true} is
     * presumed user-initiated when no separate flag is supplied —
     * the older callers all came from the SIGINT bridge.
     */
    public GoalResult(
            String goalName, boolean success, Duration duration,
            List<PhaseReport> phases, List<Diagnostic> warnings,
            List<Diagnostic> errors, boolean cancelled) {
        this(goalName, success, duration, phases, warnings, errors,
                cancelled, cancelled);
    }

    /** One row in the report's per-phase breakdown. */
    public record PhaseReport(String name, PhaseStatus status, Duration duration) {}

    /**
     * Structured diagnostic from {@link PhaseContext#warn} / {@link PhaseContext#error}.
     *
     * <p>{@code test} and {@code exceptionClass} carry the discrete parts of a
     * test failure (the failing test's display name and the thrown exception's
     * class) so structured consumers don't have to parse them back out of a
     * glued {@code message}. Both are empty for diagnostics that aren't test
     * failures.
     */
    public record Diagnostic(String phase, String code, String message, String test, String exceptionClass) {

        /** Diagnostic with no test identity — the common case (javac, resolver, …). */
        public Diagnostic(String phase, String code, String message) {
            this(phase, code, message, "", "");
        }
    }
}
