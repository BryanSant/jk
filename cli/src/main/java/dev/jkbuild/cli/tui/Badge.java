// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Theme;

/**
 * A small "chip" / "pill" label — black text on a bright-black background, used
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

    public static String pill(String label, boolean nerdfont) {
        Theme t = Theme.active();
        if (nerdfont) {
            return Theme.colorize(Glyphs.PILL_LEFT_NERD, t.darkGray())
                    + Theme.colorize(label, t.scopeBadge())
                    + Theme.colorize(Glyphs.PILL_RIGHT_NERD, t.darkGray());
        }
        return Theme.colorize(" " + label + " ", t.scopeBadge());
    }
}
