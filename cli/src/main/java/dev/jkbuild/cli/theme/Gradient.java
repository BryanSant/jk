// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.theme;

/**
 * A two-stop linear color gradient. jk keeps several named gradients (title, progress, spinner) so
 * each can be tuned independently; {@link #at(double)} is the per-position lerp every consumer
 * shares.
 */
public record Gradient(Rgb start, Rgb end) {

    /** The color at position {@code t} in {@code [0,1]} (clamped). */
    public Rgb at(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return new Rgb(
                lerp(start.r(), end.r(), clamped),
                lerp(start.g(), end.g(), clamped),
                lerp(start.b(), end.b(), clamped));
    }

    /** Same stops, swapped ends. */
    public Gradient reversed() {
        return new Gradient(end, start);
    }

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + t * (to - from));
    }
}
