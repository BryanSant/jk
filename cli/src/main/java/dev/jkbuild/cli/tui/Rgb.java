// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

/** A 24-bit truecolor value. */
public record Rgb(int r, int g, int b) {

    /** Build from a packed {@code 0xRRGGBB} literal (palette readability). */
    public static Rgb hex(int rgb) {
        return new Rgb((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }
}
