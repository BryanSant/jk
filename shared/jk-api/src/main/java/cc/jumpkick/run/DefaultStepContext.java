// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal {@link StepContext} the scheduler hands to each step. Wires {@code progress /
 * updateTicks / warn / error} into the pipeline's counters and listener fanout; tracks per-step ticks
 * growth so the scheduler can do the "auto-fill the bar to 100% on success" trick without
 * conflating it with the step's own reports.
 *
 * <p>Package-private — steps only see this through the {@link StepContext} interface.
 */
final class DefaultStepContext implements StepContext {

    private final String step;
    private final Pipeline pipeline;

    /**
     * Weighted steps occupy a fixed {@code weight} of bar ticks regardless of how many internal
     * units ({@code internalTicks}) they count; their 0→internalTicks progress is scaled into that
     * weight. Legacy steps (no explicit weight) keep the old 1:1 model: {@code progress} deltas hit
     * the numerator directly and {@code updateTicks} grows the pipeline denominator.
     */
    /**
     * Interpolation ceiling: a time-driven step eases up to this fraction of its weight and then
     * waits for real completion, so a too-short time estimate can't sprint the bar to 100% and stall
     * — the auto-fill on success closes the remaining sliver.
     */
    private static final double INTERP_CAP = 0.9;

    private final boolean weighted;
    private volatile long weight; // mutable: reweight() resizes the slice mid-run
    private final AtomicLong internalTicks; // fraction denominator (weighted)
    private final AtomicLong internalDone = new AtomicLong(0);
    private final AtomicLong emitted = new AtomicLong(0); // weight-ticks added so far (weighted)
    private final AtomicInteger tickGrowth = new AtomicInteger(0); // legacy denominator growth

    /** Wall-clock interpolation: 0 = off; else the step's expected duration. */
    private volatile long expectedNanos; // scaled with the weight on reweight()

    /** Set by {@link #cached()} when the step's work was served from cache / already up-to-date. */
    private volatile boolean cached;

    private final long startNanos;

    DefaultStepContext(
            String step,
            Pipeline pipeline,
            int internalTicks,
            int weight,
            boolean weighted,
            long expectedNanos,
            long startNanos) {
        this.step = step;
        this.pipeline = pipeline;
        this.weighted = weighted;
        this.weight = weight;
        this.internalTicks = new AtomicLong(internalTicks);
        this.expectedNanos = weighted ? expectedNanos : 0;
        this.startNanos = startNanos;
    }

    @Override
    public void progress(int delta) {
        if (delta <= 0) return;
        if (!weighted) {
            pipeline.numeratorRef().add(delta);
            notifyProgress(delta);
            return;
        }
        internalDone.addAndGet(delta);
        advance();
    }

    /**
     * Weighted mode: move the numerator so the step's share of the bar matches its internal progress
     * fraction ({@code internalDone / internalTicks}) × {@code weight}. Real progress can fill the
     * whole weight.
     */
    private void advance() {
        long ticks = internalTicks.get();
        long done = internalDone.get();
        long target = ticks > 0 ? Math.round(weight * Math.min(1.0, (double) done / ticks)) : (done > 0 ? weight : 0);
        advanceTo(target);
    }

    /**
     * Wall-clock interpolation tick (driven by the pipeline's scheduler for opaque steps). Eases the
     * slice toward {@code weight × elapsed/expected}, capped at {@link #INTERP_CAP}. Never moves the
     * bar past where real progress already put it — {@link #advanceTo} is monotonic — so it only
     * fills the gap an opaque step leaves while its single body call runs.
     */
    void tick(long nowNanos) {
        if (expectedNanos <= 0) return;
        double frac = Math.min(INTERP_CAP, (double) (nowNanos - startNanos) / expectedNanos);
        advanceTo(Math.round(weight * frac));
    }

    /** True when this step eases its slice forward over time. */
    boolean interpolating() {
        return expectedNanos > 0;
    }

    /** Monotonic advance of the weighted numerator to {@code target} ticks. */
    private synchronized void advanceTo(long target) {
        long diff = target - emitted.get();
        if (diff > 0) {
            emitted.addAndGet(diff);
            pipeline.numeratorRef().add(diff);
            notifyProgress((int) Math.min(Integer.MAX_VALUE, diff));
        }
    }

    /** The step's full bar budget — what auto-fill tops the numerator up to. */
    long stepBudget() {
        return weighted ? weight : internalTicks.get() + tickGrowth.get();
    }

