// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * One unit of work inside a {@link Goal}. Phases declare their
 * dependencies by name and run when their prerequisites finish.
 *
 * <p>Phases are immutable after construction. Use {@link Phase#builder}
 * to assemble one.
 */
public final class Phase {

    private final String name;
    private final String label;
    private final PhaseKind kind;
    private final List<String> requires;
    private final IntSupplier scope;
    private final Body body;

    Phase(String name, String label, PhaseKind kind, List<String> requires,
          IntSupplier scope, Body body) {
        this.name = Objects.requireNonNull(name);
        this.label = label != null ? label : name;
        this.kind = Objects.requireNonNull(kind);
        this.requires = List.copyOf(requires);
        this.scope = Objects.requireNonNull(scope);
        this.body = Objects.requireNonNull(body);
    }

    public String name() { return name; }
    /** Display label shown in the TUI progress bar. Defaults to {@link #name()}. */
    public String label() { return label; }
    public PhaseKind kind() { return kind; }
    public List<String> requires() { return requires; }
    public int estimateScope() { return Math.max(0, scope.getAsInt()); }
    public boolean async() { return kind != PhaseKind.SYNC; }

    /** Phase body — runs on whatever thread the scheduler dispatched it on. */
    public void execute(PhaseContext ctx) throws Exception {
        body.run(ctx);
    }

    public static Builder builder(String name) { return new Builder(name); }

    /** Functional shape of {@link Phase#execute}; {@code Exception} → fail. */
    @FunctionalInterface
    public interface Body {
        void run(PhaseContext ctx) throws Exception;
    }

    public static final class Builder {
        private final String name;
        private String label;
        private PhaseKind kind = PhaseKind.SYNC;
        private final List<String> requires = new ArrayList<>();
        private IntSupplier scope = () -> 1;
        private Body body = ctx -> {};

        Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /** Override the TUI display label (defaults to the phase name). */
        public Builder label(String label) { this.label = label; return this; }

        public Builder kind(PhaseKind kind) { this.kind = kind; return this; }

        /** Run after the named phase(s) finish successfully. */
        public Builder requires(String... names) {
            for (String n : names) requires.add(n);
            return this;
        }

        /**
         * Cheap up-front size estimate. Called once before the goal
         * starts. Use {@link PhaseContext#updateScope} during execution
         * if it turns out the estimate was low.
         */
        public Builder scope(IntSupplier supplier) { this.scope = supplier; return this; }

        /** Fixed scope — equivalent to {@code scope(() -> n)}. */
        public Builder scope(int n) { this.scope = () -> n; return this; }

        public Builder execute(Body body) { this.body = body; return this; }

        public Phase build() {
            return new Phase(name, label, kind, requires, scope, body);
        }
    }
}
