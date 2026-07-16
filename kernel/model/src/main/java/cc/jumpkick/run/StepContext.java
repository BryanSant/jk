// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

/**
 * Handle a step uses to talk back to the Pipeline. Every method is safe to call from a worker thread
 * (async steps) — the underlying counters use atomic adds and the listener fanout is the
 * framework's responsibility.
 *
 * <p>Steps should:
 *
 * <ul>
 *   <li>Call {@link #progress} as units of work complete (a file compiled, a dep fetched, a byte
 *       downloaded — whatever the step's ticks unit is).
 *   <li>Call {@link #updateTicks} when the initial estimate turns out too low (e.g. resolving
 *       uncovered more transitive deps). The Pipeline's denominator grows; the progress bar grows
 *       accordingly.
 *   <li>Call {@link #label} when the active "what am I doing right now" sub-task changes. The TUI
 *       shows this beside the bar.
 *   <li>Poll {@link #cancelled} in inner loops so the Pipeline can cooperatively shut down on a failure
 *       or Ctrl-C.
 *   <li>Call {@link #warn} / {@link #error} for structured diagnostics — the Pipeline report groups
 *       these by step.
 * </ul>
 */
public interface StepContext {

    /** Add {@code delta} to the pipeline's progress numerator. */
    void progress(int delta);

    /**
     * Grow the step's ticks (and therefore the pipeline's denominator) by {@code additional}. Use
     * when the {@link Step#estimateTicks} up-front guess proves too low.
     */
    void updateTicks(int additional);

    /**
     * Replace this step's bar weight (its share of the pipeline denominator) once the real work is known
     * — e.g. a compile that, with its resolved classpath in hand, discovers it will hard-link a
     * cached result rather than run a full javac. The pipeline denominator is adjusted by the delta,
     * re-normalising the bar. Call this <em>early</em> in the step body, before reporting progress,
     * so the slice is resized before it starts filling (a late shrink below already-emitted ticks
     * pulls the bar back slightly). A no-op for unweighted steps and for implementations that don't
     * size dynamically.
     */
    default void reweight(int newWeight) {
        /* dynamic-weight steps override via DefaultStepContext */
    }

    /**
     * Set the step's current sub-task label — what's happening right now. The TUI uses this for the
     * "Compiling Foo.java" annotation beside the bar; logging listeners may write it as a status
     * line. Pass {@code null} or empty to clear.
     */
    void label(String description);

    /**
     * Emit a free-form output line from the step (or a subprocess it's shepherding) for the view to
     * surface — e.g. forwarded test-process stdout under {@code --verbose}, or a one-off provisioning
     * notice. The TUI prints it above the pinned region; logging listeners write it as a plain line.
     * Unlike {@link #label}, this is durable passthrough output, not a transient status. Steps must
     * route user-facing text here rather than touching {@code System.out}/{@code System.err} directly
     * — only the CLI view layer owns those streams.
     */
    void output(String line);

    /**
     * Signal that this step did no real work — its outputs were already up-to-date / served from the
     * cache (CAS or action cache). The scheduler then records the step as {@link StepStatus#SKIPPED}
     * instead of {@code SUCCESS}, which counts toward the dashboard's per-project cache-hit ratio
     * ("steps skipped") without affecting the build's overall success. Idempotent — the flag simply
     * sticks once set, so a step that discovers late it did do work should just not call this.
     */
    default void cached() {}

    /** Accumulating warning. Surfaces in the run report. */
    void warn(String code, String message);

    /**
     * Accumulating error. Does NOT terminate the step — steps signal fatal failure by throwing from
     * {@link Step#execute}. Use {@code error} for non-fatal-but-important issues that should still
     * surface in the report (e.g. one file in a batch failed but the batch continued).
     */
    void error(String code, String message);

    /**
     * As {@link #error(String, String)}, but carrying the discrete {@code test} (failing test display
     * name) and {@code exceptionClass} detail of a test failure so structured consumers keep them
     * separate instead of reading them back out of one glued message. Defaults to dropping the extra
     * fields onto {@link #error(String, String)} for implementations that don't model the structured
     * form.
     */
    default void error(String code, String message, String test, String exceptionClass) {
        error(code, message);
    }

    /**
     * True if the pipeline has been cancelled (sibling failure or Ctrl-C). Long-running steps should
     * check this in their inner loops and exit promptly when set; the scheduler will hard-interrupt
     * after a 200ms grace period if a step doesn't shut down cooperatively.
     */
    boolean cancelled();

    /**
     * Stash {@code value} in the pipeline's shared state under {@code key}. Visible to every subsequent
     * step via {@link #get} / {@link #require}. Thread-safe; concurrent puts to the same key may
     * overwrite each other, but that's a step-design problem — keys should be owned by a single
     * producer.
     */
    <T> void put(PipelineKey<T> key, T value);

    /**
     * Read a previously-stashed value. Returns empty when the key has never been {@link #put}; the
     * optional shape makes step authors decide explicitly whether a missing value is OK to handle.
     */
    <T> java.util.Optional<T> get(PipelineKey<T> key);

    /**
     * Read a value the step author knows must be present (an upstream step {@code require}-ed by
     * this one populated it). Throws {@link IllegalStateException} when missing — that's a
     * programming error, not a runtime condition.
     */
    <T> T require(PipelineKey<T> key);
}
