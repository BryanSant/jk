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
import org.jline.utils.AttributedStyle;

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
        System.out.println(Theme.colorize(
                "Build Plan for " + entry.project().group() + ":" + entry.project().name(),
                t.activeStep().underline())
                + " (" + units + " unit" + (units == 1 ? "" : "s") + ", dependency order):");

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
                    .append(' ').append(Theme.colorize("[" + origin + "]", t.darkGray()));
            List<String> prereqs = new ArrayList<>();
            for (Path p : graph.edges().getOrDefault(u.dir(), Set.of())) {
                String c = coordByDir.get(p);
                if (c != null) prereqs.add(c);
            }
            if (!prereqs.isEmpty()) {
                // A single prereq is shown in full; multiple are abbreviated to
                // ":name" (same-group) so the list stays readable.
                List<String> shown = prereqs.size() == 1 ? prereqs
                        : prereqs.stream().map(c -> abbreviate(c, u.coord())).toList();
                // Visible width before " ← " — connector(2) + badge(idx + 2 caps/pads)
                // + space + coord + space + "[origin]". Caps/pads are width-1.
                int prefixCols = 2 + (idx.length() + 2) + 1 + u.coord().length()
                        + 1 + (origin.length() + 2);
                line.append(' ').append(Theme.colorize(
                        "← " + elideDeps(shown, width - prefixCols - 3), t.darkGray()));
            }
            System.out.println(line);
            explainCompile(u.dir(), u.manifest(), cas, actionCache, spine, t);
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
     * {@code …N…} marker, where {@code N} is the count of remaining units that didn't
     * fit. {@code available} is effectively unbounded on a non-TTY, so the full list
     * is shown there.
     */
    static String elideDeps(List<String> units, int available) {
        String full = String.join(", ", units);
        if (available <= 0 || units.size() <= 1 || full.length() <= available) return full;
        String best = "…" + units.size() + "…"; // marker-only, if even one unit won't fit
        for (int k = 1; k < units.size(); k++) {
            String candidate = String.join(", ", units.subList(0, k))
                    + ", …" + (units.size() - k) + "…";
            if (candidate.length() > available) break; // front grows monotonically
            best = candidate;
        }
        return best;
    }

    /**
     * The {@code compile-main} phase node + its cache-status lines for a unit,
     * mirroring the build's javac action key, rendered under {@code spine} (the
     * unit's vertical rail). Two status lines: a cache hit/miss marker with the
     * action key, and a checkbox for the work — filled + struck when cached
     * (won't run), empty when it will run.
     */
    private static void explainCompile(Path dir, JkBuild project, Cas cas, ActionCache actionCache,
                                       String spine, Theme t) throws IOException {
        String phaseLine = spine + "  " + Theme.colorize("╰─ ", t.darkGray())
                + Theme.colorize("compile-main", t.brightWhite());
        String statusPrefix = spine + "     ";
        System.out.println(phaseLine);

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
        int release = project.project().javaRelease();
        Path outputDir = BuildLayout.of(dir, project).classesDir();
        CompileRequest request = CompileRequest.builder()
                .sources(sources).classpath(classpath).outputDir(outputDir).release(release)
                .extraOptions(dev.jkbuild.compile.JavacLint.effectiveArgs(project.build().lint(), List.of()))
                .processorPath(processorCp)
                .build();
        String key = ActionKey.forJavac(ActionKey.qualifiedTaskId("compile-main", outputDir), request, Jk.VERSION);
        boolean hit = actionCache.lookup(key).isPresent();
        String shortKey = key.substring(0, 8);

        // Line 1: cache hit (green ✓) / miss (dim ✗), then the action key.
        System.out.println(statusPrefix
                + (hit ? Theme.colorize("✓", t.success()) + " cache hit: "
                       : Theme.colorize("✗", t.darkGray()) + " cache miss: ")
                + Theme.colorize(shortKey, t.path()));

        // Line 2: the work as a checkbox — a closed box + struck text when cached
        // (it won't run), an open box + plain text when it will compile this build.
        String desc = sources.size() + " source" + (sources.size() == 1 ? "" : "s")
                + " (javac --release " + release + ")";
        System.out.println(statusPrefix + (hit
                ? "■ " + Theme.colorize(desc, AttributedStyle.DEFAULT.crossedOut())
                : "□ " + desc));
    }
}
