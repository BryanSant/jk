// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

/**
 * Stable event names used in the jk-test wire protocol. The string form
 * (lowercase enum name) is what travels on the wire — kept short to keep the
 * payload tight, and stable across encodings so the JSON-today / CBOR-tomorrow
 * switch is purely an encoding change.
 */
public enum EventType {

    /** Emitted once at the start of a run. Payload: {@code {ts, engines:[...]}}. */
    PLAN_STARTED,

    /** A test or container is about to execute. Payload: {@code {id, display, parent, type, source}}. */
    STARTED,

    /** A test or container finished. Payload: {@code {id, status, duration_ms, throwable?}}. */
    FINISHED,

    /** A test was skipped. Payload: {@code {id, reason}}. */
    SKIPPED,

    /** A dynamic test was registered (e.g. {@code @TestFactory}). Payload: {@code {id, parent}}. */
    DYNAMIC_REGISTERED,

    /** A test published a {@code ReportEntry}. Payload: {@code {id, entries:{}}}. */
    REPORT,

    /** Final summary emitted once at the end. Payload: {@code {total, successful, failed, skipped, aborted, duration_ms}}. */
    PLAN_FINISHED;

    /** Lowercase wire form, e.g. {@code "plan_started"}. */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
