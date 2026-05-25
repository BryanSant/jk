// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedString;

import java.util.function.Function;

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
 * <p>{@code richLabel}, when non-null, is rendered <em>verbatim</em> as the
 * choice's label — bypassing the wizard's usual focused/dim recolor pass
 * so callers can mix multiple styles in one row (e.g. a bold-yellow source
 * tag followed by bold-white identifier followed by gray metadata).
 * Focus is still conveyed by the checkbox/radio glyph color, which the
 * wizard owns independently of the label content.
 */
public record Choice(
        String id,
        String label,
        String hint,
        Function<Answers, String> hintFn,
        AttributedString richLabel) {

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

    /** Rich-label constructor — bypass the uniform style pass. */
    public Choice(String id, AttributedString richLabel) {
        this(id, richLabel.toString(), "", null, richLabel);
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
