// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.theme.Theme;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

/**
 * A single-line yes/no confirmation, styled like {@link Wizard} and driven by a single keystroke
 * (no Enter required): {@code y}/{@code n}, with Enter taking the default and Ctrl-C / Esc
 * declining. On an interactive terminal it briefly enters raw mode (mirroring the Wizard's terminal
 * lifecycle); on a non-TTY it falls back to a cooked {@code readLine()} so piped / CI input keeps
 * working.
 *
 * <p>Renders {@code <question> [Y/n]} (default-yes) or {@code <question> [y/N]} (default-no) with
 * the brackets and slash dimmed. Once answered, the {@code [Y/n]} hint is overwritten in place with
 * a green {@code Yes} or a red {@code No}, so the settled line reads e.g. {@code <question> Yes}.
 */
public final class Confirm {

    /** Visible columns to overwrite when settling: "[Y/n]" (5) + trailing space (1). */
    private static final int HINT_WIDTH = 6;

    private final String question;
    private final boolean defaultYes;

    private Confirm(String question, boolean defaultYes) {
        this.question = question;
        this.defaultYes = defaultYes;
    }

    public static Confirm of(String question, boolean defaultYes) {
        return new Confirm(question, defaultYes);
    }

    /**
     * Whether we can prompt a human — {@link Interactivity#canPrompt()}. Keyed on the controlling
     * terminal, not stdin/stdout, so a {@code curl | bash} install (piped stdin) or {@code jk foo |
     * less} (piped stdout) still prompts when a person is actually there.
     */
    public static boolean isInteractiveTerminal() {
        return Interactivity.canPrompt();
    }

    /**
     * Ask, opening and closing our own terminal. On a non-interactive stdin, reads a cooked line
     * instead (EOF → {@code false}).
     */
    public boolean ask() {
        if (!isInteractiveTerminal()) {
            return cookedFallback();
        }
        try (Terminal terminal = Wizard.openTerminal()) {
            // Drain probe responses (DA / DECRQM) that TerminalBuilder emits on
            // open — same as Wizard.run() does — so they don't land in the reader
            // as garbage keys.
            Wizard.drainInput(terminal.reader(), 40L);
            return ask(terminal);
        } catch (IOException e) {
            return cookedFallback();
        }
    }

    /**
     * Ask on an already-open terminal — for callers running inside a Wizard's terminal lifecycle.
     * Enters raw mode for the single keystroke and restores cooked/echo mode afterward (so later
     * {@code System.in} reads still work).
     */
    public boolean ask(Terminal terminal) {
        // Render the prompt to stderr, not stdout: a y/n the user can't see (because stdout is piped
        // to `less` / a file) would be an invisible block. stderr is the terminal's own channel by
        // convention (git/apt/ssh prompt there too). Use the terminal only to capture the single
        // keystroke in raw mode.
        var err = dev.jkbuild.cli.CliOutput.stderr();
        err.print(promptText());
        err.flush();
        Attributes saved = terminal.enterRawMode();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                Boolean result = interpret(KeyReader.read(reader));
                if (result != null) {
                    // Overwrite the "[Y/n] " hint in place with the colored
                    // answer: step back over the hint, print green Yes / red No,
                    // clear the leftover, and end the line (raw mode → explicit CRLF).
                    String answer = Theme.colorize(
                            result ? "Yes" : "No",
                            result ? Theme.active().success() : Theme.active().error());
                    err.print(Ansi.cursorBack(HINT_WIDTH) + answer + Ansi.ERASE_LINE_TO_END + "\r\n");
                    err.flush();
                    return result;
                }
            }
        } finally {
            Wizard.restoreCooked(terminal, saved);
        }
    }

    /** A key → decision, or {@code null} for keys that don't resolve the prompt. */
    private Boolean interpret(KeyReader.Key key) {
        return switch (key) {
            case KeyReader.Key.Char c ->
                switch (Character.toLowerCase(c.c())) {
                    case 'y' -> Boolean.TRUE;
                    case 'n' -> Boolean.FALSE;
                    default -> null;
                };
            case KeyReader.Key.Enter ignored -> defaultYes;
            case KeyReader.Key.CtrlC ignored -> Boolean.FALSE;
            case KeyReader.Key.Escape ignored -> Boolean.FALSE;
            default -> null;
        };
    }

    /** Cooked read for non-TTY stdin — preserves the prior {@code readLine()} semantics. */
    private boolean cookedFallback() {
        // Prompt to stderr (see ask(Terminal)) so it stays visible when stdout is redirected.
        var err = dev.jkbuild.cli.CliOutput.stderr();
        err.print(promptText());
        err.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) return false; // EOF / non-interactive → decline
            String t = line.trim();
            if (t.isEmpty()) return defaultYes;
            return t.equalsIgnoreCase("y") || t.equalsIgnoreCase("yes");
        } catch (IOException e) {
            return false;
        }
    }

    private String promptText() {
        return question + " " + yesNo() + " ";
    }

    /** {@code [Y/n]} / {@code [y/N]} with the brackets and slash dimmed. */
    private String yesNo() {
        var dim = Theme.active().darkGray();
        String y = defaultYes ? "Y" : "y";
        String n = defaultYes ? "n" : "N";
        return Theme.colorize("[", dim) + y + Theme.colorize("/", dim) + n + Theme.colorize("]", dim);
    }
}
