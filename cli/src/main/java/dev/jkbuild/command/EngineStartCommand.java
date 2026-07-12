// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk engine start} — eager, blocking start: waits for the engine to be confirmed live (or
 * fails with a clear error) rather than firing lazily and hoping, so CI pre-warming gets a meaningful
 * exit code. A no-op (exit 0) when a live, version-matched engine is already running.
 */
public final class EngineStartCommand implements CliCommand {

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String description() {
        return "Start the build engine (no-op if already running)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        boolean alreadyUp = EngineClient.handshake(paths.socket(), Jk.VERSION)
                .map(h -> Jk.VERSION.equals(h.version()))
                .orElse(false);
        try {
            EngineClient.Handshake hs = EngineClient.ensureReady(paths, Jk.VERSION);
            CliOutput.out(alreadyUp
                    ? "jk engine: already running (pid " + hs.pid() + ")"
                    : "jk engine: started (pid " + hs.pid() + ")");
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err("jk engine: " + e.getMessage());
            return Exit.SOFTWARE;
        }
    }
}
