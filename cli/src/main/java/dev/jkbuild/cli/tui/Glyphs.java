// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

/**
 * Single source of truth for the status/marker glyphs used across jk's terminal UI. These are just
 * the codepoints — color is applied separately via the theme (e.g. {@code
 * Theme.colorize(Glyphs.CHECK, Theme.active().success())}).
 */
public final class Glyphs {

    private Glyphs() {}

    /** Success marker — heavy check mark. Paint with {@code Theme.success()}. */
    public static final String CHECK = "✓";

    /** Error marker — U+2718 heavy ballot X. Paint with {@code Theme.error()}. */
    public static final String CROSS = "✘";

    /** Warning marker — U+203C double exclamation. Paint with {@code Theme.warning()}. */
    public static final String BANG = "‼";

    /** Pending / active phase-row marker — white square (Neutral East Asian Width; avoids the
     * Ambiguous-width medium square, which some terminal/font combos render double-wide). */
    public static final String PENDING = "□";

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

    /** Nerd Font powerline right-pointing segment terminator / arrow (U+E0B0). */
    public static final String SEGMENT_END_NERD = "";

    /** Nerd Font powerline left-pointing segment terminator / arrow (U+E0B2). */
    public static final String SEGMENT_BACK_NERD = "";
}
