// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import cc.jumpkick.plugin.build.Phase;
import java.time.Duration;

/**
 * Callbacks the Pipeline scheduler emits as steps progress. Every method has a no-op default —
 * implementations override only the ones they care about (a JSON emitter cares about all events; a
 * quiet log listener cares only about {@code pipelineFinish}).
 *
 * <p>Threading: listeners are invoked on whatever thread emitted the event. For async steps that
 * means a worker thread; for sync steps the pipeline's caller thread. Listeners that need ordered
 * access to shared state (terminal rendering, file writes) must synchronise internally.
 *
 * <p>Order guarantees: within a single step, events are ordered as emitted. Across steps, no
 * ordering is guaranteed beyond {@code stepStart} preceding any of that step's other events and
 * {@code stepFinish} following them.
 */
public interface PipelineListener {

    default void pipelineStart(PipelineView view) {}

    /**
     * A step began. {@code phase} is the coarse {@link Phase} this step belongs to (nullable when the
     * step declares none); its place in the run hierarchy is {@code pipeline/phase/step}.
     */
    default void stepStart(String step, Phase phase, int ticks) {}

    default void progress(String step, int delta, PipelineView view) {}

    default void tickUpdate(String step, int delta, PipelineView view) {}

    default void label(String step, String label) {}

    /**
     * Free-form passthrough output line from a step (forwarded subprocess stdout under {@code
     * --verbose}, a provisioning notice, …). Renderers print it above any pinned progress region;
     * structured listeners record it as an output event.
     */
    default void output(String step, String line) {}

    default void warn(String step, String code, String message) {}

    default void error(String step, String code, String message) {}

    /**
     * Error carrying discrete {@code test} / {@code exceptionClass} detail (see {@link
     * StepContext#error(String, String, String, String)}). Defaults to the plain {@link
     * #error(String, String, String)} so listeners that don't model the structured form still receive
     * the diagnostic with its message.
     */
    default void error(String step, String code, String message, String test, String exceptionClass) {
        error(step, code, message);
    }

    default void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {}

    default void pipelineFinish(PipelineResult result) {}
}
