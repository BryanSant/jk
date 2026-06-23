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
import java.util.List;
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
                Opt.flag("Estimate the ETA for a serial build (one module at a time).", "--no-parallel"),
                Opt.flag("", "--parallel").hide(),
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
        // On a TTY, wrap the cached-module list to the terminal width; piped output gets
        // the full list on one line (MAX_VALUE → never wraps).
        int width = dev.jkbuild.cli.run.GoalConsole.isInteractiveTerminal()
                ? dev.jkbuild.cli.tui.CommandManager.detectColumns() : Integer.MAX_VALUE;

        // Forecast every module's full phase pipeline (compile → test → package),
        // truthfully — see BuildPlanForecast.
        List<BuildPlanForecast.Module> modules = BuildPlanForecast.of(graph, cas, actionCache, cache);
        boolean all = in.isSet("verbose");
        int total = modules.size();
        long rebuild = modules.stream().filter(BuildPlanForecast.Module::dirty).count();

        // Predicted wall-clock for the build. Each module's predicted weight (≈150 ms
        // each, cascade reserved via forceRebuild) feeds a schedule-aware estimate that
        // mirrors `jk build`: serial (--no-parallel) sums everything; otherwise the
        // parallel graph build overlaps independent modules, so we take the critical
        // path / throughput / serial-test bound (see EffortWeights.scheduleMillis).
        // Computed even for an all-cached plan: re-parsing every build file and
        // re-checking stamps/CAS across the workspace is a real couple of seconds.
        boolean serial = in.isSet("no-parallel");
        long etaMillis = 0;
        try {
            List<dev.jkbuild.runtime.EffortWeights.ModuleCost> costs = new ArrayList<>();
            for (BuildPlanForecast.Module m : modules) {
                Path mdir = m.unit().dir();
                var inputs = new dev.jkbuild.runtime.BuildPipeline.Inputs(
                        mdir, cache, mdir.resolve("jk.toml"), mdir.resolve("jk.lock"), mdir, 1,
                        TestCommand.estimateTestCount(mdir.resolve("src/test/java")),
                        null, JkDirs.jdks(), false, false);
                var goal = dev.jkbuild.runtime.BuildPipeline.coreBuilder(inputs, m.dirty()).build();
                int weight = goal.estimatedTotalWeight();
                int testWeight = goal.phases().stream()
                        .filter(p -> p.name().equals("run-tests"))
                        .mapToInt(dev.jkbuild.run.Phase::estimateWeight).sum();
                costs.add(new dev.jkbuild.runtime.EffortWeights.ModuleCost(
                        mdir, graph.edges().getOrDefault(mdir, Set.of()), weight, testWeight));
            }
            int concurrency = Math.max(1,
                    Math.min(Runtime.getRuntime().availableProcessors(), modules.size()));
            etaMillis = dev.jkbuild.runtime.EffortWeights.scheduleMillis(costs, concurrency, serial, false);
        } catch (RuntimeException e) {
            etaMillis = 0;   // never fail explain over the estimate
        }

        // Header: a green " - Build Plan " chip (the same chip family as jk tree), capped
        // by a green ▶ segment arrow when nerdfont, then the build-time estimate (yellow).
        String header = nerdfont
                ? Theme.colorize(" - Build Plan ", t.goalSuccessChip())
                        + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD,
                                t.bright(t.goalChipColor()))
                : Theme.colorize(" - Build Plan ", t.goalSuccessChip());
        String estimate = "Build time estimate "
                + Theme.colorize("~" + fmtDuration(Math.max(1, etaMillis)), t.warning());
        System.out.println();
        System.out.println(header + " " + estimate);
        // Root node: ● bullet, then the entry project's group:artifact in bold.
        System.out.println(" " + Theme.colorize("●", t.darkGray()) + " "
                + boldCoord(entry.project().group() + ":" + entry.project().name(), t));

        // Partition the topo order by cache status: every fully-cached module (wherever
        // it sits in the order) collapses into the "Fully Cached" section (names only);
        // only the modules that actually rebuild appear in the detailed "Rebuild" section,
        // each keeping its topo index. --verbose expands every phase (cached ones too).
        boolean verbose = all;
        List<Integer> cachedIdx = new ArrayList<>();
        List<Integer> dirtyIdx = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            (modules.get(i).dirty() ? dirtyIdx : cachedIdx).add(i);
        }

        if (!cachedIdx.isEmpty()) {
            boolean lastSection = dirtyIdx.isEmpty();
            System.out.println(" " + Theme.colorize(lastSection ? "╰─" : "├─", t.darkGray())
                    + dev.jkbuild.cli.tui.Badge.pill("Fully Cached", nerdfont));
            String childPrefix = " " + Theme.colorize(lastSection ? "   " : "│  ", t.darkGray());
            if (verbose) {
                for (int j = 0; j < cachedIdx.size(); j++) {
                    int i = cachedIdx.get(j);
                    renderModuleRow(modules.get(i), i + 1, j == cachedIdx.size() - 1,
                            childPrefix, nerdfont, true, t);
                }
            } else {
                List<String> names = new ArrayList<>();
                for (int i : cachedIdx) names.add(":" + shortName(modules.get(i).unit().coord()));
                // Wrap the full list across lines (no truncation): the first line hangs off
                // a "╰─ " connector; continuations align under the first name.
                List<String> lines = wrapNames(names, Math.max(20, width - 7));
                String cont = childPrefix + "   ";   // align past "╰─ "
                for (int li = 0; li < lines.size(); li++) {
                    System.out.println((li == 0 ? childPrefix + Theme.colorize("╰─ ", t.darkGray()) : cont)
                            + renderCachedNames(lines.get(li), t));
                }
            }
        }
        if (!dirtyIdx.isEmpty()) {
            System.out.println(" " + Theme.colorize("╰─", t.darkGray())
                    + dev.jkbuild.cli.tui.Badge.pill("Rebuild", nerdfont));
            // First child of the section: the rebuild count.
            System.out.println("    " + Theme.colorize("├─ ", t.darkGray())
                    + "Rebuild " + Theme.colorize(Long.toString(rebuild), t.warning())
                    + " of " + total + " modules");
            for (int j = 0; j < dirtyIdx.size(); j++) {
                int i = dirtyIdx.get(j);
                renderModuleRow(modules.get(i), i + 1, j == dirtyIdx.size() - 1,
                        "    ", nerdfont, verbose, t);
            }
        }
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

    /**
     * Render one module row under a section: {@code prefix} + connector + index badge +
     * coordinate, then its phase sub-tree. The verdict is implied by the enclosing section
     * (Fully Cached / Rebuild) and the origin / dependency edges are omitted as noise.
     * Phases render when the module rebuilds, or always under {@code verbose}.
     */
    private static void renderModuleRow(BuildPlanForecast.Module m, int idx, boolean last,
                                        String prefix, boolean nerdfont, boolean verbose, Theme t) {
        System.out.println(prefix
                + Theme.colorize((last ? "╰" : "├") + "─", t.darkGray())
                + dev.jkbuild.cli.tui.Badge.pill(String.format("%02d", idx), nerdfont)
                + ' ' + coloredCoord(m.unit().coord(), t));

        if (m.dirty() || verbose) {
            String spine = prefix + (last ? "   " : Theme.colorize("│", t.darkGray()) + "  ");
            List<BuildPlanForecast.Phase> ph = m.phases();
            // Pad each □ phase's verb to the widest in this module so the · column lines up.
            int verbCol = 0;
            for (BuildPlanForecast.Phase p : ph) {
                if (!p.cached()) verbCol = Math.max(verbCol, verbWidth(p.text()));
            }
            for (int k = 0; k < ph.size(); k++) {
                boolean lp = k == ph.size() - 1;
                System.out.println(spine + Theme.colorize(lp ? "╰─ " : "├─ ", t.darkGray())
                        + Theme.colorize(padRight(ph.get(k).name(), PHASE_COL), t.brightWhite())
                        + "  " + renderStatus(ph.get(k), verbCol, t));
            }
        }
    }

    /** Visible width of a phase verb — the text before {@code " · "} (or the whole text). */
    private static int verbWidth(String text) {
        int sep = text.indexOf(" · ");
        return (sep < 0 ? text : text.substring(0, sep)).length();
    }

    /** The entry project's {@code group:artifact} in bold, each segment in its coord color. */
    private static String boldCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName().bold());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup().bold())
                + ":" + Theme.colorize(coord.substring(colon + 1), t.coordName().bold());
    }

    /** The artifact half of a {@code group:artifact} coordinate. */
    private static String shortName(String coord) {
        int c = coord.indexOf(':');
        return c < 0 ? coord : coord.substring(c + 1);
    }

    /** Color a comma-list of {@code :name} cached-module refs (the {@code …+N more…} marker dim). */
    private static String renderCachedNames(String elided, Theme t) {
        StringBuilder sb = new StringBuilder();
        String[] pieces = elided.split(", ");
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(Theme.colorize(", ", t.darkGray()));
            String p = pieces[i];
            if (p.matches("…\\+\\d+ more…")) {
                sb.append(Theme.colorize(p, t.darkGray()));
            } else if (p.startsWith(":")) {
                sb.append(Theme.colorize(":", t.darkGray()))
                        .append(Theme.colorize(p.substring(1), t.coordName()));
            } else {
                sb.append(Theme.colorize(p, t.coordName()));
            }
        }
        return sb.toString();
    }

    /**
     * A phase's status: {@code ✓ cached <key> · detail} (green) when cached, otherwise
     * {@code □ <verb> · <detail>} with the verb in yellow (padded to {@code verbCol} so
     * the {@code ·} lines up across the module's phases), the {@code ·} bright-black, and
     * the trailing detail in italic.
     */
    private static String renderStatus(BuildPlanForecast.Phase p, int verbCol, Theme t) {
        if (p.cached()) {
            StringBuilder s = new StringBuilder(Theme.colorize("✓ cached", t.success()));
            if (p.key() != null) s.append(' ').append(Theme.colorize(p.key(), t.path()));
            if (p.text() != null && !p.text().isEmpty())
                s.append(' ').append(Theme.colorize(p.text(), t.darkGray().italic()));
            return s.toString();
        }
        String text = p.text();
        int sep = text.indexOf(" · ");
        String verb = sep < 0 ? text : text.substring(0, sep);
        String detail = sep < 0 ? null : text.substring(sep + 3);
        StringBuilder s = new StringBuilder(Theme.colorize("□ ", t.brightWhite()))
                .append(Theme.colorize(padRight(verb, verbCol), t.warning()));
        if (detail != null) {
            s.append(' ').append(Theme.colorize("·", t.darkGray()))
                    .append(' ').append(Theme.colorize(detail, t.brightWhite().italic()));
        }
        return s.toString();
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
     * Greedily pack {@code tokens} into {@code ", "}-joined lines, each at most
     * {@code avail} visible columns wide (the wrap point drops the separator rather
     * than leaving a trailing comma). On a non-TTY {@code avail} is effectively
     * unbounded, so the whole list lands on one line.
     */
    static List<String> wrapNames(List<String> tokens, int avail) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            if (cur.length() == 0) {
                cur.append(tok);
            } else if (cur.length() + 2 + tok.length() <= avail) {
                cur.append(", ").append(tok);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(tok);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

}
