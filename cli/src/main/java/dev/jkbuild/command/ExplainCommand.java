// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.util.JkDirs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk explain} — forecast the build (the same {@link BuildGraph} the build
 * driver uses): every module (workspace root + modules + transitive {@code path} /
 * branch-git deps) in dependency order, its origin and edges, and — via
 * {@link BuildPlanForecast} — what each of its phases (compile → test → package)
 * would do when {@code jk build} runs: restore from cache or do real work.
 * Fully-cached modules collapse to a summary line; modules that will rebuild expand
 * to their phase sub-tree ({@code --verbose} expands all). {@code --run} executes
 * the plan ({@code jk build}). Per PRD §25.1.
 */
public final class ExplainCommand implements CliCommand {

    @Override public String name() { return "explain"; }
    @Override public String description() { return "Print the planned build (units, order, cache hit/miss)"; }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Build the plan instead of printing it (`jk build`).", "--run"),
                Opt.flag("Expand every module's phases, even cached ones.", "--verbose", "-v"),
                Opt.value("<dir>",
                        "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                        "--cache-dir").hide());
    }

    @Override
    public int run(Invocation in) throws Exception {
        if (in.isSet("run")) {
            return new BuildCommand().run(in);   // forwards --cache-dir; build options default
        }
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path startDir = global.workingDir();
        Path buildFile = startDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk explain: no jk.toml in " + dev.jkbuild.cli.PathDisplay.styledRaw(startDir));
            return 2;
        }
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        BuildGraph.Result graph = BuildGraph.resolve(startDir, entry, cache.resolve("git"));
        if (graph.hasErrors()) {
            for (String err : graph.errors()) System.err.println(ConsoleSpec.errorLine("composite", err));
            return 2;
        }

