// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.Jk;
import build.jumpkick.cli.engine.EngineClient;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.cli.tui.PipelineWedge;
import build.jumpkick.config.GlobalConfig;
import build.jumpkick.engine.EnginePaths;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
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
        // Was a matching engine already up before we touched it? Distinguishes "already running"
        // from a fresh start in the settled wedge below.
        boolean alreadyUp = EngineClient.handshake(build.jumpkick.engine.EnginePaths.activeSocket(paths), Jk.VERSION)
                .map(h -> Jk.VERSION.equals(h.version()))
                .orElse(false);
        try {
            EngineClient.Handshake hs = EngineClient.ensureRunning(paths, Jk.VERSION);
            String pid = pidStyled(hs.pid());
            String message =
                    alreadyUp ? "Engine already running (pid " + pid + ")" : "Build engine started (pid " + pid + ")";
            CliOutput.out(PipelineWedge.chipLine(Glyphs.CHECK, "Engine", GlobalConfig.nerdfont(), message));
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err("jk engine: " + e.getMessage());
            return Exit.SOFTWARE;
        }
    }

    /** The engine pid in yellow on an ANSI terminal (matching the status wedge), plain otherwise. */
    private static String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
    }
}
