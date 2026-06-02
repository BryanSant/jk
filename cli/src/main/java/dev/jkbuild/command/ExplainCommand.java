// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk explain} — print the planned build with each task's action
 * cache status. Per PRD §25.1, the "build plan" view.
 *
 * <p>v0.2 first iteration: walks the v0.2 task set (compile-main,
 * compile-test). Full action-graph rendering arrives once tasks
 * become first-class.
 */
@Command(name = "explain", description = "Print the planned build with cache hit/miss")
public final class ExplainCommand implements Callable<Integer> {    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk explain: project must have jk.toml and jk.lock (run `jk lock` first)");
            return 2;
        }
        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        List<Path> lockClasspath = new ClasspathResolver(cas).classpathFor(lock);
        int release = project.project().javaRelease();

        System.out.println("build plan for " + project.project().artifact()
                + " v" + project.project().version() + ":");

        BuildLayout layout = BuildLayout.of(dir, project);
        Path mainClasses = layout.classesDir();
        explainCompile(dir.resolve("src/main/java"), "compile-main",
                lockClasspath, release, mainClasses, actionCache);

        List<Path> testClasspath = new ArrayList<>();
        testClasspath.add(mainClasses);
        testClasspath.addAll(lockClasspath);
        explainCompile(dir.resolve("src/test/java"), "compile-test",
                testClasspath, release, layout.testClassesDir(), actionCache);

        return 0;
    }

    private static void explainCompile(
            Path srcDir,
            String taskId,
            List<Path> classpath,
            int release,
            Path outputDir,
            ActionCache cache) throws IOException {

        List<Path> sources = CompileCommand.collectJavaSources(srcDir);
        if (sources.isEmpty()) {
            return;
        }
        CompileRequest request = CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .build();
        // Qualify identically to the build so the looked-up key matches.
        String key = ActionKey.forJavac(
                ActionKey.qualifiedTaskId(taskId, outputDir), request, Jk.VERSION);
        String status = cache.lookup(key).isPresent() ? "HIT" : "MISS";
        System.out.println("  " + taskId + ": " + sources.size()
                + " source" + (sources.size() == 1 ? "" : "s")
                + ", javac --release " + release
                + "  [" + status + " " + key.substring(0, 8) + "]");
    }
}
