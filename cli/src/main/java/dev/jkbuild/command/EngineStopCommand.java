// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.util.List;
import java.util.Optional;

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
        // Capture uptime from the live engine BEFORE stopping it.
        Optional<EngineClient.Status> before = EngineClient.status(paths.socket());
        if (!EngineClient.stop(paths.socket())) {
            CliOutput.err("jk engine: stop request failed");
            return Exit.SOFTWARE;
        }
        if (before.isEmpty()) {
            CliOutput.out("jk engine: not running");
            return Exit.SUCCESS;
        }
        long ranMs = Math.max(0, System.currentTimeMillis() - before.get().startedAtMillis());
        CliOutput.out(GoalWedge.chipLine(
                Glyphs.STOP, "Engine", GlobalConfig.nerdfont(), "Engine stopped. Ran for " + uptime(ranMs) + "."));
        return Exit.SUCCESS;
    }

    /** Human uptime {@code 14d 3h 37m 13s}, dropping leading zero units but always ending in seconds. */
    static String uptime(long millis) {
        long s = millis / 1000;
        long days = s / 86_400;
        long hours = (s % 86_400) / 3_600;
        long mins = (s % 3_600) / 60;
        long secs = s % 60;
        StringBuilder b = new StringBuilder();
        if (days > 0) b.append(days).append("d ");
        if (days > 0 || hours > 0) b.append(hours).append("h ");
        if (days > 0 || hours > 0 || mins > 0) b.append(mins).append("m ");
        return b.append(secs).append("s").toString();
    }
}
