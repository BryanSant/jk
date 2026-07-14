// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Two distinct "is a human here?" questions that {@link System#console()} used to conflate.
 * {@code System.console()} is non-null only when <em>both</em> stdin and stdout are terminals, so a
 * single check answered neither question well:
 *
 * <ul>
 *   <li><b>Can I prompt?</b> ({@link #canPrompt()}) — is a real person reachable for input? Under
 *       {@code curl … | bash} the process's stdin is the pipe feeding the script, so
 *       {@code System.console()} is null even though the user is right there at the <em>controlling
 *       terminal</em> ({@code /dev/tty}). The correct probe is whether that controlling terminal is
 *       reachable — exactly what the {@link Wizard}/{@link Confirm} UI opens.
 *   <li><b>Is stdout a TTY?</b> ({@link #stdoutIsTty()}) — should I animate / draw a live region?
 *       This must stay keyed on stdout alone so {@code jk build | less} gets plain text instead of
 *       cursor-movement ANSI written into the pipe.
 * </ul>
 *
 * <p>Both honor the explicit kill switches: {@code CI} and {@code JK_NONINTERACTIVE} force
 * non-interactive, and {@code TERM=dumb} is never interactive.
 */
public final class Interactivity {

    private Interactivity() {}

    // canPrompt() builds a JLine system terminal to probe the controlling terminal, which is not
    // free and emits capability queries — cache the verdict for the life of the process.
    private static volatile Boolean canPromptCache;

    /** {@code true} when {@code CI} or {@code JK_NONINTERACTIVE} is set, or {@code TERM=dumb}. */
    private static boolean forcedNonInteractive() {
        if (System.getenv("CI") != null) return true;
        String nonInteractive = System.getenv("JK_NONINTERACTIVE");
        if (nonInteractive != null && !nonInteractive.isBlank()) return true;
        return "dumb".equals(System.getenv("TERM"));
    }

    /**
     * Whether jk can prompt a human — the <em>input</em> axis. False if forced non-interactive; else
     * true iff the controlling terminal is reachable. We probe it the same way the prompt UI does
     * ({@code TerminalBuilder.system(true)}, mirroring {@link Wizard#openTerminal()}) so this can
     * never disagree with whether a wizard/confirm would actually work — including under GraalVM
     * native-image, where a hand-rolled {@code /dev/tty} open might behave differently than JLine's
     * provider. {@code dumb(true)} makes {@code build()} fall back to a dumb terminal (which we then
     * reject) instead of throwing when there is no tty. Independent of stdin/stdout redirection, so
     * {@code jk foo | less} still counts as promptable.
     */
    public static boolean canPrompt() {
        Boolean cached = canPromptCache;
        if (cached != null) return cached;
        boolean result = computeCanPrompt();
        canPromptCache = result;
        return result;
    }

    private static boolean computeCanPrompt() {
        if (forcedNonInteractive()) return false;
        Terminal probe = null;
        try {
            // system(true): bind to the controlling terminal, not our (maybe-piped) stdio.
            // dumb(true): fall back to a dumb terminal instead of throwing when none exists.
            probe = TerminalBuilder.builder().system(true).dumb(true).build();
            String type = probe.getType();
            return !Terminal.TYPE_DUMB.equals(type) && !Terminal.TYPE_DUMB_COLOR.equals(type);
        } catch (IOException | RuntimeException e) {
            return false; // no reachable terminal → can't prompt
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                    // best-effort close of the probe terminal
                }
            }
        }
    }

    /**
     * Whether stdout is an interactive terminal — the <em>output</em> axis for animation / live
     * regions. Keyed on {@link System#console()} (non-null ⇒ stdout is a tty) so a redirected or
     * piped stdout ({@code | less}, {@code > file}, CI logs) draws plain text and never leaks
     * cursor-movement ANSI into the stream. Deliberately does <em>not</em> consult the controlling
     * terminal — that is {@link #canPrompt()}'s job.
     */
    public static boolean stdoutIsTty() {
        return System.console() != null && !forcedNonInteractive();
    }
}
