// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.theme;

/** A 24-bit truecolor value. */
public record Rgb(int r, int g, int b) {

    /** Build from a packed {@code 0xRRGGBB} literal (palette readability). */
    public static Rgb hex(int rgb) {
        return new Rgb((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    /**
     * Scale every channel by {@code factor} (clamped to 0–255) — the {@code filter: brightness()}
     * model. {@code <1} darkens, {@code >1} brightens.
     */
    public Rgb scaled(double factor) {
        return new Rgb(clamp(r * factor), clamp(g * factor), clamp(b * factor));
    }

    /** This color {@code fraction} (0–1) darker, e.g. {@code darker(0.30)} = 30% darker. */
    public Rgb darker(double fraction) {
        return scaled(1.0 - fraction);
    }

    /** This color {@code fraction} (0–1) brighter, e.g. {@code brighter(0.10)} = 10% brighter. */
    public Rgb brighter(double fraction) {
        return scaled(1.0 + fraction);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
