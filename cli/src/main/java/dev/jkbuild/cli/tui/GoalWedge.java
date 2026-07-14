// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Rgb;
import dev.jkbuild.cli.theme.Theme;
import java.util.Locale;
import org.jline.utils.AttributedStyle;

/**
 * Shared chrome for the build goal line and its settled result lines, so the live header ({@link
 * CommandManager#goalHeader}) and the buffered plain-scheduler println render identically.
 *
 * <p>The line is a powerline chip: {@code ✓ Build } painted on a colored chip, closed by a U+E0B0
 * cap whose <em>foreground</em> is the chip color (so the cap's solid body continues the chip) and
 * whose <em>background</em> is left unset (so the cap tapers the chip into whatever follows — the
 * bar on the live line, the verb on a result line). Without a Nerd Font there is no chip background
 * and no cap: the glyph and verb are simply colored.
 */
public final class GoalWedge {

    private GoalWedge() {}

    /**
     * {@code " {glyph} {name} "} painted on {@code chip}. A leading + trailing space pad the pill.
     */
    static String chip(String glyph, String name, AttributedStyle chip) {
        String text = " " + glyph + (name.isEmpty() ? "" : " " + name) + " ";
        return Theme.colorize(text, chip);
    }

    /**
     * The U+E0B0 cap closing a chip: foreground = {@code chipColor} (continuing the chip body),
     * background unset (tapering into what follows). Empty when {@code nerdfont} is off — the plain
     * line needs no cap.
     */
    static String cap(Rgb chipColor, boolean nerdfont) {
        return nerdfont ? Theme.colorize(Glyphs.SEGMENT_END_NERD, Theme.active().bright(chipColor)) : "";
    }

    /**
     * A generic settled chip line: {@code ✓ Clean ▶ <message>}. The {@code glyph} + {@code verb} form
     * the chip (closed by the powerline cap); {@code message} is caller-styled and follows the cap.
     * For commands whose result reads as its own sentence (e.g. {@code jk clean}'s "Removed N files")
     * rather than the "{goal} successful" phrasing of {@link #successLine}.
     *
     * <p>No-ANSI: ASCII-only prefixes — {@code "+"} for {@link Glyphs#CHECK}, {@code "!"} for {@link
     * Glyphs#CROSS}, {@code "*"} for anything else — with a {@code ": "} separator and no color.
     */
    public static String chipLine(String glyph, String verb, boolean nerdfont, String message) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            String prefix = Glyphs.CHECK.equals(glyph) ? "+" : Glyphs.CROSS.equals(glyph) ? "!" : "*";
            return prefix + " " + verb + ": " + message;
        }
        // ✓ (done) and ▶ (running) read as positive → green chip; everything else (■ stop, spinner
        // frames, …) uses the neutral blue chip.
        boolean green = Glyphs.CHECK.equals(glyph) || Glyphs.PLAY.equals(glyph);
        var chipStyle = green ? t.goalSuccessChip() : t.goalChip();
        var capColor  = green ? t.goalChipColor()  : t.planBadgeColor();
        // Background is always applied; the powerline cap glyph is the only nerd-font difference.
        return chip(glyph, verb, chipStyle) + cap(capColor, nerdfont) + " " + message;
    }

    /** {@code group:name} with the group cyan and the name bright-cyan — for failure tails. */
    public static String coord(String coord) {
        Theme t = Theme.active();
        int i = coord.indexOf(':');
        return i < 0
                ? Theme.colorize(coord, t.coordGroup())
                : Theme.colorize(coord.substring(0, i), t.coordGroup())
                        + ":"
                        + Theme.colorize(coord.substring(i + 1), t.coordName());
    }

    /**
     * Settled failure: {@code ✘ Build ▶ Failed to build <tail>} — "Failed" in red, then "to
     * &lt;goal&gt;" (the goal name lower-cased: build, test, …) in the default color; tail pre-styled
     * by the caller.
     *
     * <p>No-ANSI: {@code "! <verb> Failed: <tail>"} — ASCII only, no color.
     */
    public static String failureLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "! " + name + " Failed: " + tail;
        }
        String verb =
                Theme.colorize("Failed", t.error()) + (name.isEmpty() ? "" : " to " + name.toLowerCase(Locale.ROOT));
        return chip(Glyphs.CROSS, name, t.goalFailureChip())
                + cap(t.goalFailColor(), nerdfont)
                + " "
                + verb
                + " "
                + tail;
    }

    /**
     * Settled failure with a caller-composed sentence, skipping the "Failed to &lt;goal&gt;"
     * derivation {@link #failureLine} does — for a result that settles by chip but whose message
     * doesn't read as "Failed to {@code <verb>}" (e.g. {@code jk run}: "Failed to run acme:api. No
     * valid main method was specified or detected"). {@code sentence} is fully pre-styled by the
     * caller, including its own leading "Failed" if wanted.
     *
     * <p>No-ANSI: {@code "! <name>: <sentence>"} — ASCII only, no color; no separate "Failed:" since
     * the caller-composed sentence already reads as one (unlike {@link #failureLine}'s {@code tail}).
     */
    public static String failureLineCustom(String name, boolean nerdfont, String sentence) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "! " + name + ": " + sentence;
        }
        return chip(Glyphs.CROSS, name, t.goalFailureChip()) + cap(t.goalFailColor(), nerdfont) + " " + sentence;
    }

    /**
     * Settled cancellation: {@code ✘ Build Canceled by user <tail>}. Reuses the failed-build chrome —
     * the same red chip (white on red) and cap as {@link #failureLine} — but reads "Canceled" (in
     * red) "by user"; {@code tail} (the {@code "took Xs"} suffix) is pre-styled by the caller.
     *
     * <p>No-ANSI: {@code "· <verb> Canceled"} — ASCII only, no color.
     */
    public static String canceledLine(String name, boolean nerdfont, String tail) {
        Theme t = Theme.active();
        if (!t.isAnsi()) {
            return "· " + name + " Canceled";
        }
        String verb = Theme.colorize("Canceled", t.error()) + " by user";
        return chip(Glyphs.CROSS, name, t.goalFailureChip())
                + cap(t.goalFailColor(), nerdfont)
                + " "
                + verb
                + " "
                + tail;
    }
}
