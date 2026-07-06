// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.Signals;

/**
 * App-level SIGINT handler: prints {@code ‼ Canceled by user} in red, performs an SGR reset, and
 * halts with exit code 2.
 *
 * <p>Wizards temporarily override this via {@link org.jline.terminal.Terminal#handle} so Ctrl-C
 * inside a wizard runs the wizard's own cancel path instead. The wizard re-calls {@link #install}
 * from its {@code finally} block so the global default is restored on exit — JLine's "previous
 * handler" tracking doesn't reliably round-trip the underlying {@code sun.misc.Signal} handler back
 * into place.
 *
 * <p>Uses {@link Signals#register} (JLine's reflective wrapper around {@code sun.misc.Signal})
 * instead of calling that class directly, so the compiler doesn't emit "internal proprietary API"
 * warnings.
 */
public final class GlobalCancel {

    private GlobalCancel() {}

    public static void install() {
        Signals.register("INT", () -> {
            LiveRegion active = LiveRegion.active();
            boolean handled = false;
            String message = "Canceled by user";
            if (active != null) {
                // Wipe / settle the in-flight region. When it renders its own
                // complete cancel line (the goal view's "‼ Build Canceled by user
                // took Xs" wedge), it returns true and we skip the generic notice;
                // otherwise we print it, named by the region's cancel text (e.g.
                // "Building canceled by user").
                handled = active.renderCanceled();
                message = active.canceledMessage();
            }
            var err = System.err;
            if (!handled) {
                err.print("\n" + Theme.colorize(Glyphs.CROSS + " " + message, Theme.active().error()) + "\n");
            }
            err.print(Ansi.RESET); // explicit SGR reset beyond the inline reset
            err.flush();
            // Belt-and-suspenders: signal the current session's cooperative cancel token before
            // the process-level halt. The CLI's guarantee is still the halt below; this lets a
            // cooperative consumer sharing the process (e.g. an embedder that does NOT halt)
            // observe the cancel through PhaseContext.cancelled() via the SessionCancel seam.
            dev.jkbuild.config.SessionContext.current().cancel().cancel();
            Runtime.getRuntime().halt(2);
        });
    }
}
