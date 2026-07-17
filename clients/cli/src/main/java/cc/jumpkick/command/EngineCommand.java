// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/** {@code jk engine} parent — manual control over the resident build engine (see {@code docs/engine.md}). */
public final class EngineCommand extends GroupCommand {

    @Override
    public String name() {
        return "engine";
    }

    @Override
    public String description() {
        return "Manage the resident build engine";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new EngineStartCommand(),
                new EngineStopCommand(),
                new EngineStatusCommand(),
                new EngineRotateTokenCommand());
    }
}
