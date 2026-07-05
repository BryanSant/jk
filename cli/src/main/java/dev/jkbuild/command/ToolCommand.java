// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.GroupCommand;
import java.util.List;

/** {@code jk tool} parent verb — manage tools, scripts, and commands. */
public final class ToolCommand extends GroupCommand {

    @Override
    public String name() {
        return "tool";
    }

    @Override
    public String description() {
        return "Manage tools, scripts, and commands";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new ToolInstallCommand(),
                new ToolListCommand(),
                new ToolUninstallCommand(),
                new ToolRunCommand(),
                new ToolDirCommand());
    }
}
