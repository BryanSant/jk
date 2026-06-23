// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

/**
 * A small "chip" / "pill" label — black text on a light-gray background, used
 * for {@code jk tree}'s scope sections and {@code jk explain}'s unit indices so
 * the two read consistently.
 *
 * <p>With a Nerd Font ({@code [global].nerdfont = true}) the chip is rounded into
 * a pill: powerline half-circle caps (drawn in the chip's background color) flank
 * the bare label. Without one, the label is space-padded to give the chip width
 * (the plain Unicode half-circles don't render well, so there are no caps).
 */
public final class Badge {

    private Badge() {}

    /** The shared light-gray scope/index chip. */
    public static String pill(String label, boolean nerdfont) {
        Theme t = Theme.active();
        return pill(label, nerdfont, t.scopeBadge(), t.plainWhite());
    }

    /**
     * A chip styled with {@code body} (its background defines the chip color); the
     * Nerd Font pill caps are painted with {@code caps} — pass a style whose
     * <em>foreground</em> matches the chip's background so they read as rounded edges.
     */
    public static String pill(String label, boolean nerdfont, AttributedStyle body, AttributedStyle caps) {
        if (nerdfont) {
            return Theme.colorize(Glyphs.PILL_LEFT_NERD, caps)
                    + Theme.colorize(label, body)
                    + Theme.colorize(Glyphs.PILL_RIGHT_NERD, caps);
        }
        return Theme.colorize(" " + label + " ", body);
    }
}
