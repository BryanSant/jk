// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

/**
 * Shared chrome for the build goal line and its settled result lines, so the live
 * header ({@link CommandManager#goalHeader}) and the buffered plain-scheduler
 * println render identically.
 *
 * <p>The line is a powerline chip: {@code  ✓ Build } painted on a colored chip,
 * closed by a U+E0B0 cap whose <em>foreground</em> is the chip color (so the cap's
 * solid body continues the chip) and whose <em>background</em> is left unset (so the
 * cap tapers the chip into whatever follows — the bar on the live line, the verb on a
 * result line). Without a Nerd Font there is no chip background and no cap: the glyph
 * and verb are simply colored.
 */
public final class GoalChrome {

    private GoalChrome() {}

    /** {@code " {glyph} {name} "} painted on {@code chip}. A leading + trailing space pad the pill. */
    static String chip(String glyph, String name, AttributedStyle chip) {
        String text = " " + glyph + (name.isEmpty() ? "" : " " + name) + " ";
        return Theme.colorize(text, chip);
    }

    /**
     * The U+E0B0 cap closing a chip: foreground = {@code chipColor} (continuing the
     * chip body), background unset (tapering into what follows). Empty when {@code
     * nerdfont} is off — the plain line needs no cap.
     */
    static String cap(Rgb chipColor, boolean nerdfont) {
        return nerdfont ? Theme.colorize(Glyphs.SEGMENT_END_NERD, Theme.active().bright(chipColor)) : "";
    }

    /** Settled success: {@code  ✓ Build ▶ Successfully <tail>} (tail pre-styled by the caller). */
    public static String successLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        String verb = Theme.colorize("Successfully", t.success());
        if (nerdfont) {
            return chip(Glyphs.CHECK, name, t.goalSuccessChip())
                    + cap(t.goalChipColor(), true) + " " + verb + " " + tail;
        }
        String head = Glyphs.CHECK + (name.isEmpty() ? "" : " " + name) + " Successfully";
        return Theme.colorize(head, t.success()) + " " + tail;
    }

    /** Settled failure: {@code  ‼ Build ▶ Failure <tail>} (tail pre-styled by the caller). */
    public static String failureLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        String verb = Theme.colorize("Failure", t.error());
        if (nerdfont) {
            return chip(Glyphs.CROSS, name, t.goalFailureChip())
                    + cap(t.goalFailColor(), true) + " " + verb + " " + tail;
        }
        String head = Glyphs.CROSS + (name.isEmpty() ? "" : " " + name) + " Failure";
        return Theme.colorize(head, t.error()) + " " + tail;
    }
}
