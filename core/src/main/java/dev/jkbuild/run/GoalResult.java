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
        boolean cancelled) {

    public GoalResult {
        Objects.requireNonNull(goalName, "goalName");
        Objects.requireNonNull(duration, "duration");
        phases = List.copyOf(phases);
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    /** One row in the report's per-phase breakdown. */
    public record PhaseReport(String name, PhaseStatus status, Duration duration) {}

    /** Structured diagnostic from {@link PhaseContext#warn} / {@link PhaseContext#error}. */
    public record Diagnostic(String phase, String code, String message) {}
}
