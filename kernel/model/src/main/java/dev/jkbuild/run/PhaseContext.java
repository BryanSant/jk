// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

/**
 * Handle a phase uses to talk back to the Goal. Every method is safe to call from a worker thread
 * (async phases) — the underlying counters use atomic adds and the listener fanout is the
 * framework's responsibility.
 *
 * <p>Phases should:
 *
 * <ul>
 *   <li>Call {@link #progress} as units of work complete (a file compiled, a dep fetched, a byte
 *       downloaded — whatever the phase's scope unit is).
 *   <li>Call {@link #updateScope} when the initial estimate turns out too low (e.g. resolving
 *       uncovered more transitive deps). The Goal's denominator grows; the progress bar grows
 *       accordingly.
 *   <li>Call {@link #label} when the active "what am I doing right now" sub-task changes. The TUI
 *       shows this beside the bar.
 *   <li>Poll {@link #cancelled} in inner loops so the Goal can cooperatively shut down on a failure
 *       or Ctrl-C.
 *   <li>Call {@link #warn} / {@link #error} for structured diagnostics — the Goal report groups
 *       these by phase.
 * </ul>
 */
public interface PhaseContext {

    /** Add {@code delta} to the goal's progress numerator. */
    void progress(int delta);

    /**
     * Grow the phase's scope (and therefore the goal's denominator) by {@code additionalScope}. Use
     * when the {@link Phase#estimateScope} up-front guess proves too low.
     */
    void updateScope(int additionalScope);

    /**
     * Replace this phase's bar weight (its share of the goal denominator) once the real work is known
     * — e.g. a compile that, with its resolved classpath in hand, discovers it will hard-link a
     * cached result rather than run a full javac. The goal denominator is adjusted by the delta,
     * re-normalising the bar. Call this <em>early</em> in the phase body, before reporting progress,
     * so the slice is resized before it starts filling (a late shrink below already-emitted ticks
     * pulls the bar back slightly). A no-op for unweighted phases and for implementations that don't
     * size dynamically.
     */
    default void reweight(int newWeight) {
        /* dynamic-weight phases override via DefaultPhaseContext */
    }

    /**
     * Set the phase's current sub-task label — what's happening right now. The TUI uses this for the
     * "Compiling Foo.java" annotation beside the bar; logging listeners may write it as a status
     * line. Pass {@code null} or empty to clear.
     */
    void label(String description);

    /**
     * Emit a free-form output line from the phase (or a subprocess it's shepherding) for the view to
     * surface — e.g. forwarded test-process stdout under {@code --verbose}, or a one-off provisioning
     * notice. The TUI prints it above the pinned region; logging listeners write it as a plain line.
     * Unlike {@link #label}, this is durable passthrough output, not a transient status. Phases must
     * route user-facing text here rather than touching {@code System.out}/{@code System.err} directly
     * — only the CLI view layer owns those streams.
     */
    void output(String line);

    /**
     * Signal that this phase did no real work — its outputs were already up-to-date / served from the
     * cache (CAS or action cache). The scheduler then records the phase as {@link PhaseStatus#SKIPPED}
     * instead of {@code SUCCESS}, which counts toward the dashboard's per-project cache-hit ratio
     * ("phases skipped") without affecting the build's overall success. Idempotent — the flag simply
     * sticks once set, so a phase that discovers late it did do work should just not call this.
     */
    default void cached() {}

    /** Accumulating warning. Surfaces in the run report. */
    void warn(String code, String message);

    /**
     * Accumulating error. Does NOT terminate the phase — phases signal fatal failure by throwing from
     * {@link Phase#execute}. Use {@code error} for non-fatal-but-important issues that should still
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
     * True if the goal has been cancelled (sibling failure or Ctrl-C). Long-running phases should
     * check this in their inner loops and exit promptly when set; the scheduler will hard-interrupt
     * after a 200ms grace period if a phase doesn't shut down cooperatively.
     */
    boolean cancelled();

    /**
     * Stash {@code value} in the goal's shared state under {@code key}. Visible to every subsequent
     * phase via {@link #get} / {@link #require}. Thread-safe; concurrent puts to the same key may
     * overwrite each other, but that's a phase-design problem — keys should be owned by a single
     * producer.
     */
    <T> void put(GoalKey<T> key, T value);

    /**
     * Read a previously-stashed value. Returns empty when the key has never been {@link #put}; the
     * optional shape makes phase authors decide explicitly whether a missing value is OK to handle.
     */
    <T> java.util.Optional<T> get(GoalKey<T> key);

    /**
     * Read a value the phase author knows must be present (an upstream phase {@code require}-ed by
     * this one populated it). Throws {@link IllegalStateException} when missing — that's a
     * programming error, not a runtime condition.
     */
    <T> T require(GoalKey<T> key);
}
