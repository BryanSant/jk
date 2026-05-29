// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.run.PhaseStatus;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
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
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Organised as a {@link Goal} with four phases:
 * <ol>
 *   <li>{@code parse-build} (SYNC) — load jk.toml + jk.lock + classpaths.</li>
 *   <li>{@code compile-main} (CPU) — compile src/main/java with the
 *       action-cache layer.</li>
 *   <li>{@code compile-test} (CPU) — compile src/test/java; requires
 *       compile-main.</li>
 *   <li>{@code run-tests} (IO) — fork the jk-test-runner JVM(s). The
 *       NDJSON event stream from the runner bridges into the Goal:
 *       each test completion is a {@code progress(1)} event, each
 *       failure becomes a {@code ctx.error}, the discovery total
 *       grows the denominator via {@code updateScope}.</li>
 * </ol>
 *
 * <p>{@code Goal.interactive} is true: the pinned {@link
 * dev.jkbuild.cli.tui.TestProgress} widget owns the foreground
 * rendering, so the Goal framework's ProgressBarListener stays out of
 * the way. The structured event log captures everything regardless of
 * what's rendered to the terminal.
 */
@Command(name = "test", description = "Compile and run tests")
public final class TestCommand implements Callable<Integer> {

    @Option(names = "--profile", paramLabel = "<name>",
            description = "Build profile to apply.")
    String profileName;

    @Option(names = {"-w", "--workers"}, paramLabel = "<N>",
            description = "Number of test-runner JVMs to fork in parallel. Each fork is fully "
                    + "process-isolated and pulls test classes from a shared queue. Default 1.")
    Integer workers;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDir;

    @picocli.CommandLine.Mixin GlobalOptions global;

