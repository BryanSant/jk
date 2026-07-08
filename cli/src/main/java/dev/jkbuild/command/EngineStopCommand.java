// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.util.List;

/**
 * {@code jk engine stop} — graceful shutdown; stopping an engine that isn't running is reported, not
 * treated as an error (exit 0 either way).
 */
public final class EngineStopCommand implements CliCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the build engine (no-op if not running)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        boolean wasRunning = EngineClient.ping(paths.socket());
        if (!EngineClient.stop(paths.socket())) {
            CliOutput.err("jk engine: stop request failed");
            return Exit.SOFTWARE;
        }
        CliOutput.out(wasRunning ? "jk engine: stopped" : "jk engine: not running");
        return Exit.SUCCESS;
    }
}
