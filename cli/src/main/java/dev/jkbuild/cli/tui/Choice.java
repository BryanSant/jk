// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.util.function.Function;
import org.jline.utils.AttributedString;

/**
 * One option in a radio or multi-select step.
 *
 * <p>{@code hint} is an optional dark-gray suffix rendered after {@code label}
 * — useful for showing a secondary identifier (e.g., the concrete package
 * name behind a friendly vendor label) without competing visually with the
 * primary choice text.
 *
 * <p>{@code hintFn}, when non-null, computes the hint from the current
 * {@link Answers} at render time so options can reflect choices made on
 * earlier steps. {@code hintFn} wins over the static {@code hint}.
 *
 * <p>{@code richLabelFn}, when non-null, builds the choice's label as a
 * multi-style {@link AttributedString}. The {@code Boolean} argument is
 * {@code true} when this row is currently focused, so callers can vary
 * intensity (e.g. bold the focused row, ordinary weight on the rest)
 * while keeping the same colors. Set this when one label needs to mix
 * multiple foreground styles in one row.
 */
public record Choice(
        String id,
        String label,
        String hint,
        Function<Answers, String> hintFn,
        Function<Boolean, AttributedString> richLabelFn) {

    public Choice {
        if (hint == null) hint = "";
    }

    public Choice(String id, String label) {
        this(id, label, "", null, null);
    }

    public Choice(String id, String label, String hint) {
        this(id, label, hint, null, null);
    }

    public Choice(String id, String label, Function<Answers, String> hintFn) {
        this(id, label, "", hintFn, null);
    }

    /** Rich-label factory — caller supplies focused/unfocused renderings. */
    public static Choice rich(String id, String fallbackLabel, Function<Boolean, AttributedString> richLabelFn) {
        return new Choice(id, fallbackLabel, "", null, richLabelFn);
    }

    /** Rich-label factory with a hint suffix. */
    public static Choice rich(
            String id, String fallbackLabel, String hint, Function<Boolean, AttributedString> richLabelFn) {
        return new Choice(id, fallbackLabel, hint, null, richLabelFn);
    }

    /** Resolved hint at render time. Dynamic {@code hintFn} wins over static {@code hint}. */
    public String hintFor(Answers answers) {
        if (hintFn != null) {
            var v = hintFn.apply(answers);
            return v == null ? "" : v;
        }
        return hint;
    }
}
