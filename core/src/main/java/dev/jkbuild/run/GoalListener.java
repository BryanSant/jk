// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.time.Duration;

/**
 * Callbacks the Goal scheduler emits as phases progress. Every method
 * has a no-op default — implementations override only the ones they
 * care about (a JSON emitter cares about all events; a quiet log
 * listener cares only about {@code goalFinish}).
 *
 * <p>Threading: listeners are invoked on whatever thread emitted the
 * event. For async phases that means a worker thread; for sync phases
 * the goal's caller thread. Listeners that need ordered access to
 * shared state (terminal rendering, file writes) must synchronise
 * internally.
 *
 * <p>Order guarantees: within a single phase, events are ordered as
 * emitted. Across phases, no ordering is guaranteed beyond
 * {@code phaseStart} preceding any of that phase's other events and
 * {@code phaseFinish} following them.
 */
public interface GoalListener {

    default void goalStart(GoalView view) {}

    default void phaseStart(String phase, int scope) {}

    default void progress(String phase, int delta, GoalView view) {}

    default void scopeUpdate(String phase, int delta, GoalView view) {}

    default void label(String phase, String label) {}

    /**
     * Free-form passthrough output line from a phase (forwarded subprocess
     * stdout under {@code --verbose}, a provisioning notice, …). Renderers
     * print it above any pinned progress region; structured listeners record
     * it as an output event.
     */
    default void output(String phase, String line) {}

    default void warn(String phase, String code, String message) {}

    default void error(String phase, String code, String message) {}

    default void phaseFinish(String phase, PhaseStatus status, Duration duration) {}

    default void goalFinish(GoalResult result) {}
}
