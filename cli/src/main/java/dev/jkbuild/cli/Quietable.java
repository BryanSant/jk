// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkConfig;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Suppress {@code System.out} when the resolved {@link JkConfig#quiet} is true. {@code System.err}
 * is left alone — errors must still reach the user.
 *
 * <p>The contract per {@code -q/--quiet}: a successful (exit-0) command produces zero output; a
 * failure produces at least one stderr line. Anything routed through {@code
 * System.out.println(...)} is dropped.
 *
 * <p>Idempotent: calling {@link #applyIfQuiet} more than once is a no-op after the first.
 */
public final class Quietable {

    /** Sentinel PrintStream that discards everything written to it. */
    private static final PrintStream SINK = new PrintStream(
            new OutputStream() {
                @Override
                public void write(int b) {
                    /* drop */
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    /* drop */
                }
            },
            false,
            StandardCharsets.UTF_8);

    private static volatile boolean applied = false;

    private Quietable() {}

    /**
     * Apply quiet mode if {@code config.quiet} is true. After this call, {@code System.out} is the
     * silent sink; subsequent writes go nowhere. Safe to call multiple times (only the first takes
     * effect).
     */
    public static synchronized void applyIfQuiet(JkConfig config) {
        if (applied) return;
        if (config.quietOr(false)) {
            System.setOut(SINK);
            applied = true;
        }
    }

    /** Reset to baseline. Test-only. */
    static synchronized void reset() {
        applied = false;
    }
}
