// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;

/**
 * {@code jk why-rebuilt &lt;task&gt;} — diff a task's current inputs against
 * the last recorded action. Tells the user which file changed, was added,
 * or was removed since the previous run. Matches PRD §25.3.
 *
 * <p>v0.2 supports {@code compile-main} and {@code compile-test}. As more
 * tasks become first-class, they can register their input-snapshot
 * builders here.
 */
@Command(name = "why-rebuilt", description = "Diff a task's current inputs against the last recorded action")
public final class WhyRebuiltCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<task>",
            description = "Task id (e.g. compile-main, compile-test).")
    String taskId;    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile) || !Files.exists(lockFile)) {
            System.err.println("jk why-rebuilt: project must have jk.toml and jk.lock");
            return 2;
        }
        JkBuild project = JkBuildParser.parse(buildFile);
        Lockfile lock = LockfileReader.read(lockFile);

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));

        Optional<ActionCache.ActionRecord> last = actionCache.lastFor(taskId);
        if (last.isEmpty()) {
            System.out.println("jk why-rebuilt: no previous run of " + taskId + " is cached");
            return 0;
        }

        CompileRequest currentRequest = buildRequest(taskId, project, lock, cas, dir);
        if (currentRequest == null) {
            System.err.println("jk why-rebuilt: unknown task `" + taskId + "`");
            return 64; // EX_USAGE
        }
        String currentKey = ActionKey.forJavac(taskId, currentRequest, Jk.VERSION);
        if (currentKey.equals(last.get().actionKey())) {
            System.out.println("task `" + taskId + "` has no input changes — next run would be a cache hit.");
            return 0;
        }

        Map<String, String> previousInputs = last.get().inputs();
        Map<String, String> currentInputs = ActionKey.snapshotInputs(currentRequest);

        System.out.println("task `" + taskId + "` would rebuild because input hash changed.");
        Set<String> allKeys = new TreeSet<>(previousInputs.keySet());
        allKeys.addAll(currentInputs.keySet());

        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String key : allKeys) {
            String previous = previousInputs.get(key);
            String current = currentInputs.get(key);
            if (previous == null) added.add(key);
            else if (current == null) removed.add(key);
            else if (!previous.equals(current)) changed.add(key);
        }

        if (!changed.isEmpty()) {
            System.out.println("\ninputs that changed since the last cached run:");
            for (String key : changed) {
                System.out.println("  " + key);
                System.out.println("    last:    " + previousInputs.get(key));
                System.out.println("    current: " + currentInputs.get(key));
            }
        }
        if (!added.isEmpty()) {
            System.out.println("\ninputs added since the last cached run:");
            for (String key : added) System.out.println("  " + key);
        }
        if (!removed.isEmpty()) {
            System.out.println("\ninputs removed since the last cached run:");
            for (String key : removed) System.out.println("  " + key);
        }
        return 0;
    }

    /** Build the {@link CompileRequest} that would be used for the named task. */
    private static CompileRequest buildRequest(
            String taskId, JkBuild project, Lockfile lock, Cas cas, Path dir) throws IOException {

        ClasspathResolver classpathResolver = new ClasspathResolver(cas);
        List<Path> lockClasspath = classpathResolver.classpathFor(lock);
        int release = project.project().javaRelease();

        return switch (taskId) {
            case "compile-main" -> {
                Path src = dir.resolve("src/main/java");
                List<Path> sources = CompileCommand.collectJavaSources(src);
                yield CompileRequest.builder()
                        .sources(sources)
                        .classpath(lockClasspath)
                        .outputDir(dir.resolve("target/classes"))
                        .release(release)
                        .build();
            }
            case "compile-test" -> {
                Path src = dir.resolve("src/test/java");
                List<Path> sources = CompileCommand.collectJavaSources(src);
                List<Path> cp = new ArrayList<>();
                cp.add(dir.resolve("target/classes"));
                cp.addAll(lockClasspath);
                yield CompileRequest.builder()
                        .sources(sources)
                        .classpath(cp)
                        .outputDir(dir.resolve("target/test-classes"))
                        .release(release)
                        .build();
            }
            default -> null;
        };
    }
}
