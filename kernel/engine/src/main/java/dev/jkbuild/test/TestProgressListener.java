// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

/**
 * Streaming callback surface for live test progress UI. {@link JUnitLauncher}
 * invokes these as each {@code ##JK:} protocol event flows past the parent's
 * aggregator, so a CLI can render a pinned status line, append failure
 * details to a buffer, etc., without re-implementing the wire-format
 * parsing.
 *
 * <p>Implementations must be thread-safe — in {@code workers > 1} mode, the
 * parent has one reader thread per worker, and each will call into the
 * listener concurrently.
 *
 * <p>All methods default to no-ops so a consumer can override just the
 * events it cares about. {@link #noop()} returns the singleton "do
 * nothing" listener used when no UI is wired up.
 */
public interface TestProgressListener {

    /**
     * Fired once per worker session after the runner enumerates the test
     * plan but before any tests start. Use to populate the
     * {@code [n of N]} denominator on a progress display. In parallel mode
     * this fires once for the discovery worker; per-worker pull sessions
     * also fire it (with their per-worker totals) — implementations can
     * either sum them or accept the first.
     */
    default void onDiscoveryTotal(int classes, int tests) {}

    /** A test method or container is about to execute. */
    default void onTestStarted(String id, String display, boolean isTest, int workerId) {}

    /**
     * A test method or container finished — exactly one fires per started node.
     *
     * <p>{@code wasStatic} is {@code true} when the test's id was present in
     * the static plan emitted by discovery. {@code false} marks invocations
     * registered dynamically at execute-time
     * ({@code @ParameterizedTest} / {@code @TestFactory} /
     * {@code @TestTemplate} / {@code @RepeatedTest}). UIs that want a stable
     * "of N" denominator should only advance their numerator when
     * {@code wasStatic} is true; dynamics are counted in the pass/fail tally
     * but not in the bar.
     */
    default void onTestFinished(
            String id, String display, String status,
            boolean isTest, boolean wasStatic, long durationMs, int workerId) {}

    /**
     * A test was skipped (e.g. {@code @Disabled}). See
     * {@link #onTestFinished} for the meaning of {@code wasStatic}.
     */
    default void onTestSkipped(
            String id, String display, String reason,
            boolean isTest, boolean wasStatic, int workerId) {}

    /** A non-protocol line came back from the child — user's {@code System.out} write, mostly. */
    default void onUserOutput(int workerId, String line) {}

    /** Detailed failure information from a FAILED finish (class + message + flattened stack). */
    default void onFailure(String id, String display, String exClass, String message, int workerId) {}

    /** Singleton no-op listener for callers that don't want a UI. */
    static TestProgressListener noop() {
        return new TestProgressListener() {};
    }
}
