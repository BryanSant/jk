// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk status} — the running build statistics the engine aggregates at every build/test
 * finish: invocation counts, total time, and min/avg/max durations, for the current project and
 * for this machine as a whole, with a per-step breakdown (a plugin's command runs as its own step,
 * so the step table is also the per-worker/per-plugin view). Engine vitals live under {@code jk
 * engine status}; this command is about the builds, not the process. A thin RPC over the engine's
 * metrics store ({@code ~/.jk/state/builds/metrics.json}), spawning the engine if needed.
 */
public final class StatusCommand implements CliCommand {

    /** Steps shown per table by default (the rest need {@code --steps}). */
    private static final int DEFAULT_STEP_LIMIT = 8;

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show running build stats for this project and this machine";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Show only the machine-wide totals (skip the current project).", "--global"),
                Opt.flag("Show every step instead of the top " + DEFAULT_STEP_LIMIT + " by total time.", "--steps"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        boolean globalOnly = in.has("global");
        boolean allSteps = in.has("steps");
        String dir = Path.of("").toAbsolutePath().normalize().toString();

        EnginePaths.Paths paths = EnginePaths.current();
        List<String> rows = EngineClient.metrics(paths, globalOnly ? null : dir).stream()
                .filter(l -> EngineProtocol.METRICS_ENTRY.equals(EngineProtocol.typeOf(l)))
                .toList();

        if (global.outputIsJson()) {
            // Each reply line is already one flat JSON object — emit them as an array verbatim.
            CliOutput.out("[" + String.join(",", rows) + "]");
            return 0;
        }

        if (rows.isEmpty()) {
            CliOutput.out("No build metrics yet — run a build and they will appear here.");
            return 0;
        }

        Optional<EngineClient.Status> engine = EngineClient.status(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
        engine.ifPresent(s -> CliOutput.out(PipelineWedge.chipLine(
                Glyphs.PLAY, "Engine", GlobalConfig.nerdfont(), "Engine is running (pid " + s.pid() + ")")));

        if (!globalOnly) {
            List<String> project = scoped(rows, "project");
            List<String> projectSteps = scoped(rows, "project/step");
            CliOutput.out("");
            if (project.isEmpty() && projectSteps.isEmpty()) {
                CliOutput.out(header("Project", dir));
                CliOutput.out("  no builds recorded for this project yet");
            } else {
                String coord = project.stream()
                        .map(r -> Jsonl.str(r, "coord"))
                        .filter(c -> c != null && !c.isBlank())
                        .findFirst()
                        .orElse(null);
                CliOutput.out(header("Project " + HistoryCommand.label(coord, dir), dir));
                printInvocations(project);
                printSteps(projectSteps, allSteps);
            }
        }

        CliOutput.out("");
        CliOutput.out(header("Global", "this machine"));
        printInvocations(scoped(rows, "global"));
        if (globalOnly || allSteps) printSteps(scoped(rows, "step"), allSteps);
        return 0;
    }

    private static List<String> scoped(List<String> rows, String scope) {
        return rows.stream().filter(r -> scope.equals(Jsonl.str(r, "scope"))).toList();
    }

    private static String header(String title, String detail) {
        return title + " " + Theme.colorize("(" + detail + ")", Theme.active().dim());
    }

    /** One line per invocation kind: {@code builds:  42 ok · 3 failed   avg … min … max … total …}. */
    private static void printInvocations(List<String> rows) {
        rows.stream()
                .sorted(Comparator.comparing(r -> String.valueOf(Jsonl.str(r, "kind"))))
                .forEach(r -> {
                    String kind = Jsonl.str(r, "kind");
                    CliOutput.out(String.format(
                            "  %-8s %-28s %s", (kind == null ? "?" : kind) + "s:", outcomes(r), spread(r, "ok")));
                });
    }

    /** {@code 42 ok · 3 failed · 1 cancelled} — zero buckets are dropped. */
    private static String outcomes(String r) {
        StringBuilder b = new StringBuilder();
        appendCount(b, Jsonl.longValue(r, "okCount", 0), "ok");
        appendCount(b, Jsonl.longValue(r, "failCount", 0), "failed");
        appendCount(b, Jsonl.longValue(r, "cancelledCount", 0), "cancelled");
        return b.length() == 0 ? "0 runs" : b.toString();
    }

    private static void appendCount(StringBuilder b, long count, String label) {
        if (count == 0) return;
        if (b.length() > 0) b.append(" · ");
        b.append(count).append(' ').append(label);
    }

    /** {@code avg 2.1s  min 0.8s  max 14.0s  total 1m31s} for one stats prefix, or "" when empty. */
    private static String spread(String r, String prefix) {
        if (Jsonl.longValue(r, prefix + "Count", 0) == 0) return "";
        long avg = prefix.equals("ok")
                ? Jsonl.longValue(r, "okAvgMillis", 0)
                : Jsonl.longValue(r, prefix + "TotalMillis", 0)
                        / Math.max(1, Jsonl.longValue(r, prefix + "Count", 1));
        return "avg " + HistoryCommand.duration(avg)
                + "  min " + HistoryCommand.duration(Jsonl.longValue(r, prefix + "MinMillis", -1))
                + "  max " + HistoryCommand.duration(Jsonl.longValue(r, prefix + "MaxMillis", -1))
                + "  total " + HistoryCommand.duration(Jsonl.longValue(r, prefix + "TotalMillis", -1));
    }

    /** The per-step table, biggest ok-total first; capped unless {@code --steps}. */
    private static void printSteps(List<String> rows, boolean all) {
        if (rows.isEmpty()) return;
        List<String> sorted = rows.stream()
                .sorted(Comparator.comparingLong((String r) -> Jsonl.longValue(r, "okTotalMillis", 0))
                        .reversed())
                .toList();
        int shown = all ? sorted.size() : Math.min(sorted.size(), DEFAULT_STEP_LIMIT);
        CliOutput.out("  steps (by total time):");
        for (int i = 0; i < shown; i++) {
            String r = sorted.get(i);
            long ok = Jsonl.longValue(r, "okCount", 0);
            long failed = Jsonl.longValue(r, "failCount", 0);
            String note = failed > 0 ? "  (" + failed + " failed)" : "";
            CliOutput.out(String.format(
                    "    %-18s %4d ok  %s%s",
                    HistoryCommand.truncate(String.valueOf(Jsonl.str(r, "step")), 18), ok, spread(r, "ok"), note));
        }
        if (shown < sorted.size()) {
            CliOutput.out(Theme.colorize(
                    "    … " + (sorted.size() - shown) + " more (use --steps)",
                    Theme.active().dim()));
        }
    }
}
