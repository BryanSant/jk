// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

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
 * {@code jk tool install}. Deletes the env directory and the launcher
 * script. No-op (exit 0 with a note) when the tool isn't installed,
 * matching {@code uv tool uninstall} on a missing tool.
 */
@Command(name = "uninstall", description = "Remove an installed CLI tool")
public final class ToolUninstallCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<name>",
            description = "Launcher name (matches `jk tool list` first column).")
    String name;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Override
    public Integer call() throws IOException {
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path envDir = jkHome.resolve("tools").resolve("envs").resolve(name);
        Path launcher = jkHome.resolve("bin").resolve(name);
        Path winLauncher = jkHome.resolve("bin").resolve(name + ".cmd");

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
