// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

/** ANSI control characters. Use these constants instead of embedding literal control bytes. */
public final class Ansi {

    private Ansi() {}

    /** The raw ESC character (0x1B). */
    public static final String ESC = "\u001B";

    /** String Terminator: ESC backslash (0x1B 0x5C). Terminates OSC and other string sequences. */
    public static final String ST = "\u001B\\";

    /** BEL character (0x07). Older terminals use BEL instead of ST to terminate OSC sequences. */
    public static final String BEL = "\u0007";

    // SGR codes: ESC [ <code> m

    public static final String RESET = ESC + "[0m";

    // Text styles

    public static final String BOLD = ESC + "[1m";
    public static final String DIM = ESC + "[2m";
    public static final String ITALIC = ESC + "[3m";
    public static final String UNDERLINE = ESC + "[4m";
    public static final String BLINK = ESC + "[5m";
    public static final String REVERSE = ESC + "[7m";
    public static final String HIDDEN = ESC + "[8m";
    public static final String STRIKE = ESC + "[9m";

    // Foreground colors

    public static final String BLACK = ESC + "[30m";
    public static final String RED = ESC + "[31m";
    public static final String GREEN = ESC + "[32m";
    public static final String YELLOW = ESC + "[33m";
    public static final String BLUE = ESC + "[34m";
    public static final String MAGENTA = ESC + "[35m";
    public static final String CYAN = ESC + "[36m";
    public static final String WHITE = ESC + "[37m";

    public static final String BRIGHT_BLACK = ESC + "[90m";
    public static final String BRIGHT_RED = ESC + "[91m";
    public static final String BRIGHT_GREEN = ESC + "[92m";
    public static final String BRIGHT_YELLOW = ESC + "[93m";
    public static final String BRIGHT_BLUE = ESC + "[94m";
    public static final String BRIGHT_MAGENTA = ESC + "[95m";
    public static final String BRIGHT_CYAN = ESC + "[96m";
    public static final String BRIGHT_WHITE = ESC + "[97m";

    /** 24-bit truecolor foreground. Falls back gracefully on terminals that don't support it (no color). */
    public static String color(int r, int g, int b) {
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }

    /** Truecolor from a CSS-style hex string: {@code "#abff17"}, {@code "#fff"}, or the same without the leading {@code #}. */
    public static String color(String hex) {
        var h = hex.startsWith("#") ? hex.substring(1) : hex;
        return switch (h.length()) {
            case 3 -> color(
                Integer.parseInt("" + h.charAt(0) + h.charAt(0), 16),
                Integer.parseInt("" + h.charAt(1) + h.charAt(1), 16),
                Integer.parseInt("" + h.charAt(2) + h.charAt(2), 16));
            case 6 -> color(
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16));
            default -> throw new IllegalArgumentException("Invalid hex color: " + hex);
        };
    }
}
