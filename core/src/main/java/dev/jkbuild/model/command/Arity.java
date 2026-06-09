// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model.command;

/**
 * How many values a positional parameter accepts. Mirrors the picocli arity
 * strings the commands used ({@code "0..1"}, {@code "1"}, {@code "1..*"},
 * {@code "0..*"}) without dragging in picocli.
 */
public enum Arity {
    /** Optional single value: {@code 0..1}. */
    ZERO_OR_ONE(0, 1),
    /** Exactly one required value: {@code 1}. */
    ONE(1, 1),
    /** One or more required values: {@code 1..*}. */
    ONE_OR_MORE(1, Integer.MAX_VALUE),
    /** Zero or more values: {@code 0..*}. */
    ZERO_OR_MORE(0, Integer.MAX_VALUE);

    private final int min;
    private final int max;

    Arity(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public boolean required() {
        return min > 0;
    }

    public boolean variadic() {
        return max > 1;
    }
}
