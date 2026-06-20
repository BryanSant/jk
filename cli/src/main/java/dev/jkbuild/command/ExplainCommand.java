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
            StringBuilder line = new StringBuilder()
                    .append(Theme.colorize((last ? "╰" : "├") + "─", t.darkGray()))
                    .append(Theme.colorize(String.format("%02d", i + 1), t.unitBadge()))
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
                line.append(' ').append(Theme.colorize("← " + String.join(", ", shown), t.darkGray()));
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
     * The {@code compile-main} phase node + its cache-status lines for a unit,
     * mirroring the build's javac action key, rendered under {@code spine} (the
     * unit's vertical rail). Two status lines: a cache hit/miss marker with the
     * action key, and a checkbox for the work — filled + struck when cached
     * (won't run), empty when it will run.
     */
    private static void explainCompile(Path dir, JkBuild project, Cas cas, ActionCache actionCache,
                                       String spine, Theme t) throws IOException {
        String phaseLine = spine + "    " + Theme.colorize("╰─ ", t.darkGray())
                + Theme.colorize("compile-main", t.brightWhite());
        String statusPrefix = spine + "       ";
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
        List<Path> classpath = new ClasspathResolver(cas).classpathFor(lock, ClasspathResolver.COMPILE_MAIN);
        int release = project.project().javaRelease();
        Path outputDir = BuildLayout.of(dir, project).classesDir();
        // Must mirror the build's javac args (incl. the default lint flags) or the
        // action key won't match what `jk build` cached — explain would always
        // report MISS. (No --profile here, so just the lint default + nothing.)
        CompileRequest request = CompileRequest.builder()
                .sources(sources).classpath(classpath).outputDir(outputDir).release(release)
                .extraOptions(dev.jkbuild.compile.JavacLint.effectiveArgs(project.build().lint(), List.of()))
                .build();
        String key = ActionKey.forJavac(ActionKey.qualifiedTaskId("compile-main", outputDir), request, Jk.VERSION);
        boolean hit = actionCache.lookup(key).isPresent();
        String shortKey = key.substring(0, 8);

        // Line 1: cache hit (green ✓) / miss (dim ✗), then the action key.
        System.out.println(statusPrefix
                + (hit ? Theme.colorize("✓", t.success()) + " cache hit: "
                       : Theme.colorize("✗", t.darkGray()) + " cache miss: ")
                + Theme.colorize(shortKey, t.path()));

        // Line 2: the work as a checkbox — filled + struck when cached (it won't
        // run), empty + plain when it will compile this build.
        String desc = sources.size() + " source" + (sources.size() == 1 ? "" : "s")
                + " (javac --release " + release + ")";
        System.out.println(statusPrefix + (hit
                ? "▰ " + Theme.colorize(desc, AttributedStyle.DEFAULT.crossedOut())
                : "▱ " + desc));
    }
}
