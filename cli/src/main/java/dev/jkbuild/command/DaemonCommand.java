// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.GroupCommand;
import java.util.List;

/** {@code jk daemon} parent — manual control over the resident build daemon (see {@code docs/daemon.md}). */
public final class DaemonCommand extends GroupCommand {

    @Override
    public String name() {
        return "daemon";
    }

    @Override
    public String description() {
        return "Manage the resident build daemon";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new DaemonStartCommand(), new DaemonStopCommand(), new DaemonStatusCommand());
    }
}
