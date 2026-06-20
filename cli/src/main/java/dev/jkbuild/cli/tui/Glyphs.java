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

    /** Run/exec marker — right-pointing triangle. Paint with {@code Theme.brightGreen()}. */
    public static final String PLAY = "▶";

    // Nerd Font powerline pill caps for badges (gated on [global].nerdfont).
    // Paint the cap in the badge's *background* color (as foreground) so it reads
    // as the chip's rounded edge. Without a Nerd Font there's no good half-circle,
    // so badges fall back to a plain padded chip (no caps).
    /** Nerd Font powerline left solid half-circle (U+E0B6). */
    public static final String PILL_LEFT_NERD = "";
    /** Nerd Font powerline right solid half-circle (U+E0B4). */
    public static final String PILL_RIGHT_NERD = "";
}
