// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.Answers;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.cli.tui.PipelineWedge;
import build.jumpkick.cli.tui.Wizard;
import build.jumpkick.cli.tui.WizardStep;
import build.jumpkick.config.GlobalConfig;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Param;
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
            CliOutput.err("jk activate: unsupported shell `" + shellName + "` (supported: bash, zsh, fish, pwsh)");
            return Exit.USAGE;
        }
        // Runs on every shell startup (the rc line evals this command), so jkx
        // self-heals — and must stay silent: stdout here is eval'd shell code.
        ensureJkxLauncher();
        CliOutput.outRaw(shell.get().activateScript(resolveJkExe()));
        return 0;
    }

    /**
     * Best-effort {@code $JK_BIN_DIR/jkx} launcher (hardlink → symlink → shim; see {@link
     * JkxLink}). {@code jkx} is a real binary so shebangs/CI work without shell integration; the
     * happy path is two stats. Foreign files under the {@code jkx} name are left alone.
     */
    private static JkxLink.Result ensureJkxLauncher() {
        String exe = resolveJkExe();
        Path jkExe = exe.startsWith("/") || exe.contains(":\\") ? Path.of(exe) : null;
        return JkxLink.ensure(build.jumpkick.util.JkDirs.binDir(), jkExe);
    }

    private int runInstaller() throws IOException {
        var shell = Shell.detect();
        if (shell.isEmpty()) {
            CliOutput.err("jk activate: couldn't detect your shell from $SHELL (value: `"
                    + System.getenv("SHELL")
                    + "`). Pass an explicit shell, e.g. `jk activate zsh`.");
            return Exit.USAGE;
        }
        if (!isInteractiveTerminal()) {
            // Genuinely non-interactive: no controlling terminal (CI, cron, a headless daemon) or
            // forced off via JK_NONINTERACTIVE. We can't prompt for consent to edit a dotfile, and
            // launching a shell would hang. Don't touch the rc file — just print the one line to add
            // (and where), self-heal jkx, and succeed. Idempotent: if the rc file already carries the
            // line, say so instead. This is not an error.
            //
            // Note `curl | bash` does NOT land here: the user's controlling terminal is reachable via
            // /dev/tty even though stdin is the piped script (install.sh routes it to us), so
            // canPrompt() is true and the interactive wizard runs below.
            return printManualInstructions(shell.get());
        }
        return runWizard(shell.get());
    }

    /**
     * Non-interactive activation: never edits the rc file (no consent possible without a TTY) and
     * never launches a shell. Prints the exact line to add and where, ensures {@code jkx}, and
     * exits 0 — or reports "already configured" when the rc file already carries the line.
     */
    private int printManualInstructions(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String activationLine = shell.activationLine(resolveJkExe());
        boolean nerdfont = GlobalConfig.nerdfont();
        Theme t = Theme.active();

        if (Files.exists(rcFile)
                && Files.readString(rcFile, StandardCharsets.UTF_8).contains(activationLine)) {
            ensureJkxLauncher();
            CliOutput.out(PipelineWedge.chipLine(Glyphs.CHECK, "Activate", nerdfont,
                    "Shell integration is already configured in " + Theme.colorize(rcDisplay, t.path())));
            return 0;
        }

        ensureJkxLauncher();
        CliOutput.out(PipelineWedge.chipLine(Glyphs.BANG, "Activate", nerdfont,
                "Add this to " + Theme.colorize(rcDisplay, t.path()) + " to finish activation:"));
        CliOutput.out("  " + Theme.colorize(activationLine, t.shell()));
        return 0;
    }

    private int runWizard(Shell shell) throws IOException {
        Path rcFile = shell.rcFile(home());
        String rcDisplay = shell.rcFileDisplay();
        String jkExe = resolveJkExe();
        String activationLine = shell.activationLine(jkExe);
        boolean nerdfont = GlobalConfig.nerdfont();

        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(activationLine)) {
                ensureJkxLauncher();
                Theme t = Theme.active();
                CliOutput.out(PipelineWedge.chipLine(Glyphs.CHECK, "Activate", nerdfont,
                        "Shell integration is already configured in "
                                + Theme.colorize(rcDisplay, t.path())));
                return 0;
            }
        }

        Wizard wizard = Wizard.builder()
                .command("Activate")
                .subtitle("Shell integration")
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
            Theme t = Theme.active();
            CliOutput.out(PipelineWedge.chipLine(Glyphs.BANG, "Activate", nerdfont,
                    "Skipped — add this to " + Theme.colorize(rcDisplay, t.path()) + " manually:"));
            CliOutput.out("  " + Theme.colorize(activationLine, t.shell()));
            return 0;
        }
        appendActivationLine(rcFile, activationLine);
        JkxLink.Result jkx = ensureJkxLauncher();
        Theme t = Theme.active();
        CliOutput.out(PipelineWedge.chipLine(Glyphs.CHECK, "Activate", nerdfont,
                "Shell integration configured in " + Theme.colorize(rcDisplay, t.path())));
        if (jkx.status() == JkxLink.Status.CREATED) {
            CliOutput.out("Installed " + Theme.colorize("jkx", t.shell()) + " (uvx-style `jk tool run`) → "
                    + Theme.colorize(jkx.path().toString(), t.path()));
        } else if (jkx.status() == JkxLink.Status.SKIPPED_FOREIGN) {
            CliOutput.out("Note: " + Theme.colorize(jkx.path().toString(), t.path())
                    + " exists but wasn't created by jk — left untouched.");
        }
        CliOutput.out("Open a new shell (or " + sourceHint(shell, rcDisplay, t) + ") to pick up the change.");
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

    private static String sourceHint(Shell shell, String rcDisplay, Theme t) {
        return switch (shell.name()) {
            case "fish" -> "run " + Theme.colorize("source " + rcDisplay, t.shell());
            case "pwsh" -> "dot-source " + Theme.colorize(rcDisplay, t.shell());
            default -> "run " + Theme.colorize("source " + rcDisplay, t.shell());
        };
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static boolean isInteractiveTerminal() {
        return build.jumpkick.cli.tui.Interactivity.canPrompt();
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
