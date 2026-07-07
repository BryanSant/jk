// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.daemon.DaemonClient;
import dev.jkbuild.daemon.DaemonPaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk daemon status} — pings first (the same liveness authority every daemon-aware command
 * uses, never a bare pidfile read) and reports pid/version/uptime/idle policy/active requests, or
 * "not running" with a non-zero exit so scripts can branch on it. {@code --output json} emits one
 * flat object instead, for scripts/CI that want to parse the result rather than scrape text.
 */
public final class DaemonStatusCommand implements CliCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show the build daemon's status";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        DaemonPaths.Paths paths = DaemonPaths.current();
        Optional<DaemonClient.Status> status = DaemonClient.status(paths.socket());
        if (status.isEmpty()) {
            if (global.outputIsJson()) {
                CliOutput.out("{\"running\":false}");
            } else {
                CliOutput.out("jk daemon: not running");
            }
            return Exit.FAILURE;
        }
        DaemonClient.Status s = status.get();
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000);
        if (global.outputIsJson()) {
            CliOutput.out("{\"running\":true"
                    + ",\"pid\":" + s.pid()
                    + ",\"version\":" + Ndjson.quote(s.version())
                    + ",\"startedAt\":" + s.startedAtMillis()
                    + ",\"uptimeSeconds\":" + uptimeSeconds
                    + ",\"idleMinutes\":" + s.idleMinutes()
                    + ",\"activeRequests\":" + s.activeRequests()
                    + "}");
            return Exit.SUCCESS;
        }
        CliOutput.out("jk daemon: running (pid " + s.pid() + ", version " + s.version() + ")");
        CliOutput.out("  uptime:          " + formatUptime(uptimeSeconds));
        CliOutput.out("  idle-minutes:    " + describeIdleMinutes(s.idleMinutes()));
        CliOutput.out("  active requests: " + s.activeRequests());
        return Exit.SUCCESS;
    }

    private static String describeIdleMinutes(int idleMinutes) {
        if (idleMinutes == 0) return "0 (exits as soon as idle)";
        if (idleMinutes == -1) return "-1 (never expires)";
        return String.valueOf(idleMinutes);
    }

    private static String formatUptime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h + "h " + m + "m " + s + "s";
    }
}
