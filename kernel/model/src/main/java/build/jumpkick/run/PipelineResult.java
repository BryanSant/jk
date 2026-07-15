// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.run;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Terminal result of a {@link Pipeline#run}. Lists every step that ran (with status + duration) plus
 * the accumulated warnings and errors emitted across all steps.
 */
public record PipelineResult(
        String pipelineName,
        boolean success,
        Duration duration,
        List<StepReport> steps,
        List<Diagnostic> warnings,
        List<Diagnostic> errors,
        boolean cancelled,
        boolean userCancelled) {

    public PipelineResult {
        Objects.requireNonNull(pipelineName, "pipelineName");
        Objects.requireNonNull(duration, "duration");
        steps = List.copyOf(steps);
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
    }

    /**
     * 7-arg compatibility constructor. {@code cancelled=true} is presumed user-initiated when no
     * separate flag is supplied — the older callers all came from the SIGINT bridge.
     */
    public PipelineResult(
            String pipelineName,
            boolean success,
            Duration duration,
            List<StepReport> steps,
            List<Diagnostic> warnings,
            List<Diagnostic> errors,
            boolean cancelled) {
        this(pipelineName, success, duration, steps, warnings, errors, cancelled, cancelled);
    }

    /** One row in the report's per-step breakdown. */
    public record StepReport(String name, StepStatus status, Duration duration) {}

    /**
     * Structured diagnostic from {@link StepContext#warn} / {@link StepContext#error}.
     *
     * <p>{@code test} and {@code exceptionClass} carry the discrete parts of a test failure (the
     * failing test's display name and the thrown exception's class) so structured consumers don't
     * have to parse them back out of a glued {@code message}. Both are empty for diagnostics that
     * aren't test failures.
     */
    public record Diagnostic(String step, String code, String message, String test, String exceptionClass) {

        /** Diagnostic with no test identity — the common case (javac, resolver, …). */
        public Diagnostic(String step, String code, String message) {
            this(step, code, message, "", "");
        }
    }
}
