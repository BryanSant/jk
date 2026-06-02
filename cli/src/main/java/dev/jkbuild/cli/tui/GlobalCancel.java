// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.Signals;

/**
 * App-level SIGINT handler: prints {@code ‼ Canceled by user} in red, performs
 * an SGR reset, and halts with exit code 2.
 *
 * <p>Wizards temporarily override this via
 * {@link org.jline.terminal.Terminal#handle} so Ctrl-C inside a wizard runs
 * the wizard's own cancel path instead. The wizard re-calls {@link #install}
 * from its {@code finally} block so the global default is restored on exit —
 * JLine's "previous handler" tracking doesn't reliably round-trip the
 * underlying {@code sun.misc.Signal} handler back into place.
 *
 * <p>Uses {@link Signals#register} (JLine's reflective wrapper around
 * {@code sun.misc.Signal}) instead of calling that class directly, so the
 * compiler doesn't emit "internal proprietary API" warnings.
 */
public final class GlobalCancel {

    private GlobalCancel() {}

    public static void install() {
        Signals.register("INT", () -> {
            ProgressBar active = ProgressBar.active();
            if (active != null) {
                // Repaint the in-flight bar in red + strikethrough so the
                // user sees what was canceled, then start the next line.
                active.renderCanceled();
            }
            var err = System.err;
            err.print("\n" + Theme.colorize("‼ Canceled by user", Theme.active().error()) + "\n");
            err.print(Ansi.RESET); // explicit SGR reset beyond the inline reset
            err.flush();
            Runtime.getRuntime().halt(2);
        });
    }
}
