// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.activate;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk activate [<shell>]} — wire jk into the user's shell.
 *
 * <p>Two modes:
 * <ul>
 *   <li><strong>{@code jk activate}</strong> (no arg) — detects the user's
 *       shell from {@code $SHELL} and launches a one-question wizard:
 *       <em>"Allow Jk to modify your &lt;rcfile&gt;?"</em>. Yes appends
 *       the appropriate {@code eval}/{@code source}/{@code Invoke-Expression}
 *       line to the rc file. Since the line goes at the end, it sits
 *       <em>below</em> any prior tool that touches {@code JAVA_HOME}
 *       (SDKMAN, mise, etc.) — jk wins the resolution race on each
 *       prompt.</li>
 *   <li><strong>{@code jk activate <shell>}</strong> — prints the
 *       integration script to stdout for users who want to manage their
 *       own rc file (e.g. {@code eval "$(jk activate zsh)"} stamped by a
 *       dotfiles framework).</li>
 * </ul>
 */
@Command(name = "activate",
        description = "Print shell integration script (bash | zsh | fish | pwsh)")
public final class ActivateCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<shell>",
            description = "Target shell: bash, zsh, fish, pwsh. "
                    + "Omit to launch the interactive installer.")
    String shellName;

    @Override
    public Integer call() throws IOException {
        if (shellName != null && !shellName.isBlank()) {
            return printScript();
        }
        return runInstaller();
    }

    // --- script-print path --------------------------------------------------

    private Integer printScript() {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            System.err.println("jk activate: unsupported shell `" + shellName
                    + "` (supported: bash, zsh, fish, pwsh)");
            return 64;
        }
        System.out.print(shell.get().activateScript(resolveJkExe()));
        return 0;
    }

    // --- interactive-installer path -----------------------------------------

    private Integer runInstaller() throws IOException {
        var shell = Shell.detect();
        if (shell.isEmpty()) {
            System.err.println("jk activate: couldn't detect your shell from $SHELL "
                    + "(value: `" + System.getenv("SHELL") + "`). "
                    + "Pass an explicit shell, e.g. `jk activate zsh`.");
            return 64;
        }
        if (!isInteractiveTerminal()) {
            System.err.println("jk activate: stdin is not a TTY — pass a shell "
                    + "(e.g. `jk activate " + shell.get().name() + "`) "
                    + "to print the integration script instead.");
            return 64;
        }
        return runWizard(shell.get());
    }

    private Integer runWizard(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String jkExe = resolveJkExe();
        String activationLine = shell.activationLine(jkExe);

        // Already wired up? Bail out before opening a wizard the user would
        // just press Yes on for no effect.
        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(activationLine)) {
                System.out.println(Theme.colorize("✓", Theme.completedStep())
                        + " jk activation is already wired up in "
                        + Theme.colorize(rcDisplay, Theme.focused()));
                return 0;
            }
        }

        Wizard wizard = Wizard.builder()
                .title("Jk - Activate Shell Integration")
                .step(WizardStep.RadioStep.horizontal("modify", "Allow Jk to modify your "
                                + rcDisplay + " file?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("yes")
                        .build())
                .build();

        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<Answers> result;
        try (terminal) {
            result = wizard.run(terminal);
        }
        if (result.isEmpty() || "no".equals(result.get().get("modify"))) {
            System.out.println("Skipped — paste this into your "
                    + rcDisplay + " when you're ready:");
            System.out.println("  " + activationLine);
            return 0;
        }

        appendActivationLine(rcFile, activationLine);
        System.out.println(Theme.colorize("✓", Theme.completedStep())
                + " appended jk activation to " + Theme.colorize(rcDisplay, Theme.focused()));
        System.out.println("Open a new shell (or " + sourceHint(shell, rcDisplay)
                + ") to pick up the change.");
        return 0;
    }

    /**
     * Append the activation line to {@code rcFile}, creating parent
     * directories if needed (relevant for fish's
     * {@code ~/.config/fish/config.fish} on a fresh install). A blank line
     * precedes the new entry so it doesn't visually merge with whatever
     * the previous tool stamped (SDKMAN, conda, etc.).
     */
    private static void appendActivationLine(Path rcFile, String line) throws IOException {
        if (rcFile.getParent() != null) {
            Files.createDirectories(rcFile.getParent());
        }
        String block = "\n# Added by `jk activate` — keep this at the end of the file so jk's\n"
                + "# JAVA_HOME / PATH overrides land after any other tool's edits.\n"
                + line + "\n";
        if (Files.exists(rcFile)) {
            Files.writeString(rcFile, block, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } else {
            Files.writeString(rcFile, block, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
    }

    private static String sourceHint(Shell shell, String rcDisplay) {
        return switch (shell.name()) {
            case "fish" -> "run `source " + rcDisplay + "`";
            case "pwsh" -> "dot-source " + rcDisplay;
            default -> "run `source " + rcDisplay + "`";
        };
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static boolean isInteractiveTerminal() {
        return System.console() != null
                && !"dumb".equals(System.getenv("TERM"))
                && System.getenv("CI") == null;
    }

    /**
     * Best-effort resolution of the absolute path to the running {@code jk}
     * binary, so the activation script can invoke us directly without
     * relying on PATH lookup. Falls back to {@code "jk"} when we can't
     * figure it out (e.g., reflection-launched).
     */
    private static String resolveJkExe() {
        var argv0 = System.getProperty("sun.java.command");
        var envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) return envOverride;

        try {
            var info = ProcessHandle.current().info();
            var cmd = info.command();
            if (cmd.isPresent()) {
                var path = Path.of(cmd.get());
                if (path.getFileName() != null
                        && path.getFileName().toString().contains("jk")) {
                    return path.toAbsolutePath().toString();
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return argv0 != null && argv0.startsWith("/") ? argv0 : "jk";
    }
}
