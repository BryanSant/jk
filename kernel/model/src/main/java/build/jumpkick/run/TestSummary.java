// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.run;

import java.util.List;

/**
 * A test run's aggregate outcome — the counts a front-end renders and derives exit codes from,
 * plus the individual failures. Produced by the engine's JUnit launcher (in-process) or
 * reconstructed from the {@code pipeline-finish} wire event's counters (engine-hosted, where the
 * failure list stays engine-side — failures already streamed as {@code error} events).
 */
public record TestSummary(long total, long succeeded, long failed, long skipped, List<Failure> failures) {

    public TestSummary {
        failures = List.copyOf(failures);
    }

    public boolean allPassed() {
        return failed == 0;
    }

    /**
     * One failed test. {@code exceptionClass} and {@code message} are the failure's throwable split
     * into discrete fields (the exception's class name and its message); either may be empty when the
     * failure carries no throwable — e.g. a non-zero runner exit, where {@code message} holds the
     * synthetic "runner exited N" summary and {@code exceptionClass} is empty. {@code details} is the
     * full stack trace as the runner rendered it (empty when there is no captured throwable).
     */
    public record Failure(String testName, String exceptionClass, String message, String details) {}
}
