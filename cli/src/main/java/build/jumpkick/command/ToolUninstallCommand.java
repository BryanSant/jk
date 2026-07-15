// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** {@code jk tool uninstall <name>} — remove an installed CLI tool. */
public final class ToolUninstallCommand implements CliCommand {

    @Override
    public String name() {
        return "uninstall";
    }

    @Override
    public String description() {
        return "Remove an installed CLI tool";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tool state directory. Default: $JK_STATE_DIR.", "--state-dir")
                        .hide(),
                Opt.value("<dir>", "Override the bin directory. Default: $JK_BIN_DIR or ~/.jk/bin.", "--bin-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("name", Arity.ONE, "Launcher name (matches `jk tool list` first column)."));
    }

    @Override
    public int run(Invocation in) throws IOException {
        String name = in.positionals().get(0);
        Path stateDir = in.value("state-dir").map(Path::of).orElse(null);
        Path binDirOverride = in.value("bin-dir").map(Path::of).orElse(null);
        Path state = stateDir != null ? stateDir : JkDirs.state();
        Path bin = binDirOverride != null ? binDirOverride : JkDirs.binDir();
        Path envDir = state.resolve("tools").resolve("envs").resolve(name);
        Path launcher = bin.resolve(name);
        Path winLauncher = bin.resolve(name + ".cmd");

        boolean envExists = Files.isDirectory(envDir);
        boolean launcherExists = Files.exists(launcher) || Files.exists(winLauncher);
        if (!envExists && !launcherExists) {
            CliOutput.out(name + " is not installed.");
            return 0;
        }

        if (envExists) {
            try (var stream = Files.walk(envDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.deleteIfExists(launcher);
        Files.deleteIfExists(winLauncher);
        CliOutput.out("Removed " + name);
        return 0;
    }
}
