// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.plugin.protocol.Ndjson;
import java.util.List;

/**
 * {@code jk history} — browse and prune the persisted build-history journal
 * ({@code ~/.jk/state/builds/journal/}). The journal is owned by the engine; these commands are thin
 * RPCs (spawning the engine if it isn't running), rendering the flat NDJSON the engine streams back.
 */
public final class HistoryCommand extends GroupCommand {

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "Browse and prune the persisted build history";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new HistoryListCommand(), new HistoryShowCommand(), new HistoryRmCommand());
    }

    // --- shared formatting helpers ----------------------------------------------

    static String glyph(boolean success, boolean cancelled) {
        return cancelled ? "⊛" : success ? "✓" : "✘"; // ⊛ cancelled, ✓ ok, ✘ failed
    }

    static String outcome(boolean success, boolean cancelled) {
        return cancelled ? "cancelled" : success ? "success" : "failed";
    }

    /** Prefer the {@code group:name} coordinate; fall back to the dir's last path segment. */
    static String label(String coord, String dir) {
        if (coord != null && !coord.isBlank()) return coord;
        if (dir == null || dir.isBlank()) return "?";
        String norm = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        int slash = norm.lastIndexOf('/');
        return slash >= 0 && slash < norm.length() - 1 ? norm.substring(slash + 1) : norm;
    }

    static String duration(long millis) {
        if (millis < 0) return "—";
        if (millis < 1000) return millis + "ms";
        double s = millis / 1000.0;
        if (s < 60) return String.format("%.1fs", s);
        long total = millis / 1000;
        return (total / 60) + "m" + (total % 60) + "s";
    }

    /** {@code part} as a whole-number percentage of {@code whole} (0% when {@code whole <= 0}). */
    static String pctString(long part, long whole) {
        return (whole <= 0 ? 0 : Math.round(100.0 * part / whole)) + "%";
    }

    static String ago(long finishedAt, long now) {
        if (finishedAt <= 0) return "";
        long sec = Math.max(0, (now - finishedAt) / 1000);
        if (sec < 60) return sec + "s ago";
        if (sec < 3600) return (sec / 60) + "m ago";
        if (sec < 86_400) return (sec / 3600) + "h ago";
        return (sec / 86_400) + "d ago";
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // --- subcommands ------------------------------------------------------------

    /** {@code jk history list} — recent runs, newest first. */
    public static final class HistoryListCommand implements CliCommand {
        @Override
        public String name() {
            return "list";
        }

        @Override
        public String description() {
            return "List recent build runs (newest first)";
        }

        @Override
        public List<Opt> options() {
            return List.of(Opt.value("<N>", "Maximum number of runs to show (default: 50).", "--limit"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            int limit = in.value("limit").map(HistoryListCommand::parseLimit).orElse(50);
            List<String> lines = EngineClient.historyList(EnginePaths.current(), limit);
            List<String> entries = lines.stream()
                    .filter(l -> EngineProtocol.HISTORY_ENTRY.equals(EngineProtocol.typeOf(l)))
                    .toList();
            if (entries.isEmpty()) {
                CliOutput.out("No build history yet — run a build and it will appear here.");
                return 0;
            }
            long now = System.currentTimeMillis();
            for (String e : entries) {
                boolean success = Ndjson.bool(e, "success", false);
                boolean cancelled = Ndjson.bool(e, "cancelled", false);
                String id = Ndjson.str(e, "id");
                String label = truncate(label(Ndjson.str(e, "coord"), Ndjson.str(e, "dir")), 34);
                String kind = Ndjson.str(e, "kind");
                long failed = Ndjson.longValue(e, "failedModules", 0);
                String note = failed > 0 ? "  (" + failed + " failed)" : "";
                long savedMillis = Ndjson.longValue(e, "savedMillis", -1);
                long estMillis = Ndjson.longValue(e, "estimatedUncachedMillis", -1);
                String saved = savedMillis >= 0 && estMillis > 0
                        ? "  saved " + duration(savedMillis) + " (" + pctString(savedMillis, estMillis) + ")"
                        : "";
                CliOutput.out(String.format(
                        "%s  %-20s  %-34s  %-6s  %8s  %s%s%s",
                        glyph(success, cancelled),
                        id,
                        label,
                        kind == null ? "" : kind,
                        duration(Ndjson.longValue(e, "millis", -1)),
                        ago(Ndjson.longValue(e, "finishedAt", 0), now),
                        saved,
                        note));
            }
            return 0;
        }

        private static int parseLimit(String raw) {
            try {
                return Math.max(1, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                return 50;
            }
        }
    }

    /** {@code jk history show <id>} — full detail of one run. */
    public static final class HistoryShowCommand implements CliCommand {
        @Override
        public String name() {
            return "show";
        }

        @Override
        public String description() {
            return "Show the full detail of one build run";
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("id", Arity.ONE, "The build id (from `jk history list`)."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            if (in.positionals().isEmpty()) {
                CliOutput.err("usage: jk history show <id>");
                return 2;
            }
            String id = in.positionals().get(0);
            List<String> lines = EngineClient.historyShow(EnginePaths.current(), id);
            String record = lines.stream()
                    .filter(l -> EngineProtocol.HISTORY_RECORD.equals(EngineProtocol.typeOf(l)))
                    .findFirst()
                    .orElse(null);
            if (record == null) {
                CliOutput.err("No such build: " + id);
                return 1;
            }
            boolean success = Ndjson.bool(record, "success", false);
            boolean cancelled = Ndjson.bool(record, "cancelled", false);
            CliOutput.out(glyph(success, cancelled) + " " + Ndjson.str(record, "id"));
            CliOutput.out("  status:   " + outcome(success, cancelled) + " (exit "
                    + Ndjson.longValue(record, "exitCode", 0) + ")");
            CliOutput.out("  kind:     " + Ndjson.str(record, "kind"));
            CliOutput.out("  project:  " + label(Ndjson.str(record, "coord"), Ndjson.str(record, "dir")));
            CliOutput.out("  dir:      " + Ndjson.str(record, "dir"));
            CliOutput.out("  duration: " + duration(Ndjson.longValue(record, "millis", -1)) + "   "
                    + ago(Ndjson.longValue(record, "finishedAt", 0), System.currentTimeMillis()));
            long savedMillis = Ndjson.longValue(record, "savedMillis", -1);
            long estMillis = Ndjson.longValue(record, "estimatedUncachedMillis", -1);
            if (savedMillis >= 0 && estMillis > 0) {
                long totalSkips = Ndjson.longValue(record, "totalSkips", -1);
                long coveredSkips = Ndjson.longValue(record, "coveredSkips", -1);
                String cov = totalSkips > 0 ? "   coverage " + pctString(coveredSkips, totalSkips) : "";
                CliOutput.out("  saved:    " + duration(savedMillis) + " (" + pctString(savedMillis, estMillis)
                        + " of ~" + duration(estMillis) + " uncached)" + cov);
            }
            String jk = Ndjson.str(record, "jkVersion");
            if (jk != null) CliOutput.out("  jk:       " + jk);
            long testsTotal = Ndjson.longValue(record, "testsTotal", -1);
            if (testsTotal >= 0) {
                CliOutput.out("  tests:    " + testsTotal + " total, "
                        + Ndjson.longValue(record, "testsSucceeded", 0) + " passed, "
                        + Ndjson.longValue(record, "testsFailed", 0) + " failed, "
                        + Ndjson.longValue(record, "testsSkipped", 0) + " skipped");
            }

            printRows(
                    lines,
                    EngineProtocol.HISTORY_MODULE,
                    "Modules",
                    m -> "  "
                            + glyph(Ndjson.bool(m, "success", false), false) + " "
                            + label(Ndjson.str(m, "coord"), Ndjson.str(m, "dir"))
                            + "  " + duration(Ndjson.longValue(m, "millis", -1)));
            printRows(
                    lines,
                    EngineProtocol.HISTORY_STEP,
                    "Steps",
                    p -> "  "
                            + Ndjson.str(p, "status") + "  " + Ndjson.str(p, "name")
                            + "  " + duration(Ndjson.longValue(p, "millis", -1)));
            printRows(lines, EngineProtocol.HISTORY_DIAG, "Diagnostics", d -> {
                StringBuilder b = new StringBuilder("  [")
                        .append(Ndjson.str(d, "severity"))
                        .append("] ");
                appendIf(b, Ndjson.str(d, "step"), ": ");
                appendIf(b, Ndjson.str(d, "test"), " — ");
                String exc = Ndjson.str(d, "exceptionClass");
                if (exc != null && !exc.isBlank()) b.append('(').append(exc).append(") ");
                String msg = Ndjson.str(d, "message");
                return b.append(msg == null ? "" : msg).toString();
            });
            return success ? 0 : 1;
        }

        private static void printRows(
                List<String> lines, String type, String header, java.util.function.Function<String, String> render) {
            List<String> rows = lines.stream()
                    .filter(l -> type.equals(EngineProtocol.typeOf(l)))
                    .toList();
            if (rows.isEmpty()) return;
            CliOutput.out(header + ":");
            for (String r : rows) CliOutput.out(render.apply(r));
        }

        private static void appendIf(StringBuilder b, String value, String suffix) {
            if (value != null && !value.isBlank()) b.append(value).append(suffix);
        }
    }

    /** {@code jk history rm <id>} — delete one run, like removing a CI run. */
    public static final class HistoryRmCommand implements CliCommand {
        @Override
        public String name() {
            return "rm";
        }

        @Override
        public String description() {
            return "Delete one build run from the history";
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of("id", Arity.ONE, "The build id to delete (from `jk history list`)."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            if (in.positionals().isEmpty()) {
                CliOutput.err("usage: jk history rm <id>");
                return 2;
            }
            String id = in.positionals().get(0);
            if (EngineClient.historyDelete(EnginePaths.current(), id)) {
                CliOutput.out("Deleted build " + id);
                return 0;
            }
            CliOutput.err("No such build: " + id);
            return 1;
        }
    }
}
