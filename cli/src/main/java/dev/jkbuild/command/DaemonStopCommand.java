// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.daemon.DaemonClient;
import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.util.List;

/**
 * {@code jk daemon stop} — graceful shutdown; stopping a daemon that isn't running is reported, not
 * treated as an error (exit 0 either way).
 */
public final class DaemonStopCommand implements CliCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the build daemon (no-op if not running)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        DaemonPaths.Paths paths = DaemonPaths.current();
        boolean wasRunning = DaemonClient.ping(paths.socket());
        if (!DaemonClient.stop(paths.socket())) {
            CliOutput.err("jk daemon: stop request failed");
            return Exit.SOFTWARE;
        }
        CliOutput.out(wasRunning ? "jk daemon: stopped" : "jk daemon: not running");
        return Exit.SUCCESS;
    }
}
