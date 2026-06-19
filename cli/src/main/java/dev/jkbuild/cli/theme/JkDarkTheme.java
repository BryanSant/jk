// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.theme;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.tui.Rail;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * The "Jk Dark" color scheme — a Material Indigo/Pink dark palette, and the
 * default {@link Theme} implementation. The palette constants map jk's semantic
 * roles (error, success, active accent, …) onto named colors; the instance
 * methods turn those roles into {@link AttributedStyle}s. jk emits foreground
 * colors only, so {@link #BACKGROUND}, {@link #CURSOR}, and the selection colors
 * are carried for completeness but not applied by current output.
 *
 * <p>Truecolor RGB everywhere; terminals without 24-bit color degrade to the
 * nearest indexed color via JLine's renderer.
 */
public final class JkDarkTheme implements Theme {

    public static final String NAME = "Jk Dark";
    public static final String VARIANT = "dark";

    public static final Rgb PRIMARY       = Rgb.hex(0x3F51B5); // Indigo 500
    public static final Rgb PRIMARY_DARK  = Rgb.hex(0x303F9F); // Indigo 700
    public static final Rgb PRIMARY_LIGHT = Rgb.hex(0xC5CAE9); // Indigo 100
    public static final Rgb ACCENT        = Rgb.hex(0xFF4081); // Pink A200

    public static final Rgb BACKGROUND = Rgb.hex(0x000000);
    public static final Rgb FOREGROUND = Rgb.hex(0xCFD8DC);

    public static final Rgb CURSOR      = Rgb.hex(0xECEFF1);
    public static final Rgb CURSOR_TEXT = Rgb.hex(0x000000);

    public static final Rgb SELECTION_BG   = Rgb.hex(0x303F9F);
    public static final Rgb SELECTION_TEXT = Rgb.hex(0xFFFFFF);

    // Normal (the 8 base ANSI colors).
    public static final Rgb NORMAL_BLACK   = Rgb.hex(0x263238); // Blue Grey 900
    public static final Rgb NORMAL_RED     = Rgb.hex(0xE91E63); // Pink 500
    public static final Rgb NORMAL_GREEN   = Rgb.hex(0x4CAF50); // Green 500
    public static final Rgb NORMAL_YELLOW  = Rgb.hex(0xFFC107); // Amber 500
    public static final Rgb NORMAL_BLUE    = Rgb.hex(0x3F51B5); // Indigo 500
    public static final Rgb NORMAL_MAGENTA = Rgb.hex(0x9C27B0); // Purple 500
    public static final Rgb NORMAL_CYAN    = Rgb.hex(0x00BCD4); // Cyan 500
    public static final Rgb NORMAL_WHITE   = Rgb.hex(0xCFD8DC); // Blue Grey 100

    /** Filesystem paths shown to the user. */
    public static final Rgb PATH           = Rgb.hex(0x969DD4); // periwinkle

    // Bright (the 8 bright ANSI colors).
    public static final Rgb BRIGHT_BLACK   = Rgb.hex(0x546E7A); // Blue Grey 600
    public static final Rgb BRIGHT_RED     = Rgb.hex(0xFF4081); // Pink A200
    public static final Rgb BRIGHT_GREEN   = Rgb.hex(0x69F0AE); // Green A200
    public static final Rgb BRIGHT_YELLOW  = Rgb.hex(0xFFD54F); // Amber 300
    public static final Rgb BRIGHT_BLUE    = Rgb.hex(0x536DFE); // Indigo A200
    public static final Rgb BRIGHT_MAGENTA = Rgb.hex(0xE040FB); // Purple A200
    public static final Rgb BRIGHT_CYAN    = Rgb.hex(0x18FFFF); // Cyan A200
    public static final Rgb BRIGHT_WHITE   = Rgb.hex(0xECEFF1); // Blue Grey 50

    // --- gradients --------------------------------------------------------
    // Named gradients, each independently tunable (all from the Jk Dark scheme):
    // title bright-blue → accent; spinner primary → accent; progress green
    // 50% darker → 50% brighter.
    /** Gradient for {@code jk init}/wizard titles — Jk Dark bright-blue → accent. */
    private static final Gradient TITLE_GRADIENT = new Gradient(BRIGHT_BLUE, ACCENT);
    /**
     * Gradient for the progress-bar fill — the Jk Dark green spanning 50% darker
     * → 50% brighter, so the fill is a single hue deepening as it grows.
     */
    private static final Gradient PROGRESS_GRADIENT =
            new Gradient(NORMAL_GREEN.darker(0.50), NORMAL_GREEN.brighter(0.50));
    /** Gradient for the spinner frames — Jk Dark primary → accent. */
    private static final Gradient SPINNER_GRADIENT = new Gradient(PRIMARY, ACCENT);
    /** Gradient a failed progress bar repaints in: dark red #7f1d1d → bright red #ef4444. */
    private static final Gradient FAILURE_GRADIENT = new Gradient(Rgb.hex(0x7f1d1d), Rgb.hex(0xef4444));

    /** Apply a foreground color unless the resolved {@code --color} choice disables it. */
    private static AttributedStyle withColor(AttributedStyle base, int r, int g, int b) {
        return Theme.colorEnabled() ? base.foreground(r, g, b) : base;
    }

    /** {@link Rgb} overload of {@link #withColor(AttributedStyle, int, int, int)}. */
    private static AttributedStyle withColor(AttributedStyle base, Rgb c) {
        return withColor(base, c.r(), c.g(), c.b());
    }

    @Override
    public AttributedStyle dim() {
        return AttributedStyle.DEFAULT.faint();
    }

    @Override
    public AttributedStyle darkGray() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_BLACK);
    }

    @Override
    public AttributedStyle normalGray() {
        return withColor(AttributedStyle.DEFAULT, PRIMARY_LIGHT);
    }

    @Override
    public AttributedStyle activeStep() {
        return withColor(AttributedStyle.DEFAULT, ACCENT);
    }

    @Override
    public AttributedStyle completedStep() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_GREEN);
    }

    @Override
    public AttributedStyle focused() {
        return withColor(AttributedStyle.DEFAULT.bold(), BRIGHT_WHITE);
    }

    @Override
    public AttributedStyle settled() {
        return withColor(AttributedStyle.DEFAULT, FOREGROUND);
    }

    @Override
    public AttributedStyle plainWhite() {
        return settled();
    }

    @Override
    public AttributedStyle brightWhite() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_WHITE);
    }

    @Override
    public AttributedStyle completedPrompt() {
        return darkGray();
    }

    @Override
    public AttributedStyle error() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_RED);
    }

    @Override
    public AttributedStyle success() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_GREEN);
    }

    @Override
    public AttributedStyle warning() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_YELLOW);
    }

    @Override
    public AttributedStyle blue() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_BLUE);
    }

    @Override
    public AttributedStyle primary() {
        return withColor(AttributedStyle.DEFAULT, PRIMARY);
    }

    @Override
    public AttributedStyle cyan() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_CYAN);
    }

    @Override
    public AttributedStyle black() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_BLACK);
    }

    @Override
    public AttributedStyle brightGreen() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_GREEN);
    }

    @Override
    public AttributedStyle brightCyan() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_CYAN);
    }

    @Override
    public AttributedStyle brightYellow() {
        return withColor(AttributedStyle.DEFAULT, BRIGHT_YELLOW);
    }

    @Override
    public AttributedStyle bright(int r, int g, int b) {
        return withColor(AttributedStyle.DEFAULT, r, g, b);
    }

    @Override
    public AttributedStyle bright(Rgb c) {
        return withColor(AttributedStyle.DEFAULT, c);
    }

    // --- help-semantic styles --------------------------------------------

    @Override
    public AttributedStyle sectionHeading() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_GREEN);
    }

    @Override
    public AttributedStyle commandName() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_CYAN);
    }

    @Override
    public AttributedStyle paramLabel() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_CYAN);
    }

    @Override
    public AttributedStyle highlight() {
        return withColor(AttributedStyle.DEFAULT, NORMAL_YELLOW);
    }

    @Override
    public AttributedStyle path() {
        return withColor(AttributedStyle.DEFAULT, PATH);
    }

    @Override
    public AttributedStyle errorLabel() {
        return withColor(AttributedStyle.DEFAULT.bold(), NORMAL_RED);
    }

    /** Legacy 16-color bright-green accent for {@code tip:} lines. */
    @Override
    public String tip() {
        return "92";
    }

    /** Legacy bold-bright-cyan accent for the {@code --help} hint. */
    @Override
    public String helpHint() {
        return "1;96";
    }

    // --- gradients --------------------------------------------------------

    @Override
    public Gradient titleGradient() {
        return TITLE_GRADIENT;
    }

    @Override
    public Gradient progressGradient() {
        return PROGRESS_GRADIENT;
    }

    @Override
    public Gradient spinnerGradient() {
        return SPINNER_GRADIENT;
    }

    @Override
    public Gradient failureGradient() {
        return FAILURE_GRADIENT;
    }

    @Override
    public AttributedString gradientHeader(String text) {
        var sb = new AttributedStringBuilder();
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        if (n == 0) {
            return sb.toAttributedString();
        }
        if (!Theme.colorEnabled()) {
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

    @Override
    public String gradientHeaderAnsi(String text) {
        if (text.isEmpty()) return "";
        if (!Theme.colorEnabled()) return Ansi.sgr("1") + text + Ansi.RESET;
        var codepoints = text.codePoints().toArray();
        var n = codepoints.length;
        var sb = new StringBuilder();
        for (var i = 0; i < n; i++) {
            var t = n == 1 ? 0.0 : (double) i / (n - 1);
            Rgb c = TITLE_GRADIENT.at(t);
            sb.append(Ansi.sgr(Ansi.boldFgBody(c.r(), c.g(), c.b())));
            sb.append(new String(Character.toChars(codepoints[i])));
        }
        sb.append(Ansi.RESET);
        return sb.toString();
    }

    @Override
    public AttributedStyle railStyle(Rail.StepState state, Rail.RailGlyph glyph) {
        return switch (glyph) {
            case BULLET, OPEN, MID, CLOSE -> switch (state) {
                case ACTIVE -> activeStep();
                case COMPLETED, INACTIVE -> darkGray();
            };
        };
    }
}
