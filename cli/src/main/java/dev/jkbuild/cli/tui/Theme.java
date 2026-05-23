// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Color and style helpers used by Rail and Wizard. All static. Truecolor RGB
 * everywhere; terminals without 24-bit color degrade to the nearest indexed
 * color via JLine's renderer.
 */
public final class Theme {

    private Theme() {}

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

    public static AttributedStyle dim() {
        return AttributedStyle.DEFAULT.faint();
    }

    public static AttributedStyle activeStep() {
        return AttributedStyle.DEFAULT.foreground(ACTIVE_R, ACTIVE_G, ACTIVE_B);
    }

    public static AttributedStyle completedStep() {
        return AttributedStyle.DEFAULT.foreground(OK_R, OK_G, OK_B);
    }

    /** Bold + bright white; used for focused option labels and the input buffer. */
    public static AttributedStyle focused() {
        return AttributedStyle.DEFAULT.bold().foreground(0xff, 0xff, 0xff);
    }

    public static AttributedStyle bright(int r, int g, int b) {
        return AttributedStyle.DEFAULT.foreground(r, g, b);
    }

    /** Per-codepoint truecolor lerp from {@code #d946ef} to {@code #3b82f6}. */
    public static AttributedString gradientHeader(String text) {
        var sb = new AttributedStringBuilder();
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        if (n == 0) {
            return sb.toAttributedString();
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

    /** Maps (state, glyph) to the style used to render that rail glyph. */
    public static AttributedStyle railStyle(Rail.StepState state, Rail.RailGlyph glyph) {
        return switch (glyph) {
            case COMPLETED_BULLET -> completedStep();
            case ACTIVE_BULLET -> activeStep();
            case OPEN, MID, CLOSE -> switch (state) {
                case ACTIVE -> activeStep();
                case COMPLETED, INACTIVE -> dim();
            };
        };
    }
}
