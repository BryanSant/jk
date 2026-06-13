// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

/**
 * Single source of truth for the status/marker glyphs used across jk's
 * terminal UI. These are just the codepoints — color is applied separately
 * via the theme (e.g. {@code Theme.colorize(Glyphs.CHECK, Theme.active().success())}).
 */
public final class Glyphs {

    private Glyphs() {}

    /** Success marker — heavy check mark. Paint with {@code Theme.success()}. */
    public static final String CHECK = "✓";

    /** Failure / cancel marker — double exclamation. Paint with {@code Theme.error()}. */
    public static final String CROSS = "‼";

    /** Pending / active phase-row marker — white medium square. */
    public static final String PENDING = "◻";
}
