// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compile.ClasspathResolver;
import dev.buildjk.compile.CompileRequest;
import dev.buildjk.compile.CompileResult;
import dev.buildjk.compile.JavacDriver;
import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.task.ActionCache;
import dev.buildjk.task.ActionKey;
import dev.buildjk.test.JUnitLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>v0.2 first iteration: in-process JUnit launch. JVM-forking, test
 * source sets, parallel workers, JUnit XML / SARIF / JaCoCo reporting,
 * and per-scope lockfile entries all land in subsequent slices.
 */
@Command(name = "test", description = "Compile and run tests")
public final class TestCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply.")
    String profileName;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the CAS cache directory. Default: ~/.jk/cache.")
    Path cacheDir;

    @Override
    public Integer call() throws IOException {
        Path dir = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildFile = dir.resolve("build.jk");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk test: no build.jk in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk test: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        BuildJk project;
        try {
            project = BuildJkParser.parse(buildFile);
        } catch (RuntimeException e) {
            System.err.println("jk test: " + e.getMessage());
            return 2;
        }
        Lockfile lock = LockfileReader.read(lockFile);

        Path cache = cacheDir != null
                ? cacheDir
                : Path.of(System.getProperty("user.home"), ".jk", "cache");
        Cas cas = new Cas(cache);
        ClasspathResolver classpathResolver = new ClasspathResolver(cas);
        List<Path> compileMainCp = classpathResolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN);
        List<Path> compileTestCp = classpathResolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST);
        List<Path> testRuntimeCp = classpathResolver.classpathFor(lock, ClasspathResolver.TEST);
        int release = CheckCommand.parseReleaseFromJdk(project.project().jdk());

        Path target = dir.resolve("target");
        Path mainClasses = target.resolve("classes");
        Path testClasses = target.resolve("test-classes");

        dev.buildjk.model.Profile profile =
                CheckCommand.resolveProfile(project.profiles(), profileName);
        List<String> javacArgs = profile == null ? List.of() : profile.javacArgs();

        Path javaHome = CompileToolchain.resolveJavaHome(dir);

        // 1. Compile main sources (main + provided scope on the classpath).
        boolean ok = compileWithCache(
                "compile-main",
                dir.resolve("src/main/java"),
                mainClasses,
                compileMainCp,
                release, javacArgs, javaHome, cas, cache);
        if (!ok) return 1;

        // 2. Compile test sources (main classes + main + provided + test scope).
        Path srcTest = dir.resolve("src/test/java");
        if (CheckCommand.collectJavaSources(srcTest).isEmpty()) {
            System.out.println("jk test: no test sources in src/test/java");
            return 0;
        }
        List<Path> compileTestFullCp = new ArrayList<>();
        compileTestFullCp.add(mainClasses);
        compileTestFullCp.addAll(compileTestCp);
        ok = compileWithCache(
                "compile-test",
                srcTest, testClasses,
                compileTestFullCp,
                release, javacArgs, javaHome, cas, cache);
        if (!ok) return 1;

        // 3. Run JUnit Platform on the test-runtime classpath (main + runtime + test).
        List<Path> runtimeClasspath = new ArrayList<>();
        runtimeClasspath.add(mainClasses);
        runtimeClasspath.addAll(testRuntimeCp);
        JUnitLauncher.Result result = new JUnitLauncher().run(testClasses, runtimeClasspath);

        System.out.println("Tests: " + result.succeeded() + " passed, "
                + result.failed() + " failed, "
                + result.skipped() + " skipped"
                + " (of " + result.total() + ")");
        for (JUnitLauncher.Failure failure : result.failures()) {
            System.err.println("FAIL: " + failure.testName() + " — " + failure.message());
        }
        return result.allPassed() ? 0 : 4; // 4 = test failure per PRD §6
    }

    /** Compile sources with action-cache lookup. Shared with {@link BuildCommand}. */
    static boolean compileWithCache(
            String taskId,
            Path srcDir,
            Path outputDir,
            List<Path> classpath,
            int release,
            List<String> javacArgs,
            Path javaHome,
            Cas cas,
            Path cacheRoot) throws IOException {

        List<Path> sources = CheckCommand.collectJavaSources(srcDir);
        if (sources.isEmpty()) {
            Files.createDirectories(outputDir);
            return true;
        }

        CompileRequest request = CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .extraOptions(javacArgs)
                .javaHome(javaHome)
                .build();
        String actionKey = ActionKey.forJavac(taskId, request, Jk.VERSION);
        ActionCache actionCache = new ActionCache(cas, cacheRoot.resolve("actions"));

        var cached = actionCache.lookup(actionKey);
        if (cached.isPresent()) {
            actionCache.restore(cached.get(), outputDir);
            System.out.println("Cache hit: " + taskId + " (" + actionKey.substring(0, 8) + ")");
            return true;
        }
        CompileResult result = new JavacDriver().compile(request);
        for (CompileResult.Diagnostic d : result.diagnostics()) {
            System.err.println(d.render());
        }
        if (!result.success() || result.hasErrors()) return false;
        actionCache.store(taskId, actionKey, ActionKey.snapshotInputs(request), outputDir);
        return true;
    }
}
