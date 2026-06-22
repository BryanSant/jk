// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

import java.util.Locale;

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

    /**
     * A generic settled chip line: {@code  ✓ Clean ▶ <message>}. The {@code glyph} +
     * {@code verb} form the green chip (closed by the powerline cap); {@code message}
     * is caller-styled and follows the cap. For commands whose result reads as its own
     * sentence (e.g. {@code jk clean}'s "Removed N files") rather than the "{goal}
     * successful" phrasing of {@link #successLine}.
     */
    public static String chipLine(String glyph, String verb, boolean nerdfont, String message) {
        Theme t = Theme.active();
        if (nerdfont) {
            return chip(glyph, verb, t.goalSuccessChip()) + cap(t.goalChipColor(), true) + " " + message;
        }
        return Theme.colorize(glyph + " " + verb, t.success()) + " " + message;
    }

    /**
     * Settled success: {@code  ✓ Build ▶ Build successful for N modules took T} — the
     * "{goal} successful" phrase in green. The {@code tail} is pre-styled by the caller
     * and carries its own leading separator (a {@code " for …"} or a {@code ", …"}), so
     * it abuts the verb directly.
     */
    public static String successLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        String goal = name.isEmpty() ? "Build" : name;
        String verb = Theme.colorize(goal + " successful", t.success());
        if (nerdfont) {
            return chip(Glyphs.CHECK, name, t.goalSuccessChip())
                    + cap(t.goalChipColor(), true) + " " + verb + tail;
        }
        return Theme.colorize(Glyphs.CHECK, t.success()) + " " + verb + tail;
    }

    /**
     * Settled failure: {@code  ‼ Build ▶ Failed to build <tail>} — "Failed" in red, then
     * "to &lt;goal&gt;" (the goal name lower-cased: build, test, …) in the default color;
     * tail pre-styled by the caller.
     */
    public static String failureLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        String verb = Theme.colorize("Failed", t.error())
                + (name.isEmpty() ? "" : " to " + name.toLowerCase(Locale.ROOT));
        if (nerdfont) {
            return chip(Glyphs.CROSS, name, t.goalFailureChip())
                    + cap(t.goalFailColor(), true) + " " + verb + " " + tail;
        }
        String head = Theme.colorize(Glyphs.CROSS + (name.isEmpty() ? "" : " " + name), t.error());
        return head + " " + verb + " " + tail;
    }
}
