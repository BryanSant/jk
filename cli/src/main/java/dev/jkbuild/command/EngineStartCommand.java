// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
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
        try {
            EngineClient.EngineReady ready = EngineClient.ensureReady(paths, Jk.VERSION);
            // The optimize path already printed its own wedge ("✓ Engine  Build engine optimized and
            // started (pid N) took Xs"). Otherwise — a fast cache-mapped start, or an engine that was
            // already running — print the matching green wedge rather than a plain line.
            if (!ready.optimized()) {
                CliOutput.out(GoalWedge.chipLine(
                        Glyphs.CHECK,
                        "Engine",
                        GlobalConfig.nerdfont(),
                        "Build engine started (pid " + pidStyled(ready.handshake().pid()) + ")"));
            }
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err("jk engine: " + e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /** The engine pid in yellow on an ANSI terminal (matching the optimize wedge), plain otherwise. */
    private static String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
    }
}