    @Override
    public synchronized void reweight(int newWeight) {
        if (!weighted || newWeight < 0) return;
        long old = weight;
        if (newWeight == old) return;
        pipeline.denominatorRef().add(newWeight - old);
        // Keep the per-weight interpolation duration constant as the slice resizes.
        if (old > 0 && expectedNanos > 0) {
            expectedNanos = expectedNanos * newWeight / old;
        }
        weight = newWeight;
        // If we shrank below what was already emitted (reweight called late), pull
        // the numerator back so the step never exceeds its new budget. Meant to be
        // called early (emitted == 0), so this is normally a no-op.
        long over = emitted.get() - newWeight;
        if (over > 0) {
            emitted.addAndGet(-over);
            pipeline.numeratorRef().add(-over);
        }
    }

    @Override
    public void updateTicks(int additional) {
        if (additional <= 0) return;
        if (weighted) {
            // The step's bar share is fixed at `weight`; discovering more units
            // just re-bases the fraction (each unit is now worth fewer ticks). Grow
            // the internal denominator only — the pipeline denominator stays put, so the
            // bar neither grows nor jumps; advance() picks up the new ratio.
            internalTicks.addAndGet(additional);
            return;
        }
        tickGrowth.addAndGet(additional);
        // Grow the denominator only — the numerator stays where it is.
        // An older version of this method also advanced the numerator
        // proportionally to "preserve the fraction" so the bar wouldn't
        // appear to go backwards when a step discovered more work
        // mid-flight; that turned out to compound catastrophically for
        // steps that call updateTicks and progress in lockstep (each
        // iteration the proportional advance added to num while progress
        // also added to num, so num grew ~2× faster than den, producing
        // displays like "318 of 161"). Honest mid-step backtracking is
        // strictly better than nonsense counts; step-end auto-fill in
        // Pipeline.runOneStep still closes any residual gap on success.
        pipeline.denominatorRef().add(additional);
        PipelineView snap = pipeline.snapshot();
        pipeline.emit(l -> l.tickUpdate(step, additional, snap));
    }

    @Override
    public void cached() {
        this.cached = true;
    }

    /** Whether the step marked itself a cache hit / up-to-date via {@link #cached()}. */
    boolean wasCached() {
        return cached;
    }

    @Override
    public void label(String description) {
        String d = description == null ? "" : description;
        pipeline.emit(l -> l.label(step, d));
    }

    @Override
    public void output(String line) {
        String s = line == null ? "" : line;
        pipeline.emit(l -> l.output(step, s));
    }

    @Override
    public void warn(String code, String message) {
        pipeline.warningsRef().add(new PipelineResult.Diagnostic(step, code, message));
        pipeline.emit(l -> l.warn(step, code, message));
    }

    @Override
    public void error(String code, String message) {
        pipeline.errorsRef().add(new PipelineResult.Diagnostic(step, code, message));
        pipeline.emit(l -> l.error(step, code, message));
    }

    @Override
    public void error(String code, String message, String test, String exceptionClass) {
        pipeline.errorsRef().add(new PipelineResult.Diagnostic(step, code, message, test, exceptionClass));
        pipeline.emit(l -> l.error(step, code, message, test, exceptionClass));
    }

    @Override
    public boolean cancelled() {
        // Per-pipeline cancel (a sibling failure, or Ctrl-C torn down by the scheduler) OR a
        // session-level cancel signaled through the SessionCancel seam (a front-end's
        // CancelToken). The seam avoids an upward :model → :core dependency; until the engine
        // binds a probe it reads false, so the per-pipeline behavior is unchanged.
        return pipeline.cancelledRef().get() || SessionCancel.cancelled();
    }

    @Override
    public <T> void put(PipelineKey<T> key, T value) {
        // Allow null to be stored as a sentinel? Decided against — steps
        // should signal "no value" by not putting at all and downstream
        // reading via .get() returning empty.
        if (value == null) {
            pipeline.stateRef().remove(key.name());
        } else {
            pipeline.stateRef().put(key.name(), value);
        }
    }

    @Override
    public <T> java.util.Optional<T> get(PipelineKey<T> key) {
        return pipeline.get(key);
    }

    @Override
    public <T> T require(PipelineKey<T> key) {
        return pipeline.get(key)
                .orElseThrow(() -> new IllegalStateException("step '"
                        + step
                        + "' required key '"
                        + key.name()
                        + "' but it wasn't set by any upstream step"));
    }

    /**
     * Fanout for synthetic auto-fill at step end. Same shape as a regular {@link #progress} but
     * invoked by the scheduler, not by the step body.
     */
    void notifyProgress(int delta) {
        if (delta <= 0) return;
        PipelineView snap = pipeline.snapshot();
        pipeline.emit(l -> l.progress(step, delta, snap));
    }
}
