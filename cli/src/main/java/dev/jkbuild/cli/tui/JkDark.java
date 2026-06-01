// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

/**
 * The "Jk Dark" color scheme — a Material Indigo/Pink dark palette. {@link Theme}
 * maps jk's semantic roles (error, success, active accent, …) onto these named
 * colors. jk emits foreground colors only, so {@link #BACKGROUND},
 * {@link #CURSOR}, and the selection colors are carried for completeness but not
 * applied by current output.
 */
public final class JkDark {

    private JkDark() {}

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

    // Bright (the 8 bright ANSI colors).
    public static final Rgb BRIGHT_BLACK   = Rgb.hex(0x546E7A); // Blue Grey 600
    public static final Rgb BRIGHT_RED     = Rgb.hex(0xFF4081); // Pink A200
    public static final Rgb BRIGHT_GREEN   = Rgb.hex(0x69F0AE); // Green A200
    public static final Rgb BRIGHT_YELLOW  = Rgb.hex(0xFFD54F); // Amber 300
    public static final Rgb BRIGHT_BLUE    = Rgb.hex(0x536DFE); // Indigo A200
    public static final Rgb BRIGHT_MAGENTA = Rgb.hex(0xE040FB); // Purple A200
    public static final Rgb BRIGHT_CYAN    = Rgb.hex(0x18FFFF); // Cyan A200
    public static final Rgb BRIGHT_WHITE   = Rgb.hex(0xECEFF1); // Blue Grey 50
}
