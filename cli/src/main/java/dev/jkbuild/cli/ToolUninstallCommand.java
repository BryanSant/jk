// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;

/**
 * {@code jk tool uninstall <name>} — remove a tool installed via
 * {@code jk tool install}. Deletes the env metadata under
 * {@code $JK_STATE_DIR/tools/envs/<name>/} and the launcher under
 * {@code $JK_BIN_DIR/<name>}. No-op (exit 0 with a note) when the tool
 * isn't installed, matching {@code uv tool uninstall}.
 */
@Command(name = "uninstall", description = "Remove an installed CLI tool")
public final class ToolUninstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<name>",
            description = "Launcher name (matches `jk tool list` first column).")
    String name;

    @Option(names = "--state-dir", hidden = true,
            description = "Override the tool state directory. Default: $JK_STATE_DIR.")
    Path stateDir;

    @Option(names = "--bin-dir", hidden = true,
            description = "Override the bin directory. Default: $JK_BIN_DIR or ~/.local/bin.")
    Path binDirOverride;

    @Override
    public Integer call() throws IOException {
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path bin = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envDir = state.resolve("tools").resolve("envs").resolve(name);
        Path launcher = bin.resolve(name);
        Path winLauncher = bin.resolve(name + ".cmd");

        boolean envExists = Files.isDirectory(envDir);
        boolean launcherExists = Files.exists(launcher) || Files.exists(winLauncher);
        if (!envExists && !launcherExists) {
            System.out.println(name + " is not installed.");
            return 0;
        }

        if (envExists) {
            try (var stream = Files.walk(envDir)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
        Files.deleteIfExists(launcher);
        Files.deleteIfExists(winLauncher);

        System.out.println("Removed " + name);
        return 0;
    }
}
