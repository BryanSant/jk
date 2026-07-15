// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.testrunner;

/**
 * Stable event names used in the jk-test wire protocol. The string form (lowercase enum name) is
 * what travels on the wire — kept short to keep the payload tight, and stable across encodings so
 * the JSON-today / CBOR-tomorrow switch is purely an encoding change.
 */
public enum EventType {

    /** Emitted once at the start of a run. Payload: {@code {ts, engines:[...]}}. */
    PLAN_STARTED,

    /**
     * A test or container is about to execute. Payload: {@code {id, display, parent, type, source}}.
     */
    STARTED,

    /** A test or container finished. Payload: {@code {id, status, duration_ms, throwable?}}. */
    FINISHED,

    /** A test was skipped. Payload: {@code {id, reason}}. */
    SKIPPED,

    /** A dynamic test was registered (e.g. {@code @TestFactory}). Payload: {@code {id, parent}}. */
    DYNAMIC_REGISTERED,

    /** A test published a {@code ReportEntry}. Payload: {@code {id, entries:{}}}. */
    REPORT,

    /**
     * Emitted once per top-level test class during discovery (one-shot, list-only, and parallel modes
     * all emit this up-front). Lets the parent build the work queue and populate the progress bar's
     * total before any test runs. Payload: {@code {"class": "<fqcn>"}}.
     */
    DISCOVERED,

    /**
     * Emitted once after the runner finishes enumerating the test plan, before any tests start.
     * Carries the totals the parent needs to render a "{n of N}" progress bar without counting events
     * itself. Payload: {@code {"classes": <int>, "tests": <int>}}.
     */
    DISCOVERY_TOTAL,

    /**
     * Pull-mode signal: this worker finished its current class and is ready for another. Parent
     * replies on the worker's stdin with a {@code RUN <fqcn>} line or {@code DONE} to terminate. The
     * {@code w} field on every event already identifies which worker it is.
     */
    READY,

    /**
     * Final summary emitted once at the end. Payload: {@code {total, successful, failed, skipped,
     * aborted, duration_ms}}.
     */
    PLAN_FINISHED;

    /** Lowercase wire form, e.g. {@code "plan_started"}. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
