// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * Color and style helpers used by Rail and Wizard. All static. Truecolor RGB
 * everywhere; terminals without 24-bit color degrade to the nearest indexed
 * color via JLine's renderer.
 *
 * <p>Color emission is decided by the user's resolved {@code --color}
 * choice in {@link ActiveConfig} (see {@link JkConfig.ColorChoice}):
 * <ul>
 *   <li>{@code ALWAYS} — emit color unconditionally.</li>
 *   <li>{@code NEVER} — strip all foreground colors. Text attributes
 *       (bold, italic, faint) survive — color choice is about color.</li>
 *   <li>{@code AUTO} (default) — emit color unless the
 *       <a href="https://no-color.org/">NO_COLOR</a> env var is set, or
 *       stdout is not attached to a terminal.</li>
 * </ul>
 */
public final class Theme {

    private Theme() {}

    /** True when foreground color should be emitted, given the resolved {@code --color} choice. */
    public static boolean colorEnabled() {
        var choice = ActiveConfig.get().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            // AUTO: emit color unless NO_COLOR is set. We don't gate on isatty —
            // many jk consumers (CI logs, `less -R`, pipes into other formatters)
            // benefit from preserved color, and users who want strictly plain
            // output can pass `--color never`.
            case AUTO -> {
                var nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }

    // --- gradients --------------------------------------------------------
    // Named gradients, each independently tunable (all from the Jk Dark scheme):
    // title bright-blue → accent; spinner primary → accent; progress green →
    // bright-green.
    /** Gradient for {@code jk init}/wizard titles — Jk Dark bright-blue → accent. */
    public static final Gradient TITLE_GRADIENT = new Gradient(JkDark.BRIGHT_BLUE, JkDark.ACCENT);
    /**
     * Gradient for the {@link ProgressBar} fill — Jk Dark green (30% darker) →
     * bright-green (10% brighter), for a punchier fill than the raw palette greens.
     */
    public static final Gradient PROGRESS_GRADIENT =
            new Gradient(JkDark.NORMAL_GREEN.darker(0.30), JkDark.BRIGHT_GREEN.brighter(0.10));
    /** Gradient for the {@link Spinner} frames — Jk Dark primary → accent. */
    public static final Gradient SPINNER_GRADIENT = new Gradient(JkDark.PRIMARY, JkDark.ACCENT);
    /** Gradient a failed progress bar repaints in: dark red #7f1d1d → bright red #ef4444. */
    public static final Gradient FAILURE_GRADIENT = new Gradient(Rgb.hex(0x7f1d1d), Rgb.hex(0xef4444));

    /** Apply a foreground color unless the resolved {@code --color} choice disables it. */
    private static AttributedStyle withColor(AttributedStyle base, int r, int g, int b) {
        return colorEnabled() ? base.foreground(r, g, b) : base;
    }

    /** {@link Rgb} overload of {@link #withColor(AttributedStyle, int, int, int)}. */
    private static AttributedStyle withColor(AttributedStyle base, Rgb c) {
        return withColor(base, c.r(), c.g(), c.b());
    }

    public static AttributedStyle dim() {
        return AttributedStyle.DEFAULT.faint();
    }

    /** Inactive/completed rail glyphs (┌ │ └) and completed step prompts — Jk Dark bright black. */
    public static AttributedStyle darkGray() {
        return withColor(AttributedStyle.DEFAULT, JkDark.BRIGHT_BLACK);
    }

    /** De-emphasised body text adjacent to bright labels — Jk Dark primary-light. */
    public static AttributedStyle normalGray() {
        return withColor(AttributedStyle.DEFAULT, JkDark.PRIMARY_LIGHT);
    }

    /** Active rail / step bullet — the highlight accent (Jk Dark accent). */
    public static AttributedStyle activeStep() {
        return withColor(AttributedStyle.DEFAULT, JkDark.ACCENT);
    }

    public static AttributedStyle completedStep() {
        return withColor(AttributedStyle.DEFAULT, JkDark.NORMAL_GREEN);
    }

    /** Bold + bright white; used for focused option labels and the input buffer. */
    public static AttributedStyle focused() {
        return withColor(AttributedStyle.DEFAULT.bold(), JkDark.BRIGHT_WHITE);
    }

    /** Plain foreground (not bold, not dim); used for settled answer text. */
    public static AttributedStyle settled() {
        return withColor(AttributedStyle.DEFAULT, JkDark.FOREGROUND);
    }

    /** Alias for {@link #settled} — plain white with no weight modifier. */
    public static AttributedStyle plainWhite() {
        return settled();
    }

    /**
     * Dark gray #4b5563 (same as the inactive rail glyphs). Used for the
     * prompt line of a step once it has been settled — the gray de-emphasises
     * the question against the focused-white text of the active step, while
     * matching the rail's color above and below.
     */
    public static AttributedStyle completedPrompt() {
        return darkGray();
    }

    /** Red; used for inline error messages and cancellation closers (distinct from the accent). */
    public static AttributedStyle error() {
        return withColor(AttributedStyle.DEFAULT, JkDark.NORMAL_RED);
    }

    /** Bold + green; used for success-banner text. */
    public static AttributedStyle success() {
        return withColor(AttributedStyle.DEFAULT.bold(), JkDark.NORMAL_GREEN);
    }

    /** Yellow; used to call attention to state like "default" / current selection. */
    public static AttributedStyle warning() {
        return withColor(AttributedStyle.DEFAULT, JkDark.NORMAL_YELLOW);
    }

    /** Blue — used elsewhere; no longer a gradient endpoint. */
    public static AttributedStyle blue() {
        return withColor(AttributedStyle.DEFAULT, JkDark.BRIGHT_BLUE);
    }

    /** Bright green — used for the settled-answer arrow and "➜" prefixes. */
    public static AttributedStyle brightGreen() {
        return withColor(AttributedStyle.DEFAULT, JkDark.BRIGHT_GREEN);
    }

    public static AttributedStyle bright(int r, int g, int b) {
        return withColor(AttributedStyle.DEFAULT, r, g, b);
    }

    /** {@link Rgb} overload of {@link #bright(int, int, int)}. */
    public static AttributedStyle bright(Rgb c) {
        return withColor(AttributedStyle.DEFAULT, c);
    }

    /** Per-codepoint truecolor lerp across {@link #TITLE_GRADIENT}, bold on each char. */
    public static AttributedString gradientHeader(String text) {
        var sb = new AttributedStringBuilder();
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        if (n == 0) {
            return sb.toAttributedString();
        }
        if (!colorEnabled()) {
            // Drop the gradient entirely; bold still distinguishes the header.
            return sb.append(text, AttributedStyle.DEFAULT.bold()).toAttributedString();
        }
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            Rgb c = TITLE_GRADIENT.at(t);
            sb.append(new String(Character.toChars(codepoints[i])),
                    AttributedStyle.DEFAULT.bold().foreground(c.r(), c.g(), c.b()));
        }
        return sb.toAttributedString();
    }

    /**
     * Same gradient as {@link #gradientHeader(String)} but returned as a raw
     * ANSI string with {@code 1;38;2;R;G;B} (bold-first truecolor) in every
     * per-codepoint SGR sequence. Use this when you need the bold attribute
     * stamped on each char's SGR — {@link AttributedString#toAnsi} emits bold
     * only on the first char's SGR and relies on persistence, which can look
     * not-bold against vivid gradient colors on some terminal renderings.
     */
    public static String gradientHeaderAnsi(String text) {
        if (text.isEmpty()) return "";
        if (!colorEnabled()) return "\033[1m" + text + "\033[0m";
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            Rgb c = TITLE_GRADIENT.at(t);
            sb.append("\033[1;38;2;").append(c.r()).append(';').append(c.g()).append(';').append(c.b()).append('m');
            sb.append(new String(Character.toChars(codepoints[i])));
        }
        sb.append("\033[0m");
        return sb.toString();
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
            case BULLET, OPEN, MID, CLOSE -> switch (state) {
                case ACTIVE -> activeStep();
                case COMPLETED, INACTIVE -> darkGray();
            };
        };
    }
}
