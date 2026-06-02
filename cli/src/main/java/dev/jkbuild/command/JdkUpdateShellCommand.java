// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk jdk update-shell} — append a line to the user's shell rc file
 * so {@code $JK_BIN_DIR} (where {@code jk install} and {@code jk tool
 * install} write launchers) ends up on the {@code PATH}.
 *
 * <p>Modelled after {@code uv python update-shell}. Idempotent: re-runs
 * are a no-op once the line is present. Detects the shell from
 * {@code $SHELL} (overridable via {@code --shell} for tests):
 *
 * <ul>
 *   <li><b>bash</b> → {@code ~/.bashrc}</li>
 *   <li><b>zsh</b>  → {@code ~/.zshenv} (loaded for every zsh shell, not
 *       only interactive ones — matches uv's choice)</li>
 *   <li><b>fish</b> → {@code ~/.config/fish/conf.d/jk.fish} (uses
 *       {@code fish_add_path} so the user's existing path management
 *       conventions are honored)</li>
 * </ul>
 */
@Command(name = "update-shell",
        description = "Ensure that the jk executable directory is on the `PATH`")
public final class JdkUpdateShellCommand implements Callable<Integer> {

    /** Sentinel comment so we can detect prior writes idempotently. */
    static final String MARKER = "# Added by `jk jdk update-shell`";

    @Option(names = "--shell",
            description = "Force a specific shell: bash | zsh | fish. "
                    + "Default: detected from $SHELL.")
    String shellOverride;

    @Option(names = "--home", hidden = true,
            description = "Override the user home root (for tests). Default: $HOME.")
    Path homeOverride;

    @Option(names = "--bin-dir", hidden = true,
            description = "Override the bin directory the export line points at. "
                    + "Default: $JK_BIN_DIR or ~/.local/bin.")
    Path binDirOverride;

    @Override
    public Integer call() throws IOException {
        Path home = homeOverride != null
                ? homeOverride : Path.of(System.getProperty("user.home"));
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Optional<JdkShell> detected = shellOverride != null
                ? JdkShell.detect(shellOverride) : JdkShell.detect();
        if (detected.isEmpty()) {
            System.err.println("jk jdk update-shell: could not detect shell (set --shell, "
                    + "or add `" + bashLine(binDir) + "` to your rc file manually).");
            return 64; // EX_USAGE
        }
        JdkShell shell = detected.get();

        Path rcFile = shell.rcFile(home);
        String addition = exportLine(shell, binDir);
        // Already present? Match either the export line or our marker so the
        // user's hand-edits don't get duplicated.
        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(MARKER) || existing.contains(addition)) {
                System.out.println(rcFile + ": already on PATH");
                return 0;
            }
        } else {
            Files.createDirectories(rcFile.getParent());
        }

        String toAppend = "\n" + MARKER + "\n" + addition + "\n";
        Files.writeString(rcFile, toAppend, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        System.out.println("Updated " + rcFile);
        System.out.println("Restart your shell or run: source " + rcFile);
        return 0;
    }

    private static String exportLine(JdkShell shell, Path binDir) {
        return shell == JdkShell.FISH
                ? "fish_add_path \"" + binDir + "\""
                : bashLine(binDir);
    }

    private static String bashLine(Path binDir) {
        return "export PATH=\"" + binDir + ":$PATH\"";
    }
}
