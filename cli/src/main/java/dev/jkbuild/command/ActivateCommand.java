// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Param;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.jline.terminal.Terminal;

/** {@code jk activate [<shell>]} — wire jk into the user's shell. */
public final class ActivateCommand implements CliCommand {

    @Override
    public String name() {
        return "activate";
    }

    @Override
    public String description() {
        return "Print shell integration (bash, zsh, fish, pwsh)";
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "shell",
                Arity.ZERO_OR_ONE,
                "Target shell: bash, zsh, fish, pwsh.\n" + "Omit to launch the interactive installer."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String shellName = in.positionals().isEmpty() ? null : in.positionals().get(0);
        if (shellName != null && !shellName.isBlank()) {
            return printScript(shellName);
        }
        return runInstaller();
    }

    private int printScript(String shellName) {
        var shell = Shell.byName(shellName);
        if (shell.isEmpty()) {
            System.err.println("jk activate: unsupported shell `" + shellName + "` (supported: bash, zsh, fish, pwsh)");
            return 64;
        }
        System.out.print(shell.get().activateScript(resolveJkExe()));
        return 0;
    }

    private int runInstaller() throws IOException {
        var shell = Shell.detect();
        if (shell.isEmpty()) {
            System.err.println("jk activate: couldn't detect your shell from $SHELL (value: `"
                    + System.getenv("SHELL")
                    + "`). Pass an explicit shell, e.g. `jk activate zsh`.");
            return 64;
        }
        if (!isInteractiveTerminal()) {
            System.err.println("jk activate: stdin is not a TTY — pass a shell (e.g. `jk activate "
                    + shell.get().name()
                    + "`) to print the integration script instead.");
            return 64;
        }
        return runWizard(shell.get());
    }

    private int runWizard(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String jkExe = resolveJkExe();
        String activationLine = shell.activationLine(jkExe);

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(activationLine)) {
                System.out.println(Theme.colorize(Glyphs.CHECK, Theme.active().completedStep())
                        + " jk activation is already wired up in "
                        + Theme.colorize(rcDisplay, Theme.active().focused()));
                return 0;
            }
        }

        Wizard wizard = Wizard.builder()
                .title("Jk - Activate Shell Integration")
                .step(WizardStep.RadioStep.horizontal("modify", "Allow Jk to modify your " + rcDisplay + " file?")
                        .choice("yes", "Yes")
                        .choice("no", "No")
                        .defaultChoice("yes")
                        .build())
                .build();

        Terminal terminal;
        try {
            terminal = Wizard.openTerminal();
        } catch (IOException e) {
            throw new IOException("failed to open terminal: " + e.getMessage(), e);
        }
        Optional<Answers> result;
        try (terminal) {
            result = wizard.run(terminal);
        }
        if (result.isEmpty() || "no".equals(result.get().get("modify"))) {
            System.out.println(
                    "Skipped — paste this into your " + rcDisplay + " when you're ready:\n  " + activationLine);
            return 0;
        }
        appendActivationLine(rcFile, activationLine);
        System.out.println(Theme.colorize(Glyphs.CHECK, Theme.active().completedStep())
                + " appended jk activation to "
                + Theme.colorize(rcDisplay, Theme.active().focused()));
        System.out.println("Open a new shell (or " + sourceHint(shell, rcDisplay) + ") to pick up the change.");
        return 0;
    }

    private static void appendActivationLine(Path rcFile, String line) throws IOException {
        if (rcFile.getParent() != null) Files.createDirectories(rcFile.getParent());
        String block =
                "\n# Added by `jk activate` — keep this at the end of the file so jk's\n# JAVA_HOME / PATH overrides land after any other tool's edits.\n"
                        + line
                        + "\n";
        if (Files.exists(rcFile)) Files.writeString(rcFile, block, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        else
            Files.writeString(
                    rcFile, block, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
        return System.console() != null && !"dumb".equals(System.getenv("TERM")) && System.getenv("CI") == null;
    }

    private static String resolveJkExe() {
        var argv0 = System.getProperty("sun.java.command");
        var envOverride = System.getenv("JK_EXE");
        if (envOverride != null && !envOverride.isBlank()) return envOverride;
        try {
            var info = ProcessHandle.current().info();
            var cmd = info.command();
            if (cmd.isPresent()) {
                var path = Path.of(cmd.get());
                if (path.getFileName() != null && path.getFileName().toString().contains("jk"))
                    return path.toAbsolutePath().toString();
            }
        } catch (RuntimeException ignored) {
        }
        return argv0 != null && argv0.startsWith("/") ? argv0 : "jk";
    }
}
