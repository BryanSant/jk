// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.tui.DrainView;
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
 * {@code jk engine stop} — graceful drain by default: the engine refuses new jobs and exits cleanly
 * once in-flight jobs finish (its AOT cache still assembles). On a TTY with jobs running it blocks
 * with a live "Draining N job(s)…" region; press Ctrl-X (or pass {@code --force}) to stop now.
 * Stopping an engine that isn't running is reported, not an error (exit 0 either way).
 */
public final class EngineStopCommand implements CliCommand {

    @Override
    public String name() {
        return "stop";
    }

    @Override
    public String description() {
        return "Stop the build engine (drains running jobs first; --now to skip the drain)";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag(
                "Stop now, abandoning in-flight jobs (still assembles the AOT cache).", "--now"));
    }

    @Override
    public int run(Invocation in) {
        EnginePaths.Paths paths = EnginePaths.current();
        Optional<EngineClient.Status> before = EngineClient.status(paths.socket());
        if (before.isEmpty()) {
            CliOutput.out("jk engine: not running");
            return Exit.SUCCESS;
        }
        long started = before.get().startedAtMillis();

        // Force: stop now via the clean-exit path (SIGKILL fallback if unresponsive).
        if (in.isSet("now")) {
            if (!EngineClient.forceStop(paths.socket())) EngineClient.hardKill(before.get().pid());
            CliOutput.out(stoppedWedge(elapsed(started)));
            return Exit.SUCCESS;
        }

        // Graceful drain. The engine enters draining and reports the in-flight job count.
        int jobs = EngineClient.drain(paths.socket());
        if (jobs <= 0) {
            // Idle (or already gone): the engine exits immediately.
            CliOutput.out(stoppedWedge(elapsed(started)));
            return Exit.SUCCESS;
        }
        if (!GoalConsole.isInteractiveTerminal()) {
            CliOutput.out("jk engine: shutdown scheduled (" + jobs + " job" + (jobs == 1 ? "" : "s")
                    + " will finish first)");
            return Exit.SUCCESS;
        }
        return drainOnTty(paths, jobs, started);
    }

    /** Block on a TTY with the live drain region until the engine exits or Ctrl-X forces it. */
    private int drainOnTty(EnginePaths.Paths paths, int jobs, long started) {
        DrainView view = DrainView.start(jobs, GlobalConfig.nerdfont());
        try {
            while (true) {
                if (view.forceRequested()) {
                    EngineClient.forceStop(paths.socket());
                    break;
                }
                Optional<EngineClient.Status> s = EngineClient.status(paths.socket());
                if (s.isEmpty()) {
                    // Confirm the engine really exited (avoid a transient accept/close false positive).
                    sleep(150);
                    if (EngineClient.status(paths.socket()).isEmpty() && !EngineClient.ping(paths.socket())) break;
                    continue;
                }
                view.setJobs(Math.max(0, s.get().activePipelines()));
                sleep(200);
            }
            view.settleStopped(stoppedWedge(elapsed(started)));
        } finally {
            view.close();
        }
        return Exit.SUCCESS;
    }

    private static long elapsed(long startedAtMillis) {
        return Math.max(0, System.currentTimeMillis() - startedAtMillis);
    }

    private static String stoppedWedge(long ranMs) {
        return GoalWedge.chipLine(
                Glyphs.STOP, "Engine", GlobalConfig.nerdfont(), "Engine stopped. Ran for " + uptime(ranMs) + ".");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