        Theme t = Theme.active();
        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        // On a TTY, elide long dependency edges to the terminal width; piped output
        // gets the full list (MAX_VALUE → never truncates).
        int width = dev.jkbuild.cli.run.GoalConsole.isInteractiveTerminal()
                ? dev.jkbuild.cli.tui.CommandManager.detectColumns() : Integer.MAX_VALUE;
        Map<Path, String> coordByDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) coordByDir.put(u.dir(), u.coord());

        // Forecast every module's full phase pipeline (compile → test → package),
        // truthfully — see BuildPlanForecast.
        List<BuildPlanForecast.Module> modules = BuildPlanForecast.of(graph, cas, actionCache, cache);
        boolean all = in.isSet("verbose");
        int total = modules.size();
        long rebuild = modules.stream().filter(BuildPlanForecast.Module::dirty).count();

        // Predicted wall-clock for the build: sum each module's bar weight (the SAME
        // estimate the live bar calibrates to — weights ≈150 ms each, with the cascade
        // reserved via forceRebuild), × MS_PER_WEIGHT. Computed even for an all-cached
        // plan: re-parsing every build file and re-checking stamps/CAS across the
        // workspace is a real couple of seconds (measured ~2.3 s on jk.jk's 17 modules),
        // not instant — so explain always quantifies how long the next build will take.
        long etaMillis = 0;
        try {
            for (BuildPlanForecast.Module m : modules) {
                Path mdir = m.unit().dir();
                var inputs = new dev.jkbuild.runtime.BuildPipeline.Inputs(
                        mdir, cache, mdir.resolve("jk.toml"), mdir.resolve("jk.lock"), mdir, 1,
                        TestCommand.estimateTestCount(mdir.resolve("src/test/java")),
                        null, JkDirs.jdks(), false, false);
                etaMillis += (long) dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs, m.dirty())
                        .build().estimatedTotalWeight() * dev.jkbuild.runtime.EffortWeights.MS_PER_WEIGHT;
            }
        } catch (RuntimeException e) {
            etaMillis = 0;   // never fail explain over the estimate
        }

        // Header: a leading blank line, then a left-flush cyan powerline segment —
        // " Build Plan for <coord> " on the cyan chip, capped on the right by a ▶ segment
        // arrow. A module/rebuild summary follows, then a trunk rail.
        String title = " Build Plan for " + entry.project().group() + ":" + entry.project().name() + " ";
        String header = nerdfont
                ? Theme.colorize(title, t.cyanBadge())
                        + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD, t.cyan())
                : Theme.colorize(title, t.cyanBadge());
        String summary = Theme.colorize(total + (total == 1 ? " module" : " modules"), t.brightWhite())
                + Theme.colorize(" · ", t.darkGray())
                + (rebuild == 0
                        ? Theme.colorize("all cached", t.success())
                        : Theme.colorize(rebuild + " will rebuild", t.brightWhite()))
                + (etaMillis > 0
                        ? Theme.colorize(" · ~" + fmtDuration(etaMillis), t.darkGray())
                        : "");
        System.out.println();
        System.out.println(header + " " + summary);
        System.out.println(Theme.colorize("│", t.darkGray()));

        // Group into display rows: dirty modules render individually (with a phase
        // sub-tree); a contiguous run of more than COLLAPSE fully-cached modules
        // collapses to one summary line (--verbose expands everything).
        List<int[]> rows = new ArrayList<>();   // {startIndex, count}; count>1 ⇒ collapsed cached run
        for (int i = 0; i < modules.size(); ) {
            if (all || modules.get(i).dirty()) { rows.add(new int[]{i, 1}); i++; continue; }
            int j = i;
            while (j < modules.size() && !modules.get(j).dirty()) j++;
            int run = j - i;
            if (run > COLLAPSE) rows.add(new int[]{i, run});
            else for (int k = i; k < j; k++) rows.add(new int[]{k, 1});
            i = j;
        }
        for (int r = 0; r < rows.size(); r++) {
            boolean last = r == rows.size() - 1;
            int[] row = rows.get(r);
            if (row[1] > 1) renderCollapsed(modules, row[0], row[1], last, width, t);
            else renderModule(modules.get(row[0]), row[0] + 1, graph, coordByDir, last, width, nerdfont, t);
        }
        System.out.println();
        System.out.println(Theme.colorize((total - rebuild) + " cached · " + rebuild + " rebuild", t.darkGray()));
        return 0;
    }

    /** "1m 20s" / "8s" / "<1s" — coarse predicted-duration formatting for the plan summary. */
    private static String fmtDuration(long millis) {
        if (millis <= 0) return "0s";
        long s = millis / 1000;                       // floor: don't over-state
        if (s == 0) return "<1s";                     // a sub-second cache-verify pass
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /** Longest phase-name column (e.g. {@code package-shadow}, {@code compile-kotlin}). */
    private static final int PHASE_COL = 14;
    /** Contiguous fully-cached runs longer than this collapse to one summary line. */
    private static final int COLLAPSE = 3;

    /** Render one module: a header line + (when dirty) its phase sub-tree. */
    private static void renderModule(BuildPlanForecast.Module m, int idx, BuildGraph.Result graph,
                                     Map<Path, String> coordByDir, boolean last, int width,
                                     boolean nerdfont, Theme t) {
        BuildGraph.BuildUnit u = m.unit();
        String origin = switch (u.origin()) {
            case ROOT -> "root";
            case MODULE -> "module";
            case PATH -> "path dep";
            case BRANCH_GIT -> "branch git dep";
        };
        String idxStr = String.format("%02d", idx);
        // Visible (uncolored) width as we go, so the verdict can be column-aligned.
        int plain = 2 + (idxStr.length() + 2) + 1 + u.coord().length() + 1 + origin.length();
        StringBuilder line = new StringBuilder()
                .append(Theme.colorize((last ? "╰" : "├") + "─", t.darkGray()))
                .append(dev.jkbuild.cli.tui.Badge.pill(idxStr, nerdfont))
                .append(' ').append(coloredCoord(u.coord(), t))
                .append(' ').append(Theme.colorize(origin, t.darkGray()));
        List<String> prereqs = new ArrayList<>();
        for (Path p : graph.edges().getOrDefault(u.dir(), Set.of())) {
            String c = coordByDir.get(p);
            if (c != null) prereqs.add(abbreviate(c, u.coord())); // same-group → :name
        }
        if (!prereqs.isEmpty()) {
            String elided = elideDeps(prereqs, Math.max(8, width - plain - VERDICT_COL - 3));
            line.append(' ').append(renderDeps(elided, t));
            plain += 1 + 2 + elided.length();   // " " + "← " + edges
        }
        String verdict = m.dirty()
                ? Theme.colorize("□ rebuild", t.brightWhite())
                : Theme.colorize("✓ fully cached", t.success());
        int pad = Math.max(2, VERDICT_COL - plain);
        line.append(" ".repeat(pad)).append(verdict);
        System.out.println(line);

        if (m.dirty()) {
            String spine = last ? " " : Theme.colorize("│", t.darkGray());
            List<BuildPlanForecast.Phase> ph = m.phases();
            for (int k = 0; k < ph.size(); k++) {
                boolean lp = k == ph.size() - 1;
                System.out.println(spine + "  " + Theme.colorize(lp ? "╰─ " : "├─ ", t.darkGray())
                        + Theme.colorize(padRight(ph.get(k).name(), PHASE_COL), t.brightWhite())
                        + "  " + renderStatus(ph.get(k), t));
            }
        }
    }

    /** Column the per-module verdict ("✓ fully cached" / "□ rebuild") aligns to. */
    private static final int VERDICT_COL = 44;

    /** Render a collapsed run of fully-cached modules as one summary line. */
    private static void renderCollapsed(List<BuildPlanForecast.Module> modules, int start, int count,
                                        boolean last, int width, Theme t) {
        List<String> names = new ArrayList<>();
        for (int k = start; k < start + count; k++) {
            String coord = modules.get(k).unit().coord();
            int c = coord.indexOf(':');
            names.add(c < 0 ? coord : coord.substring(c + 1));
        }
        String label = count + " modules fully cached";
        String elided = elideDeps(names, Math.max(10, width - label.length() - 8));
        StringBuilder sb = new StringBuilder(Theme.colorize((last ? "╰" : "├") + "─ ", t.darkGray()))
                .append(Theme.colorize("✓ ", t.success()))
                .append(Theme.colorize(label + ": ", t.darkGray()));
        String[] pieces = elided.split(", ");
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(Theme.colorize(", ", t.darkGray()));
            sb.append(pieces[i].matches("…\\+\\d+ more…")
                    ? Theme.colorize(pieces[i], t.darkGray())
                    : Theme.colorize(pieces[i], t.coordName()));
        }
        System.out.println(sb);
    }

    /** A phase's status: {@code ✓ cached <key> · detail} (green) or {@code □ detail} (white). */
    private static String renderStatus(BuildPlanForecast.Phase p, Theme t) {
        if (p.cached()) {
            StringBuilder s = new StringBuilder(Theme.colorize("✓ cached", t.success()));
            if (p.key() != null) s.append(' ').append(Theme.colorize(p.key(), t.path()));
            if (p.text() != null && !p.text().isEmpty())
                s.append(' ').append(Theme.colorize(p.text(), t.darkGray()));
            return s.toString();
        }
        return Theme.colorize("□ " + p.text(), t.brightWhite());
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    /** {@code group:name} with the group and name in their coordinate colors. */
    private static String coloredCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup())
                + ":" + Theme.colorize(coord.substring(colon + 1), t.coordName());
    }

    /** Abbreviate a prereq to {@code :name} when it shares {@code unitCoord}'s group. */
    private static String abbreviate(String prereq, String unitCoord) {
        int pc = prereq.indexOf(':');
        int uc = unitCoord.indexOf(':');
        if (pc > 0 && uc > 0 && prereq.substring(0, pc).equals(unitCoord.substring(0, uc))) {
            return prereq.substring(pc); // ":name"
        }
        return prereq;
    }

    /**
     * Join {@code units} with {@code ", "} to fit {@code available} visible columns.
     * When the full list is too wide, show as many leading units as fit followed by a
     * {@code …+N more…} marker, where {@code N} is the count of remaining units that
     * didn't fit. {@code available} is effectively unbounded on a non-TTY, so the full
     * list is shown there.
     */
    static String elideDeps(List<String> units, int available) {
        String full = String.join(", ", units);
        if (available <= 0 || units.size() <= 1 || full.length() <= available) return full;
        String best = "…+" + units.size() + " more…"; // marker-only, if even one unit won't fit
        for (int k = 1; k < units.size(); k++) {
            String candidate = String.join(", ", units.subList(0, k))
                    + ", …+" + (units.size() - k) + " more…";
            if (candidate.length() > available) break; // front grows monotonically
            best = candidate;
        }
        return best;
    }

    /**
     * Color an elided dep list: {@code ← } and separators dim, each dep's name in the
     * coordinate-name color (the {@code :} dim for same-group {@code :name} refs), and
     * a {@code …+N more…} remaining-count marker dim.
     */
    private static String renderDeps(String elided, Theme t) {
        StringBuilder sb = new StringBuilder(Theme.colorize("← ", t.darkGray()));
        String[] pieces = elided.split(", ");
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(Theme.colorize(", ", t.darkGray()));
            String p = pieces[i];
            if (p.matches("…\\+\\d+ more…")) {
                sb.append(Theme.colorize(p, t.darkGray()));            // remaining-count marker
            } else if (p.startsWith(":")) {
                sb.append(Theme.colorize(":", t.darkGray()))
                        .append(Theme.colorize(p.substring(1), t.coordName()));
            } else {
                sb.append(coloredCoord(p, t));                         // full group:name
            }
        }
        return sb.toString();
    }

}
