// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import dev.jkbuild.cli.theme.Theme;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A single-line yes/no confirmation, styled like {@link Wizard} and driven by a
 * single keystroke (no Enter required): {@code y}/{@code n}, with Enter taking
 * the default and Ctrl-C / Esc declining. On an interactive terminal it briefly
 * enters raw mode (mirroring the Wizard's terminal lifecycle) and echoes the
 * chosen answer; on a non-TTY it falls back to a cooked {@code readLine()} so
 * piped / CI input keeps working.
 *
 * <p>Renders {@code <question> [Y/n]} (default-yes) or {@code <question> [y/N]}
 * (default-no) with the brackets and slash dimmed, exactly like the prompts it
 * replaces.
 */
public final class Confirm {

    private final String question;
    private final boolean defaultYes;

    private Confirm(String question, boolean defaultYes) {
        this.question = question;
        this.defaultYes = defaultYes;
    }

    public static Confirm of(String question, boolean defaultYes) {
        return new Confirm(question, defaultYes);
    }

    /** True on most terminals; false when stdin is piped/redirected, dumb, or under CI. */
    public static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }

    /**
     * Ask, opening and closing our own terminal. On a non-interactive stdin,
     * reads a cooked line instead (EOF → {@code false}).
     */
    public boolean ask() {
        if (!isInteractiveTerminal()) {
            return cookedFallback();
        }
        try (Terminal terminal = Wizard.openTerminal()) {
            return ask(terminal);
        } catch (IOException e) {
            return cookedFallback();
        }
    }

    /**
     * Ask on an already-open terminal — for callers running inside a Wizard's
     * terminal lifecycle. Enters raw mode for the single keystroke and restores
     * cooked/echo mode afterward (so later {@code System.in} reads still work).
     */
    public boolean ask(Terminal terminal) {
        // Render via System.out (cooked, before raw mode) so any context the
        // caller already printed via System.out stays correctly ordered; use the
        // terminal only to capture the single keystroke in raw mode.
        System.out.print(promptText());
        System.out.flush();
        Attributes saved = terminal.enterRawMode();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                Boolean result = interpret(KeyReader.read(reader));
                if (result != null) {
                    // Raw mode: emit an explicit CRLF since ONLCR is off.
                    System.out.print(Theme.colorize(result ? "Yes" : "No",
                            Theme.active().focused()) + "\r\n");
                    System.out.flush();
                    return result;
                }
            }
        } finally {
            Attributes cooked = new Attributes(saved);
            cooked.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            cooked.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            terminal.setAttributes(cooked);
            terminal.flush();
        }
    }

    /** A key → decision, or {@code null} for keys that don't resolve the prompt. */
    private Boolean interpret(KeyReader.Key key) {
        return switch (key) {
            case KeyReader.Key.Char c -> switch (Character.toLowerCase(c.c())) {
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
        System.out.print(promptText());
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
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
        return Theme.colorize("[", dim) + y + Theme.colorize("/", dim)
                + n + Theme.colorize("]", dim);
    }
}
