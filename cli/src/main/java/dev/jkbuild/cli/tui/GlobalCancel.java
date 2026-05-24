// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

/**
 * App-level SIGINT handler: prints {@code 𝘅 Canceled} in red, performs an
 * SGR reset, and halts with exit code 2.
 *
 * <p>Wizards temporarily override this via
 * {@link org.jline.terminal.Terminal#handle} so Ctrl-C inside a wizard runs
 * the wizard's own cancel path instead. The wizard re-calls {@link #install}
 * from its {@code finally} block so the global default is restored on exit —
 * JLine's "previous handler" tracking doesn't reliably round-trip the
 * underlying {@code sun.misc.Signal} handler back into place.
 */
public final class GlobalCancel {

    private GlobalCancel() {}

    @SuppressWarnings("removal")
    public static void install() {
        sun.misc.Signal.handle(new sun.misc.Signal("INT"), sig -> {
            ProgressBar active = ProgressBar.active();
            if (active != null) {
                // Repaint the in-flight bar in red + strikethrough so the
                // user sees what was canceled, then start the next line.
                active.renderCanceled();
            }
            var err = System.err;
            err.print("\n\033[31m𝘅 Canceled\033[0m\n");
            err.print("\033[0m"); // explicit SGR reset beyond the inline [0m
            err.flush();
            Runtime.getRuntime().halt(2);
        });
    }
}
