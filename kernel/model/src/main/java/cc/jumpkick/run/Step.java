// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import cc.jumpkick.plugin.build.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * One unit of work inside a {@link Pipeline}. Steps declare their dependencies by name and run when
 * their prerequisites finish.
 *
 * <p>Steps are immutable after construction. Use {@link Step#builder} to assemble one.
 */
public final class Step {

    private final String name;
    private final String label;
    private final StepKind kind;
    private final List<String> requires;
    private final IntSupplier ticks;
    private final IntSupplier weight; // null → weight tracks ticks (legacy behaviour)
    private final boolean interpolated;
    private final Phase phase; // nullable — the coarse pipeline stage this step belongs to
    private final Body body;

    Step(
            String name,
            String label,
            StepKind kind,
            List<String> requires,
            IntSupplier ticks,
            IntSupplier weight,
            boolean interpolated,
            Phase phase,
            Body body) {
        this.name = Objects.requireNonNull(name);
        this.label = label != null ? label : name;
        this.kind = Objects.requireNonNull(kind);
        this.requires = List.copyOf(requires);
        this.ticks = Objects.requireNonNull(ticks);
        this.weight = weight;
        this.interpolated = interpolated;
        this.phase = phase;
        this.body = Objects.requireNonNull(body);
    }

    /** The coarse pipeline {@link Phase} this step belongs to, if declared. */
    public Optional<Phase> phase() {
        return Optional.ofNullable(phase);
    }

    public String name() {
        return name;
    }

    /** Display label shown in the TUI progress bar. Defaults to {@link #name()}. */
    public String label() {
        return label;
    }

    public StepKind kind() {
        return kind;
    }

    public List<String> requires() {
        return requires;
    }

    /**
     * Internal unit count — how granularly this step ticks (sources, artifacts, tests). Drives the
     * within-step fraction, <em>not</em> the share of the bar the step occupies; see {@link
     * #estimateWeight}.
     */
    public int estimateTicks() {
        return Math.max(0, ticks.getAsInt());
    }

    /** True when a {@link Builder#weight} was set, so the step self-weights. */
    public boolean hasExplicitWeight() {
        return weight != null;
    }

    /**
     * The step's share of the progress bar — a time-proportional cost, not a unit count. The pipeline's
     * denominator sums these, and a step's own 0→100% (its {@link #estimateTicks} internal progress)
     * is scaled into this many ticks. Defaults to {@link #estimateTicks} when no weight was set, so
     * pipelines that don't opt in keep counting units exactly as before.
     */
    public int estimateWeight() {
        return Math.max(0, weight != null ? weight.getAsInt() : ticks.getAsInt());
    }

    /**
     * True when the scheduler should ease this step's bar slice forward over elapsed time while it
     * runs, rather than leaving it flat until the body reports progress. Use only for <em>opaque</em>
     * steps (a single black-box call like javac) — steps that already report fine-grained progress
     * (per-artifact, per-test, per-stage) must leave this off, or a too-short time estimate would
     * race the bar ahead and then stall.
     */
    public boolean interpolated() {
        return interpolated;
    }

    public boolean async() {
        return kind != StepKind.SYNC;
    }

    /** Step body — runs on whatever thread the scheduler dispatched it on. */
    public void execute(StepContext ctx) throws Exception {
        body.run(ctx);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Functional shape of {@link Step#execute}; {@code Exception} → fail. */
    @FunctionalInterface
    public interface Body {
        void run(StepContext ctx) throws Exception;
    }

    public static final class Builder {
        private final String name;
        private String label;
        private StepKind kind = StepKind.SYNC;
        private final List<String> requires = new ArrayList<>();
        private IntSupplier ticks = () -> 1;
        private IntSupplier weight = null; // null → weight tracks ticks
        private boolean interpolated = false;
        private Phase phase = null;
        private Body body = ctx -> {};

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /** Override the TUI display label (defaults to the step name). */
        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder kind(StepKind kind) {
            this.kind = kind;
            return this;
        }

        /** Run after the named step(s) finish successfully. */
        public Builder requires(String... names) {
            for (String n : names) requires.add(n);
            return this;
        }

        /**
         * Cheap up-front size estimate. Called once before the pipeline starts. Use {@link
         * StepContext#updateTicks} during execution if it turns out the estimate was low.
         */
        public Builder ticks(IntSupplier supplier) {
            this.ticks = supplier;
            return this;
        }

        /** Fixed ticks — equivalent to {@code ticks(() -> n)}. */
        public Builder ticks(int n) {
            this.ticks = () -> n;
            return this;
        }

        /**
         * Set the step's share of the progress bar — a time-proportional cost, independent of its
         * {@link #ticks} unit count. Use this to keep a file-count- or test-count-scoped step from
         * dominating the bar: e.g. a compile over 300 sources and a 5-test run can each be weighted by
         * their expected duration so the bar paces by time, not by raw counts. When unset, the weight
         * tracks the ticks (legacy behaviour).
         */
        public Builder weight(IntSupplier supplier) {
            this.weight = supplier;
            return this;
        }

        /** Fixed weight — equivalent to {@code weight(() -> n)}. */
        public Builder weight(int n) {
            this.weight = () -> n;
            return this;
        }

        /**
         * Ease this step's bar slice forward over elapsed time while it runs. For opaque steps only —
         * see {@link Step#interpolated()}.
         */
        public Builder interpolated() {
            this.interpolated = true;
            return this;
        }

        /** Declare the coarse pipeline {@link Phase} this step belongs to. */
        public Builder phase(Phase phase) {
            this.phase = phase;
            return this;
        }

        public Builder execute(Body body) {
            this.body = body;
            return this;
        }

        public Step build() {
            return new Step(name, label, kind, requires, ticks, weight, interpolated, phase, body);
        }
    }
}
