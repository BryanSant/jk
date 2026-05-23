// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.config.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.task.ActionCache;
import dev.buildjk.task.ActionKey;
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
public final class ExplainCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk explain: project must have build.jk and jk.lock (run `jk lock` first)");
            return 2;
        }
        BuildJk project = BuildJkParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        List<Path> lockClasspath = new ClasspathResolver(cas).classpathFor(lock);
        int release = CompileCommand.parseReleaseFromJdk(project.project().jdk());

        System.out.println("build plan for " + project.project().artifact()
                + " v" + project.project().version() + ":");

        Path mainClasses = dir.resolve("target/classes");
        explainCompile(dir.resolve("src/main/java"), "compile-main",
                lockClasspath, release, mainClasses, actionCache);

        List<Path> testClasspath = new ArrayList<>();
        testClasspath.add(mainClasses);
        testClasspath.addAll(lockClasspath);
        explainCompile(dir.resolve("src/test/java"), "compile-test",
                testClasspath, release, dir.resolve("target/test-classes"), actionCache);

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
        String key = ActionKey.forJavac(taskId, request, Jk.VERSION);
        String status = cache.lookup(key).isPresent() ? "HIT" : "MISS";
        System.out.println("  " + taskId + ": " + sources.size()
                + " source" + (sources.size() == 1 ? "" : "s")
                + ", javac --release " + release
                + "  [" + status + " " + key.substring(0, 8) + "]");
    }
}