    // Cross-phase keys.
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);
    private static final GoalKey<JkBuild> PROJECT = GoalKey.of("project", JkBuild.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> COMPILE_MAIN_CP = GoalKey.of("cp-main", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> COMPILE_TEST_CP = GoalKey.of("cp-test", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> TEST_RUNTIME_CP = GoalKey.of("cp-runtime", List.class);
    @SuppressWarnings("rawtypes")
    private static final GoalKey<List> JAVAC_ARGS = GoalKey.of("javac-args", List.class);
    private static final GoalKey<Path> JAVA_HOME = GoalKey.of("java-home", Path.class);
    private static final GoalKey<Integer> RELEASE = GoalKey.of("release", Integer.class);
    private static final GoalKey<Path> MAIN_CLASSES = GoalKey.of("main-classes", Path.class);
    private static final GoalKey<Path> TEST_CLASSES = GoalKey.of("test-classes", Path.class);
    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);
    private static final GoalKey<Boolean> NO_TEST_SOURCES =
            GoalKey.of("no-test-sources", Boolean.class);

    @Override
    public Integer call() throws IOException {
        Path dir = global.workingDir();
        Path buildFile = dir.resolve("jk.toml");
        Path lockFile = dir.resolve("jk.lock");
        if (!Files.exists(buildFile)) {
            System.err.println("jk test: no jk.toml in " + dir);
            return 2;
        }
        if (!Files.exists(lockFile)) {
            System.err.println("jk test: no jk.lock in " + dir + " (run `jk lock` first)");
            return 2;
        }

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Cas cas = new Cas(cache);
        int workerCount = workers != null && workers > 0 ? workers : 1;

        Phase parseBuild = Phase.builder("parse-build")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml / jk.lock");
                    JkBuild project;
                    try {
                        project = JkBuildParser.parse(buildFile);
                    } catch (RuntimeException e) {
                        ctx.error("toml", e.getMessage());
                        throw e;
                    }
                    ctx.put(PROJECT, project);
                    Lockfile lock = LockfileReader.read(lockFile);
                    ctx.put(LOCKFILE, lock);

                    ClasspathResolver resolver = new ClasspathResolver(cas);
                    ctx.put(COMPILE_MAIN_CP,
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_MAIN));
                    ctx.put(COMPILE_TEST_CP,
                            resolver.classpathFor(lock, ClasspathResolver.COMPILE_TEST));
                    ctx.put(TEST_RUNTIME_CP,
                            resolver.classpathFor(lock, ClasspathResolver.TEST));

                    Profile profile = CompileCommand.resolveProfile(project.profiles(), profileName);
                    ctx.put(JAVAC_ARGS, profile == null ? List.of() : profile.javacArgs());
                    ctx.put(JAVA_HOME, CompileToolchain.resolveJavaHome(dir));
                    ctx.put(RELEASE, project.project().javaRelease());

                    BuildLayout layout = BuildLayout.of(dir, project);
                    ctx.put(MAIN_CLASSES, layout.classesDir());
                    ctx.put(TEST_CLASSES, layout.testClassesDir());
                    ctx.progress(1);
                })
                .build();

        Phase compileMain = Phase.builder("compile-main")
                .kind(PhaseKind.CPU)
                .requires("parse-build")
                .scope(1)
                .execute(ctx -> {
                    @SuppressWarnings("unchecked")
                    List<Path> cp = (List<Path>) ctx.require(COMPILE_MAIN_CP);
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    boolean ok = compileWithCache(ctx, "compile-main",
                            dir.resolve("src/main/java"),
                            ctx.require(MAIN_CLASSES),
                            cp, ctx.require(RELEASE), javacArgs,
                            ctx.require(JAVA_HOME), cas, cache);
                    if (!ok) throw new RuntimeException("main compile failed");
                    ctx.progress(1);
                })
                .build();

        Phase compileTest = Phase.builder("compile-test")
                .kind(PhaseKind.CPU)
                .requires("compile-main")
                .scope(1)
                .execute(ctx -> {
                    Path srcTest = dir.resolve("src/test/java");
                    if (CompileCommand.collectJavaSources(srcTest).isEmpty()) {
                        ctx.label("no test sources");
                        ctx.put(NO_TEST_SOURCES, true);
                        ctx.progress(1);
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> compileTestCp = (List<Path>) ctx.require(COMPILE_TEST_CP);
                    List<Path> fullCp = new ArrayList<>();
                    fullCp.add(ctx.require(MAIN_CLASSES));
                    fullCp.addAll(compileTestCp);
                    @SuppressWarnings("unchecked")
                    List<String> javacArgs = (List<String>) ctx.require(JAVAC_ARGS);
                    boolean ok = compileWithCache(ctx, "compile-test",
                            srcTest, ctx.require(TEST_CLASSES), fullCp,
                            ctx.require(RELEASE), javacArgs,
                            ctx.require(JAVA_HOME), cas, cache);
                    if (!ok) throw new RuntimeException("test compile failed");
                    ctx.progress(1);
                })
                .build();

        Phase runTests = Phase.builder("run-tests")
                .kind(PhaseKind.IO)
                .requires("compile-test")
                .scope(0)            // grown via updateScope on discovery total
                .execute(ctx -> {
                    if (ctx.get(NO_TEST_SOURCES).orElse(false)) {
                        ctx.label("no tests to run");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    List<Path> testRuntimeCp = (List<Path>) ctx.require(TEST_RUNTIME_CP);
                    List<Path> runtimeClasspath = new ArrayList<>();
                    runtimeClasspath.add(ctx.require(MAIN_CLASSES));
                    runtimeClasspath.addAll(testRuntimeCp);

                    // TestProgress is the foreground rendering owner —
                    // pinned status row + writeAbove for user output /
                    // failures. The Goal framework stays out of the way
                    // because the goal is marked interactive.
                    var progress = dev.jkbuild.cli.tui.TestProgress.start(System.out);
                    TestProgressListener listener = bridgeListener(ctx, progress, workerCount);

                    JUnitLauncher.Result result;
                    try {
                        result = new JUnitLauncher().run(
                                ctx.require(JAVA_HOME),
                                ctx.require(TEST_CLASSES),
                                runtimeClasspath,
                                cache, workerCount, listener);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        progress.writeAbove("jk test: interrupted");
                        progress.close();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        progress.writeAbove("jk test: " + e.getMessage());
                        progress.close();
                        ctx.error("test", e.getMessage());
                        throw e;
                    }

                    ctx.put(TEST_RESULT, result);
                    if (result.allPassed()) {
                        progress.finishSuccess();
                    } else {
                        progress.finishFailure(result.failed());
                        // Throwing here marks the phase FAIL so the
                        // event log captures the verdict; the outer
                        // exit-code logic still distinguishes "test
                        // failure" (exit 4) from "phase exception".
                        throw new RuntimeException(result.failed() + " test failure"
                                + (result.failed() == 1 ? "" : "s"));
                    }
                })
                .build();

        Goal goal = Goal.builder("test")
                .interactive(true)   // TestProgress owns the foreground
                .addPhase(parseBuild)
                .addPhase(compileMain)
                .addPhase(compileTest)
                .addPhase(runTests)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            return 0;
        }
        // Test failures get exit 4 (PRD §6); compile / launcher errors are
        // exit 1.
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) {
            return 4;
        }
        // Failure UX owned by the listener.
        return 1;
    }

    /**
     * Wraps both the {@link dev.jkbuild.cli.tui.TestProgress} widget
     * (pinned-row UI) and the Goal's {@link PhaseContext} (structured
     * events). Each test completion ticks both: the widget's counter
     * advances and the goal's numerator grows. The discovery total
     * grows the goal's denominator via {@code updateScope} so the
     * event log has accurate scope numbers even though the visual UI
     * is owned by TestProgress.
     */
    static TestProgressListener bridgeListener(
            PhaseContext ctx,
            dev.jkbuild.cli.tui.TestProgress progress,
            int workerCount) {
        return new TestProgressListener() {
            @Override
            public void onDiscoveryTotal(int classes, int tests) {
                progress.setTotal(tests);
                if (tests > 0) ctx.updateScope(tests);
            }

            @Override
            public void onTestFinished(String id, String display, String status,
                                       boolean isTest, long durationMs, int workerId) {
                if (!isTest) return;
                progress.incrementCompleted();
                ctx.progress(1);
                ctx.label(display);
            }

            @Override
            public void onTestSkipped(String id, String display, String reason,
                                      boolean isTest, int workerId) {
                if (!isTest) return;
                progress.incrementCompleted();
                ctx.progress(1);
            }

            @Override
            public void onFailure(String id, String display, String exClass,
                                  String message, int workerId) {
                progress.writeAbove("FAIL: " + display + " — " + exClass
                        + (message.isEmpty() ? "" : ": " + message));
                ctx.error("test", display + ": " + exClass
                        + (message.isEmpty() ? "" : " — " + message));
            }

            @Override
            public void onUserOutput(int workerId, String line) {
                String prefix = workerCount > 1 ? "[w" + workerId + "] " : "";
                progress.writeAbove(prefix + line);
                // Deliberately NOT routed to the event log — test
                // println output would drown the structured stream.
            }
        };
    }

    /**
     * Compile sources with action-cache lookup. Shared by the compile-main
     * and compile-test phases — each calls it with its own task ID, source
     * dir, classpath, and output dir.
     */
    static boolean compileWithCache(
            PhaseContext ctx,
            String taskId,
            Path srcDir,
            Path outputDir,
            List<Path> classpath,
            int release,
            List<String> javacArgs,
            Path javaHome,
            Cas cas,
            Path cacheRoot) throws IOException {

        List<Path> sources = CompileCommand.collectJavaSources(srcDir);
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

        boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
        java.util.Optional<ActionCache.ActionRecord> cached =
                noCache ? java.util.Optional.empty() : actionCache.lookup(actionKey);
        if (cached.isPresent()) {
            actionCache.restore(cached.get(), outputDir);
            ctx.label(taskId + ": cache hit " + actionKey.substring(0, 8));
            return true;
        }
        ctx.label(taskId + ": compiling " + sources.size() + " sources");
        CompileResult result = new JavacDriver().compile(request);
        for (CompileResult.Diagnostic d : result.diagnostics()) {
            ctx.error("javac", d.render());
        }
        if (!result.success() || result.hasErrors()) return false;
        actionCache.store(taskId, actionKey, ActionKey.snapshotInputs(request), outputDir);
        return true;
    }
}
