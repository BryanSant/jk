// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

/**
 * Centralized ANSI escape-sequence primitives for the whole {@code :cli} module.
 *
 * <p>This class owns the raw control-sequence mechanics — the {@link #ESC} and {@link #BEL}
 * characters, the CSI/OSC introducers, the {@link #ST} terminator, the SGR {@link #RESET}, cursor
 * movement, line erasure, OSC&nbsp;8 hyperlinks, and OSC&nbsp;9;4 taskbar progress. Nothing here
 * knows about <em>colors</em>: color choices live in the theme layer (see {@code
 * build.jumpkick.cli.theme.Theme}), which uses {@link #RESET} (and the SGR helpers) to emit styled
 * text.
 *
 * <p>Keeping every escape in one place means a single audited definition of {@code "\033[K"},
 * {@code "\033]9;4;…\007"}, etc., instead of the literals that used to be scattered across the help
 * renderer, the progress bars, the spinner, the wizard, and individual commands.
 */
public final class Ansi {

    private Ansi() {}

    /** Escape, {@code U+001B}. The lead byte of every CSI/OSC/SS sequence. */
    public static final char ESC = '\033';

    /** Bell, {@code U+0007}. Doubles as the legacy OSC string terminator. */
    public static final char BEL = '\007';

    /** Control Sequence Introducer: {@code ESC [}. */
    public static final String CSI = ESC + "[";

    /** Operating System Command introducer: {@code ESC ]}. */
    public static final String OSC = ESC + "]";

    /** String Terminator: {@code ESC \}. The spec-correct closer for OSC strings. */
    public static final String ST = ESC + "\\";

    /** SGR reset — clears all colors and attributes. */
    public static final String RESET = CSI + "0m";

    // --- SGR (color/attribute) mechanics ---------------------------------
    // The theme layer decides WHICH colors; these just assemble the sequence.

    /** Wrap an SGR parameter body (e.g. {@code "1;38;2;0;188;212"}) into a full {@code ESC[…m}. */
    public static String sgr(String body) {
        return CSI + body + "m";
    }

    /**
     * Truecolor foreground SGR body, e.g. {@code "38;2;r;g;b"} (no leading {@code ESC[}, no trailing
     * {@code m}).
     */
    public static String fgBody(int r, int g, int b) {
        return "38;2;" + r + ";" + g + ";" + b;
    }

    /** Bold + truecolor foreground SGR body, e.g. {@code "1;38;2;r;g;b"}. */
    public static String boldFgBody(int r, int g, int b) {
        return "1;" + fgBody(r, g, b);
    }

    // --- Cursor visibility & movement ------------------------------------

    /** Hide the cursor (DECTCEM). */
    public static final String HIDE_CURSOR = CSI + "?25l";

    /** Show the cursor (DECTCEM). */
    public static final String SHOW_CURSOR = CSI + "?25h";

    /** Erase from the cursor to the end of the current line (EL 0). */
    public static final String ERASE_LINE_TO_END = CSI + "K";

    /** Erase from the cursor to the end of the display (ED 0). */
    public static final String ERASE_DISPLAY_TO_END = CSI + "0J";

    /** Carriage return followed by erase-to-end — the common "redraw this line" prefix. */
    public static final String CLEAR_LINE = "\r" + ERASE_LINE_TO_END;

    /** Move the cursor up {@code n} rows (CUU). */
    public static String cursorUp(int n) {
        return CSI + n + "A";
    }

    /** Move the cursor down {@code n} rows (CUD). */
    public static String cursorDown(int n) {
        return CSI + n + "B";
    }

    /** Move the cursor forward {@code n} columns (CUF). */
    public static String cursorForward(int n) {
        return CSI + n + "C";
    }

    /** Move the cursor back {@code n} columns (CUB). */
    public static String cursorBack(int n) {
        return CSI + n + "D";
    }

    /** Move the cursor to absolute column {@code col}, 1-based (CHA). */
    public static String cursorToColumn(int col) {
        return CSI + col + "G";
    }

    /** Move the cursor up {@code n} lines and to column 1 (CPL). */
    public static String cursorPrevLine(int n) {
        return CSI + n + "F";
    }

    // --- OSC 8 hyperlinks -------------------------------------------------

    /**
     * Wrap {@code text} in an OSC&nbsp;8 hyperlink to {@code url}. Terminals that don't support it
     * render {@code text} plainly. Uses {@link #BEL} as the terminator (the widely-supported form).
     */
    public static String hyperlink(String url, String text) {
        return OSC + "8;;" + url + BEL + text + OSC + "8;;" + BEL;
    }

    // --- OSC 9;4 taskbar progress (ConEmu / Windows Terminal / WezTerm / kitty) ---

    /** Set determinate taskbar progress to {@code percent} (0–100). */
    public static String taskbarProgress(int percent) {
        return OSC + "9;4;1;" + percent + BEL;
    }

    /** Set the taskbar to the indeterminate (busy) state. */
    public static final String TASKBAR_INDETERMINATE = OSC + "9;4;3" + BEL;

    /** Clear any taskbar progress indicator. */
    public static final String TASKBAR_CLEAR = OSC + "9;4;0" + BEL;
}
