// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk jdk update-shell} — append a line to the user's shell rc file
 * so {@code $JK_BIN_DIR} ends up on the {@code PATH}.
 */
public final class JdkUpdateShellCommand implements CliCommand {

    static final String MARKER = "# Added by `jk jdk update-shell`";

    @Override public String name() { return "update-shell"; }
    @Override public String description() { return "Ensure that the jk executable directory is on the `PATH`"; }

    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<shell>", "Shell override: bash | zsh | fish. Default: detected from $SHELL.", "--shell"),
                Opt.value("<dir>", "Override the user home root (for tests). Default: $HOME.", "--home").hide(),
                Opt.value("<dir>", "Override the bin directory the export line points at.", "--bin-dir").hide());
    }

    @Override
    public int run(Invocation in) throws IOException {
        String shellOverride = in.value("shell").orElse(null);
        Path homeOverride = in.value("home").map(Path::of).orElse(null);
        Path binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);

        Path home = homeOverride != null ? homeOverride : Path.of(System.getProperty("user.home"));
        Path binDir = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Optional<JdkShell> detected = shellOverride != null ? JdkShell.detect(shellOverride) : JdkShell.detect();
        if (detected.isEmpty()) {
            System.err.println("jk jdk update-shell: could not detect shell (set --shell, or add `" + bashLine(binDir) + "` to your rc file manually)."); return 64;
        }
        JdkShell shell = detected.get();
        Path rcFile = shell.rcFile(home);
        String addition = exportLine(shell, binDir);
        if (Files.exists(rcFile)) {
            String existing = Files.readString(rcFile, StandardCharsets.UTF_8);
            if (existing.contains(MARKER) || existing.contains(addition)) { System.out.println(rcFile + ": already on PATH"); return 0; }
        } else {
            Files.createDirectories(rcFile.getParent());
        }
        Files.writeString(rcFile, "\n" + MARKER + "\n" + addition + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("Updated " + rcFile);
        System.out.println("Restart your shell or run: source " + rcFile);
        return 0;
    }

    private static String exportLine(JdkShell shell, Path binDir) {
        return shell == JdkShell.FISH ? "fish_add_path \"" + binDir + "\"" : bashLine(binDir);
    }

    private static String bashLine(Path binDir) {
        return "export PATH=\"" + binDir + ":$PATH\"";
    }
}
