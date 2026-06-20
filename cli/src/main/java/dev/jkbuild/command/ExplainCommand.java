// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.Jk;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.runtime.BuildGraph;
import dev.jkbuild.runtime.CompileSupport;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk explain} — print the unified build plan (the same {@link BuildGraph}
 * the build driver uses): every build unit (workspace root + members + transitive
 * {@code path} / branch-git deps) in dependency order, its origin, what it
 * depends on, and each unit's compile cache status. {@code --run} executes the
 * same plan ({@code jk build}). Per PRD §25.1.
 */
public final class ExplainCommand implements CliCommand {

    @Override public String name() { return "explain"; }
    @Override public String description() { return "Print the planned build (units, order, cache hit/miss)"; }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Build the plan instead of just printing it (same as `jk build`).", "--run"),
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

        List<BuildGraph.BuildUnit> order = graph.topoOrder();
        int units = order.size();
        // Header: a leading blank line, then a left-flush cyan powerline segment —
        // " Build Plan for <coord> " on the cyan chip, capped on the right by a ▶ segment
        // arrow (painted in the chip color over the default background). A white phase count
        // follows, then a trunk rail.
        String title = " Build Plan for " + entry.project().group() + ":" + entry.project().name() + " ";
        String header = nerdfont
                ? Theme.colorize(title, t.cyanBadge())
                        + Theme.colorize(dev.jkbuild.cli.tui.Glyphs.SEGMENT_END_NERD, t.cyan())
                : Theme.colorize(title, t.cyanBadge());
        System.out.println();
        System.out.println(header
                + " " + Theme.colorize(units + " total phases", t.brightWhite()));
        System.out.println(Theme.colorize("│", t.darkGray()));

        for (int i = 0; i < order.size(); i++) {
            BuildGraph.BuildUnit u = order.get(i);
            boolean last = i == order.size() - 1;
            // The vertical spine under this unit: a rail for non-last units, blank
            // for the last one (the tree closes).
            String spine = last ? " " : Theme.colorize("│", t.darkGray());

            String origin = switch (u.origin()) {
                case ROOT -> "root";
                case MEMBER -> "member";
                case PATH -> "path dep";
                case BRANCH_GIT -> "branch git dep";
            };
            String idx = String.format("%02d", i + 1);
            StringBuilder line = new StringBuilder()
                    .append(Theme.colorize((last ? "╰" : "├") + "─", t.darkGray()))
                    .append(dev.jkbuild.cli.tui.Badge.pill(idx, nerdfont))
                    .append(' ').append(coloredCoord(u.coord(), t))
                    .append(' ').append(Theme.colorize(origin, t.darkGray()));
            List<String> prereqs = new ArrayList<>();
            for (Path p : graph.edges().getOrDefault(u.dir(), Set.of())) {
                String c = coordByDir.get(p);
                if (c != null) prereqs.add(abbreviate(c, u.coord())); // same-group → :name
            }
            if (!prereqs.isEmpty()) {
                // Visible width before " ← " — connector(2) + index pill (idx + 2 caps/pads)
                // + space + coord + space + origin. Caps/pads are width-1.
                int prefixCols = 2 + (idx.length() + 2) + 1 + u.coord().length()
                        + 1 + origin.length();
                line.append(' ').append(renderDeps(elideDeps(prereqs, width - prefixCols - 3), t));
            }
            System.out.println(line);
            explainCompile(u.dir(), u.manifest(), cas, actionCache, cache, spine, t);
        }
        return 0;
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

    /**
     * The {@code compile-main} phase node + a single status line for a unit,
     * rendered under {@code spine} (the unit's vertical rail). The status predicts
     * — via {@link dev.jkbuild.task.JavaIncrementalCompile#predict} on the same
     * inputs the build uses — whether the build would restore from cache, or do a
     * full or partial (incremental) compile, and over how many sources.
     */
    private static void explainCompile(Path dir, JkBuild project, Cas cas, ActionCache actionCache,
                                       Path cache, String spine, Theme t) throws IOException {
        System.out.println(spine + "  " + Theme.colorize("╰─ ", t.darkGray())
                + Theme.colorize("compile-main", t.focused()));   // bold + white
        String statusPrefix = spine + "     ";

        Path lockFile = dir.resolve("jk.lock");
        if (!Files.isRegularFile(lockFile)) {
            System.out.println(statusPrefix + Theme.colorize("not locked yet (run `jk build`)", t.darkGray()));
            return;
        }
        boolean simple = CompileSupport.isSimpleLayout(project.project(), dir);
        Path srcDir = simple ? dir.resolve("src") : dir.resolve("src/main/java");
        List<Path> sources = CompileSupport.collectJavaSources(srcDir);
        if (sources.isEmpty()) {
            System.out.println(statusPrefix + Theme.colorize("no Java sources", t.darkGray()));
            return;
        }

        Lockfile lock = LockfileReader.read(lockFile);
        ClasspathResolver resolver = new ClasspathResolver(cas);
        // Mirror the build's compile-main inputs EXACTLY (via the shared helper) or the
        // action key won't match what `jk build` cached — explain would report a false
        // MISS. The classpath must include workspace sibling jars + their transitive
        // lockfile deps, not just this module's own lockfile; the processor path and
        // javac args (default lint, no --profile) round out the request.
        dev.jkbuild.config.WorkspaceClasspath.Result siblings =
                dev.jkbuild.config.WorkspaceClasspath.resolve(dir, project, Set.of(
                        dev.jkbuild.model.Scope.EXPORT, dev.jkbuild.model.Scope.MAIN));
        List<Path> classpath = dev.jkbuild.runtime.BuildPipeline.mainCompileClasspath(lock, resolver, siblings);
        List<Path> processorCp = resolver.classpathFor(lock, Set.of(dev.jkbuild.model.Scope.PROCESSOR));
        Path outputDir = BuildLayout.of(dir, project).classesDir();
        CompileRequest request = CompileRequest.builder()
                .sources(sources).classpath(classpath).outputDir(outputDir).release(project.project().javaRelease())
                .extraOptions(dev.jkbuild.compile.JavacLint.effectiveArgs(project.build().lint(), List.of()))
                .processorPath(processorCp)
                .build();
        String taskId = ActionKey.qualifiedTaskId("compile-main", outputDir);
        Path stateDir = cache.resolve("actions").resolve("incremental-java").resolve(taskId);
        var pred = dev.jkbuild.task.JavaIncrementalCompile.predict(taskId, request, Jk.VERSION, actionCache, stateDir);
        int n = pred.sourceCount();
        String sourceLabel = n + " source" + (n == 1 ? "" : "s");
        String line = switch (pred.outcome()) {
            case CACHE_HIT -> Theme.colorize("✓ Skip", t.success())
                    + ": cache found for " + Theme.colorize(pred.actionKey().substring(0, 8), t.path())
                    + ", no compile necessary";
            case INCREMENTAL -> Theme.colorize("□ Partial compile", t.brightWhite())
                    + " for " + sourceLabel + " required";
            case FULL -> Theme.colorize("□ Full compile", t.brightWhite())
                    + " for " + sourceLabel + " required";
        };
        System.out.println(statusPrefix + line);
    }
}
