// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Color and style helpers used by Rail and Wizard. All static. Truecolor RGB
 * everywhere; terminals without 24-bit color degrade to the nearest indexed
 * color via JLine's renderer.
 *
 * <p>Honors the <a href="https://no-color.org/">NO_COLOR</a> convention:
 * when the {@code NO_COLOR} environment variable is set to a non-empty
 * value, all foreground colors are stripped. Text attributes (bold,
 * italic, faint) survive — NO_COLOR is specifically about color.
 */
public final class Theme {

    /** True when the user has requested colorless output via the NO_COLOR env var. */
    private static final boolean NO_COLOR = noColorRequested();

    private Theme() {}

    private static boolean noColorRequested() {
        var v = System.getenv("NO_COLOR");
        return v != null && !v.isEmpty();
    }

    // Gradient endpoints: magenta to blue.
    private static final int GRAD_START_R = 0xd9;
    private static final int GRAD_START_G = 0x46;
    private static final int GRAD_START_B = 0xef;
    private static final int GRAD_END_R = 0x3b;
    private static final int GRAD_END_G = 0x82;
    private static final int GRAD_END_B = 0xf6;

    // Active rail / bullet: cyan #22d3ee.
    private static final int ACTIVE_R = 0x22;
    private static final int ACTIVE_G = 0xd3;
    private static final int ACTIVE_B = 0xee;

    // Completed bullet / selected option: green #22c55e.
    private static final int OK_R = 0x22;
    private static final int OK_G = 0xc5;
    private static final int OK_B = 0x5e;

    /** Error / cancellation red #ef4444. */
    private static final int ERR_R = 0xef;
    private static final int ERR_G = 0x44;
    private static final int ERR_B = 0x44;

    /** Attention / highlight yellow #facc15 — used for "default" status, etc. */
    private static final int WARN_R = 0xfa;
    private static final int WARN_G = 0xcc;
    private static final int WARN_B = 0x15;

    /** Apply a foreground color unless NO_COLOR is set. */
    private static AttributedStyle withColor(AttributedStyle base, int r, int g, int b) {
        return NO_COLOR ? base : base.foreground(r, g, b);
    }

    public static AttributedStyle dim() {
        return AttributedStyle.DEFAULT.faint();
    }

    /** Dark gray #4b5563; used for inactive/completed rail glyphs (┌ │ └). */
    public static AttributedStyle darkGray() {
        return withColor(AttributedStyle.DEFAULT, 0x4b, 0x55, 0x63);
    }

    public static AttributedStyle activeStep() {
        return withColor(AttributedStyle.DEFAULT, ACTIVE_R, ACTIVE_G, ACTIVE_B);
    }

    public static AttributedStyle completedStep() {
        return withColor(AttributedStyle.DEFAULT, OK_R, OK_G, OK_B);
    }

    /** Bold + bright white; used for focused option labels and the input buffer. */
    public static AttributedStyle focused() {
        return withColor(AttributedStyle.DEFAULT.bold(), 0xff, 0xff, 0xff);
    }

    /** Plain white (not bold, not dim); used for settled answer text. */
    public static AttributedStyle settled() {
        return withColor(AttributedStyle.DEFAULT, 0xff, 0xff, 0xff);
    }

    /** Red; used for inline error messages and cancellation closers. */
    public static AttributedStyle error() {
        return withColor(AttributedStyle.DEFAULT, ERR_R, ERR_G, ERR_B);
    }

    /** Bold + green; used for success-banner text. */
    public static AttributedStyle success() {
        return withColor(AttributedStyle.DEFAULT.bold(), OK_R, OK_G, OK_B);
    }

    /** Yellow; used to call attention to state like "default" / current selection. */
    public static AttributedStyle warning() {
        return withColor(AttributedStyle.DEFAULT, WARN_R, WARN_G, WARN_B);
    }

    public static AttributedStyle bright(int r, int g, int b) {
        return withColor(AttributedStyle.DEFAULT, r, g, b);
    }

    /** Per-codepoint truecolor lerp from {@code #d946ef} to {@code #3b82f6}. */
    public static AttributedString gradientHeader(String text) {
        var sb = new AttributedStringBuilder();
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        if (n == 0) {
            return sb.toAttributedString();
        }
        if (NO_COLOR) {
            // Drop the gradient entirely; bold still distinguishes the header.
            return sb.append(text, AttributedStyle.DEFAULT.bold()).toAttributedString();
        }
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            var r = (int) Math.round(GRAD_START_R + t * (GRAD_END_R - GRAD_START_R));
            var g = (int) Math.round(GRAD_START_G + t * (GRAD_END_G - GRAD_START_G));
            var b = (int) Math.round(GRAD_START_B + t * (GRAD_END_B - GRAD_START_B));
            sb.append(new String(Character.toChars(codepoints[i])), AttributedStyle.DEFAULT.bold().foreground(r, g, b));
        }
        return sb.toAttributedString();
    }

    /**
     * Wrap {@code text} in the ANSI SGR codes for {@code style} so the
     * result can be written to a {@code PrintStream} / {@code System.out}
     * verbatim. Bypasses {@link org.jline.utils.AttributedString#toAnsi()}
     * — that path rewrites single-line box-drawing glyphs to ASCII when
     * called without a terminal, which mangles widgets that use box
     * characters.
     */
    public static String colorize(String text, AttributedStyle style) {
        String sgr = style.toAnsi();
        return sgr.isEmpty() ? text : "\033[" + sgr + "m" + text + "\033[0m";
    }

    /** Maps (state, glyph) to the style used to render that rail glyph. */
    public static AttributedStyle railStyle(Rail.StepState state, Rail.RailGlyph glyph) {
        return switch (glyph) {
            case COMPLETED_BULLET -> completedStep();
            case ACTIVE_BULLET -> activeStep();
            case OPEN, MID, CLOSE -> switch (state) {
                case ACTIVE -> activeStep();
                case COMPLETED, INACTIVE -> darkGray();
            };
        };
    }
}
