// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.theme;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.tui.Rail;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/**
 * Color and style provider used by the TUI widgets, the goal runner, and the
 * help/command renderers. Implementations map jk's semantic roles (error,
 * success, active accent, gradients, …) onto truecolor {@link AttributedStyle}s.
 * Terminals without 24-bit color degrade to the nearest indexed color via
 * JLine's renderer.
 *
 * <p>The active theme is resolved through the static {@link #active()} accessor;
 * call sites pull styles via {@code Theme.active().error()} etc. The two
 * theme-independent helpers — {@link #colorEnabled()} and
 * {@link #colorize(String, AttributedStyle)} — remain static on this interface.
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
public interface Theme {

    // --- style getters ----------------------------------------------------

    AttributedStyle dim();

    /** Inactive/completed rail glyphs (┌ │ └) and completed step prompts — Jk Dark bright black. */
    AttributedStyle darkGray();

    /** De-emphasised body text adjacent to bright labels — Jk Dark primary-light. */
    AttributedStyle normalGray();

    /** Active rail / step bullet — the highlight accent (Jk Dark accent). */
    AttributedStyle activeStep();

    AttributedStyle completedStep();

    /** Bold + bright white; used for focused option labels and the input buffer. */
    AttributedStyle focused();

    /** Plain foreground (not bold, not dim); used for settled answer text. */
    AttributedStyle settled();

    /** Alias for {@link #settled} — plain white with no weight modifier. */
    AttributedStyle plainWhite();

    /**
     * Dark gray (same as the inactive rail glyphs). Used for the prompt line of
     * a step once it has been settled — the gray de-emphasises the question
     * against the focused-white text of the active step, while matching the
     * rail's color above and below.
     */
    AttributedStyle completedPrompt();

    /** Red; used for inline error messages and cancellation closers (distinct from the accent). */
    AttributedStyle error();

    /** Bold + green; used for success-banner text. */
    AttributedStyle success();

    /** Yellow; used to call attention to state like "default" / current selection. */
    AttributedStyle warning();

    /** Blue — used elsewhere; no longer a gradient endpoint. */
    AttributedStyle blue();

    /** Indigo primary — the brand base color (Jk Dark primary). */
    AttributedStyle primary();

    /** Cyan — used to label structural keys like scopes (Jk Dark cyan). */
    AttributedStyle cyan();

    /** Bright green — used for the settled-answer arrow and "➜" prefixes. */
    AttributedStyle brightGreen();

    AttributedStyle bright(int r, int g, int b);

    /** {@link Rgb} overload of {@link #bright(int, int, int)}. */
    AttributedStyle bright(Rgb c);

    // --- help-semantic styles --------------------------------------------

    /** Section heading in help output — bold accent. */
    AttributedStyle sectionHeading();

    /** Command name in help output — bold cyan. */
    AttributedStyle commandName();

    /** Parameter/option label in help output — cyan. */
    AttributedStyle paramLabel();

    /** Inline highlight in help output — yellow. */
    AttributedStyle highlight();

    /** Error label/prefix — bold red. */
    AttributedStyle errorLabel();

    /**
     * The {@code tip:} suggestion accent in error blocks, as a raw SGR parameter
     * body (no {@code ESC[} / {@code m}). This is a legacy 16-color accent
     * (bright green) that does not round-trip byte-identically through
     * {@link AttributedStyle#toAnsi()}, so it is sourced as a literal body here
     * — keeping the color choice in the theme layer, not in the renderer.
     */
    String tip();

    /**
     * The {@code --help} hint accent in error blocks, as a raw SGR parameter
     * body (no {@code ESC[} / {@code m}). A legacy 16-color accent (bold bright
     * cyan); see {@link #tip()} for why it is a literal body rather than an
     * {@link AttributedStyle}.
     */
    String helpHint();

    // --- gradients --------------------------------------------------------

    /** Gradient for {@code jk init}/wizard titles. */
    Gradient titleGradient();

    /** Gradient for the progress-bar fill. */
    Gradient progressGradient();

    /** Gradient for the spinner frames. */
    Gradient spinnerGradient();

    /** Gradient a failed progress bar repaints in. */
    Gradient failureGradient();

    /** Per-codepoint truecolor lerp across {@link #titleGradient()}, bold on each char. */
    AttributedString gradientHeader(String text);

    /**
     * Same gradient as {@link #gradientHeader(String)} but returned as a raw
     * ANSI string with {@code 1;38;2;R;G;B} (bold-first truecolor) in every
     * per-codepoint SGR sequence. Use this when you need the bold attribute
     * stamped on each char's SGR — {@link AttributedString#toAnsi} emits bold
     * only on the first char's SGR and relies on persistence, which can look
     * not-bold against vivid gradient colors on some terminal renderings.
     */
    String gradientHeaderAnsi(String text);

    /** Maps (state, glyph) to the style used to render that rail glyph. */
    AttributedStyle railStyle(Rail.StepState state, Rail.RailGlyph glyph);

    // --- static, theme-independent helpers --------------------------------

    /** Holds the active theme; defaults to a {@link JkDarkTheme} singleton. */
    final class Holder {
        private Holder() {}
        private static volatile Theme active = new JkDarkTheme();
    }

    /** The active theme. Defaults to {@link JkDarkTheme}. */
    static Theme active() {
        return Holder.active;
    }

    /** Replace the active theme. */
    static void setActive(Theme theme) {
        Holder.active = theme;
    }

    /** True when foreground color should be emitted, given the resolved {@code --color} choice. */
    static boolean colorEnabled() {
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

    /**
     * Wrap {@code text} in the ANSI SGR codes for {@code style} so the
     * result can be written to a {@code PrintStream} / {@code System.out}
     * verbatim. Bypasses {@link org.jline.utils.AttributedString#toAnsi()}
     * — that path rewrites single-line box-drawing glyphs to ASCII when
     * called without a terminal, which mangles widgets that use box
     * characters.
     *
     * <p>Emits jk's canonical <em>attribute-leading</em> SGR order: every
     * text-attribute code (bold {@code 1}, faint {@code 2}, italic {@code 3},
     * underline {@code 4}, …) is placed <em>before</em> the truecolor
     * {@code 38;2;r;g;b}/{@code 48;2;r;g;b} color group. jline's
     * {@link AttributedStyle#toAnsi()} emits the color group first and the
     * attributes last; we re-order so the bytes match jk's historical output
     * (e.g. {@code 1;38;2;0;188;212} rather than {@code 38;2;0;188;212;1}).
     * Terminals ignore SGR parameter order, so this is visually identical for
     * every consumer — it just makes one canonical byte order across the module.
     */
    static String colorize(String text, AttributedStyle style) {
        String sgr = style.toAnsi();
        // A style with no attributes renders as a blank SGR body (JLine emits a
        // single space, not ""), which would otherwise produce a stray `\033[ m`.
        if (sgr.isBlank()) return text;
        return Ansi.CSI + attributeLeading(sgr) + "m" + text + Ansi.RESET;
    }

    /**
     * Re-order a {@code ;}-separated SGR body so the truecolor color group(s)
     * ({@code 38;2;r;g;b} / {@code 48;2;r;g;b}) come last and every other
     * (attribute) code keeps its original relative order in front. jline emits
     * color-first/attribute-last; jk's canonical order is attribute-first.
     */
    private static String attributeLeading(String body) {
        String[] params = body.split(";");
        StringBuilder attrs = new StringBuilder();
        StringBuilder colors = new StringBuilder();
        for (int i = 0; i < params.length; ) {
            String p = params[i];
            // A truecolor group is "38;2;r;g;b" (fg) or "48;2;r;g;b" (bg): five
            // params. Move the whole group to the color tail; everything else is
            // an attribute and stays in front, in order.
            if ((p.equals("38") || p.equals("48")) && i + 1 < params.length && params[i + 1].equals("2")) {
                int end = Math.min(i + 5, params.length);
                for (int j = i; j < end; j++) {
                    if (colors.length() > 0) colors.append(';');
                    colors.append(params[j]);
                }
                i = end;
            } else {
                if (attrs.length() > 0) attrs.append(';');
                attrs.append(p);
                i++;
            }
        }
        if (attrs.length() == 0) return colors.toString();
        if (colors.length() == 0) return attrs.toString();
        return attrs + ";" + colors;
    }
}
