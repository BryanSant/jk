// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.Jk;
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
            System.err.println("jk explain: no jk.toml in " + startDir);
            return 2;
        }
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        BuildGraph.Result graph = BuildGraph.resolve(startDir, entry, cache.resolve("git"));
        if (graph.hasErrors()) {
            for (String err : graph.errors()) System.err.println("error[composite]: " + err);
            return 2;
        }

        Theme t = Theme.active();
        Map<Path, String> coordByDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) coordByDir.put(u.dir(), u.coord());

        int units = graph.topoOrder().size();
        System.out.println("build plan for " + Theme.colorize(entry.project().group()
                + ":" + entry.project().name(), t.brightCyan())
                + " (" + units + " unit" + (units == 1 ? "" : "s") + ", dependency order):");

        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            String origin = switch (u.origin()) {
                case ROOT -> "root";
                case MEMBER -> "member";
                case PATH -> "path dep";
                case BRANCH_GIT -> "branch git dep";
            };
            StringBuilder line = new StringBuilder("  ")
                    .append(Theme.colorize(u.coord(), t.highlight()))
                    .append("  ").append(Theme.colorize("[" + origin + "]", t.darkGray()));
            List<String> prereqs = new ArrayList<>();
            for (Path p : graph.edges().getOrDefault(u.dir(), Set.of())) {
                String c = coordByDir.get(p);
                if (c != null) prereqs.add(c);
            }
            if (!prereqs.isEmpty()) {
                line.append("  ").append(Theme.colorize("← " + String.join(", ", prereqs), t.darkGray()));
            }
            System.out.println(line);
            explainCompile(u.dir(), u.manifest(), cas, actionCache);
        }
        return 0;
    }

    /** One {@code compile-main} cache-status line for a unit, mirroring the build's javac action key. */
    private static void explainCompile(Path dir, JkBuild project, Cas cas, ActionCache actionCache)
            throws IOException {
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.isRegularFile(lockFile)) {
            System.out.println("      " + "compile-main: not locked yet (run `jk build`)");
            return;
        }
        boolean simple = CompileSupport.isSimpleLayout(project.project(), dir);
        Path srcDir = simple ? dir.resolve("src") : dir.resolve("src/main/java");
        List<Path> sources = CompileSupport.collectJavaSources(srcDir);
        if (sources.isEmpty()) return;

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
        String status = actionCache.lookup(key).isPresent() ? "HIT" : "MISS";
        System.out.println("      compile-main: " + sources.size()
                + " source" + (sources.size() == 1 ? "" : "s")
                + ", javac --release " + release + "  [" + status + " " + key.substring(0, 8) + "]");
    }
}
