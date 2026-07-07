// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.daemon.DaemonClient;
import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.io.IOException;
import java.util.List;

/**
 * {@code jk daemon start} — eager, blocking start: waits for the daemon to be confirmed live (or
 * fails with a clear error) rather than firing lazily and hoping, so CI pre-warming gets a meaningful
 * exit code. A no-op (exit 0) when a live, version-matched daemon is already running.
 */
public final class DaemonStartCommand implements CliCommand {

    @Override
    public String name() {
        return "start";
    }

    @Override
    public String description() {
        return "Start the build daemon (no-op if already running)";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        DaemonPaths.Paths paths = DaemonPaths.current();
        boolean alreadyUp = DaemonClient.handshake(paths.socket(), Jk.VERSION)
                .map(h -> Jk.VERSION.equals(h.version()))
                .orElse(false);
        try {
            DaemonClient.Handshake hs = DaemonClient.ensureRunning(paths, Jk.VERSION);
            CliOutput.out(alreadyUp
                    ? "jk daemon: already running (pid " + hs.pid() + ")"
                    : "jk daemon: started (pid " + hs.pid() + ")");
            return Exit.SUCCESS;
        } catch (IOException e) {
            CliOutput.err("jk daemon: " + e.getMessage());
            return Exit.SOFTWARE;
        }
    }
}
