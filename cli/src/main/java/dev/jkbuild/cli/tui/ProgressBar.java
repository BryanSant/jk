// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Gradient;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;

/**
 * Pure, embeddable segmented progress bar — a <em>string renderer</em>, not a
 * widget. It owns no cursor, no terminal, no threads, and emits no OSC: given a
 * {@code (numerator, denominator)} it returns one line of colored text:
 *
 * <pre>
 *   ▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱▱ 45% [42 of 93]
 * </pre>
 *
 * <p>The caller (the goal-oriented {@code CommandManager} view) places this line
 * inside its multi-line live region and repaints the whole region, so this class
 * deliberately does <em>not</em> manage screen state. The single-line, cursor-
 * owning widget lives in {@link SpinnerProgressBar}.
 *
 * <p>Filled segments use the same <em>moving</em> gradient as
 * {@link SpinnerProgressBar}: the right-most filled glyph is pinned to the
 * gradient end and the band trails back toward the start, so the color looks
 * pushed rightward as the bar fills. Brackets around the {@code N of D} count
 * are bright-black; the count and percent are plain.
 */
public final class ProgressBar {

    public static final int SEGMENTS = 40;
    static final char FILLED_CHAR = '▰';
    static final char EMPTY_CHAR = '▱';

    private final AttributedStyle[] fillColors;
    /** Empty glyphs take the gradient's left-most (darkest) color, not a neutral dim. */
    private final AttributedStyle emptyStyle;

    /** Bar in the default green → bright-green progress gradient. */
    public ProgressBar() {
        this(Theme.active().progressGradient());
    }

    /** Bar in an explicit gradient (e.g. the failure gradient for a stopped run). */
    public ProgressBar(Gradient gradient) {
        this.fillColors = buildGradient(SEGMENTS, gradient);
        this.emptyStyle = fillColors[0];
    }

    /** Clamp {@code numerator / denominator} to {@code [0.0, 1.0]}. */
    public static double fraction(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        double f = (double) numerator / (double) denominator;
        return f < 0.0 ? 0.0 : Math.min(f, 1.0);
    }

    /** Number of filled segments for the given ratio. */
    public static int filled(long numerator, long denominator) {
        return (int) Math.round(fraction(numerator, denominator) * SEGMENTS);
    }

    /** Rounded percent for the given ratio. */
    public static int percent(long numerator, long denominator) {
        return (int) Math.round(fraction(numerator, denominator) * 100);
    }

    /**
     * Render one bar line for {@code (numerator, denominator)} — colored text,
     * no trailing newline and no cursor control.
     */
    public String render(long numerator, long denominator) {
        int fill = filled(numerator, denominator);
        int pct = percent(numerator, denominator);
        AttributedStyle bracket = Theme.active().darkGray();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SEGMENTS; i++) {
            boolean isFilled = i < fill;
            char c = isFilled ? FILLED_CHAR : EMPTY_CHAR;
            AttributedStyle style = isFilled ? filledColor(i, fill) : emptyStyle;
            sb.append(Theme.colorize(String.valueOf(c), style));
        }
        sb.append(' ').append(Theme.colorize(pct + "%", Theme.active().settled()));
        sb.append(' ')
                .append(Theme.colorize("[", bracket))
                .append(Theme.colorize(numerator + " of " + denominator, Theme.active().settled()))
                .append(Theme.colorize("]", bracket));
        return sb.toString();
    }

    /**
     * Color for the filled glyph at zero-based {@code i} when {@code fill}
     * glyphs are lit: the frontier maps to the gradient end and each glyph to
     * its left steps one entry back toward the start.
     */
    private AttributedStyle filledColor(int i, int fill) {
        return fillColors[SEGMENTS - fill + i];
    }

    private static AttributedStyle[] buildGradient(int n, Gradient gradient) {
        AttributedStyle[] a = new AttributedStyle[n];
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0.0 : (double) i / (n - 1);
            a[i] = Theme.active().bright(gradient.at(t));
        }
        return a;
    }
}
