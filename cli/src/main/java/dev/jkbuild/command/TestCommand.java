// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

import dev.jkbuild.cli.GlobalOptions;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
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
import dev.jkbuild.task.IncrementalCompile;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * <p>Output renders through the shared {@link
 * dev.jkbuild.cli.run.ProgressBarListener} — same bar shape as
 * {@code jk compile} and {@code jk build}. {@link #bridgeListener}
 * translates the test-runner's NDJSON events into {@code progress},
 * {@code label}, and {@code error} calls on the {@link PhaseContext}.
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

        // Pre-discover an estimated test count by scanning src/test/java
        // for @Test (and friends). The runTests phase is built with this
        // count as its scope, so the goal's denominator is set ONCE
        // up-front and never has to be reshaped mid-run. Bar climbs
        // smoothly from 0 across all phases instead of flashing 100%
        // when the early compile phases finish.
        int estimatedTestCount = estimateTestCount(dir.resolve("src/test/java"));

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
                .label("Testing")
                .kind(PhaseKind.IO)
                .requires("compile-test")
                // Scope is the upfront lexical estimate. We deliberately
                // don't reshape this via updateScope at runtime — the
                // numerator climbs through static-only test finishes and
                // the phase-end auto-fill closes any residual gap.
                .scope(estimatedTestCount)
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

                    TestProgressListener listener =
                            bridgeListener(ctx, workerCount, global.verbose);

                    JUnitLauncher.Result result;
                    try {
                        result = new JUnitLauncher().run(
                                ctx.require(JAVA_HOME),
                                ctx.require(TEST_CLASSES),
                                runtimeClasspath,
                                cache, workerCount, listener);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        ctx.error("test", "interrupted");
                        throw new RuntimeException(e);
                    } catch (IOException e) {
                        ctx.error("test", e.getMessage());
                        throw e;
                    }

                    ctx.put(TEST_RESULT, result);
                    if (!result.allPassed()) {
                        // Throw to mark the phase FAIL so the event log
                        // captures the verdict and ProgressBarListener
                        // repaints the bar in the failure gradient. The
                        // outer exit-code logic still distinguishes "test
                        // failure" (exit 4) from "phase exception".
                        throw new RuntimeException(result.failed() + " test failure"
                                + (result.failed() == 1 ? "" : "s"));
                    }
                })
                .build();

        Goal goal = Goal.builder("test")
                .addPhase(parseBuild)
                .addPhase(compileMain)
                .addPhase(compileTest)
                .addPhase(runTests)
                .build();

        ConsoleSpec spec = new ConsoleSpec("Testing",
                r -> testSummary(goal, r),
                r -> testFailureMessage(goal, r));
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(buildFile, dir));

        if (result.success()) return 0;
        // Test failures get exit 4 (PRD §6); compile / launcher errors are exit 1.
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    /**
     * Success result line (sans the leading ✔): {@code Passed N tests in 32s},
     * or {@code No tests in <t>} for a project with no {@code src/test/java}.
     */
    private String testSummary(Goal goal, GoalResult result) {
        String inTime = Theme.colorize(
                "in " + BuildCommand.fmtDuration(result.duration()), Theme.active().darkGray());
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult == null || testResult.total() == 0) return "No tests " + inTime;
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s") + " " + inTime;
    }

    private static String testFailureMessage(Goal goal, GoalResult result) {
        var tr = goal.get(TEST_RESULT).orElse(null);
        String base = (tr != null && !tr.allPassed()) ? "Tests failed" : "Build failed";
        return base + " " + BuildCommand.inTime(result);
    }

    /**
     * Up-front lexical estimate of the test count for the runTests phase's
     * scope. Counts {@code @Test}, {@code @ParameterizedTest},
     * {@code @TestFactory}, {@code @TestTemplate}, and {@code @RepeatedTest}
     * occurrences across every {@code .java} file under {@code src/test/java}.
     *
     * <p>The estimate is intentionally generous: each parameterized /
     * factory / template method counts as 1 regardless of the runtime
     * invocation count, and an {@code @Test} mentioned in a Javadoc is
     * over-counted. Both biases are safe because the numerator is gated
     * on the static plan (see {@code wasStatic} in {@code bridgeListener}),
     * so over-estimation just means the bar reaches ~98% and phase-end
     * auto-fill closes the rest. Under-estimation would be worse — the
     * regex catches every {@code @Test*}-prefixed annotation precisely
     * to avoid it.
     */
    private static final Pattern TEST_ANNOTATION_REGEX = Pattern.compile(
            "@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b");

    static int estimateTestCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            for (Path file : (Iterable<Path>) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))::iterator) {
                try {
                    String content = Files.readString(file);
                    count += (int) TEST_ANNOTATION_REGEX.matcher(content).results().count();
                } catch (IOException ignored) {
                    // best-effort: skip unreadable files, keep counting
                }
            }
        } catch (IOException ignored) {
            // best-effort: zero estimate falls back to a flat (empty) bar
            // through the test phase, which is honest if pessimistic.
        }
        return count;
    }

    /**
     * Translate {@link TestProgressListener} events into Goal-framework
     * calls so the shared {@code ProgressBarListener} renders the same
     * bar {@code jk compile} and {@code jk build} use.
     *
     * <ul>
     *   <li>{@code onDiscoveryTotal} grows the denominator via {@link
     *       PhaseContext#updateScope}.</li>
     *   <li>Each {@code onTestFinished} / {@code onTestSkipped} ticks
     *       the numerator with {@code ctx.progress(1)} and sets the
     *       bar's step-message via {@code ctx.label}.</li>
     *   <li>Failures route through {@code ctx.error}, which the bar
     *       listener hoists above the pinned row in the same
     *       {@code ✗ Error [phase/code]: ...} format compile uses for
     *       javac diagnostics.</li>
     *   <li>Test-process stdout/stderr is muted by default and only
     *       surfaced under {@code --verbose} — that mode swaps the bar
     *       for {@code VerboseListener}, so direct {@code println} is
     *       safe (no pinned row to corrupt).</li>
     * </ul>
     */
    static TestProgressListener bridgeListener(
            PhaseContext ctx,
            int workerCount,
            boolean verbose) {
        return new TestProgressListener() {
            // Progress strategy: the runTests phase was built with its
            // scope baked in from an upfront lexical scan, so the goal's
            // denominator is fixed before any phase runs. We don't react
            // to the runner's discovery_total at all (would reshape the
            // bar after the early phases had already moved). The numerator
            // ticks only for tests that were in the static plan (i.e.,
            // wasStatic=true). Dynamic invocations run and are counted in
            // the pass/fail tally but never advance the bar — for
            // parameterized-heavy suites that means the bar saturates
            // near 99% before execution ends; phase-end auto-fill snaps
            // it to 100% on success.

            @Override
            public void onTestFinished(String id, String display, String status,
                                       boolean isTest, boolean wasStatic,
                                       long durationMs, int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
                ctx.label(display);
            }

            @Override
            public void onTestSkipped(String id, String display, String reason,
                                      boolean isTest, boolean wasStatic, int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
            }

            @Override
            public void onFailure(String id, String display, String exClass,
                                  String message, int workerId) {
                ctx.error("test", display + ": " + exClass
                        + (message.isEmpty() ? "" : " — " + message));
            }

            @Override
            public void onUserOutput(int workerId, String line) {
                // Muted by default; --verbose surfaces it. Under verbose
                // mode GoalConsole picks VerboseListener (no pinned bar),
                // so println straight to stdout is safe.
                if (!verbose) return;
                String prefix = workerCount > 1 ? "[w" + workerId + "] " : "";
                System.out.println(prefix + line);
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

        // Project-qualify so the `tasks/<taskId>` pointer is unique per module
        // (display labels keep the plain base name).
        String cacheTaskId = ActionKey.qualifiedTaskId(taskId, outputDir);
        CompileRequest request = CompileRequest.builder()
                .sources(sources)
                .classpath(classpath)
                .outputDir(outputDir)
                .release(release)
                .extraOptions(javacArgs)
                .javaHome(javaHome)
                .build();
        ActionCache actionCache = new ActionCache(cas, cacheRoot.resolve("actions"));
        boolean useCache = !dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);

        ctx.label(taskId + ": " + sources.size() + " sources");
        IncrementalCompile.Result r = IncrementalCompile.run(
                cacheTaskId, request, Jk.VERSION, useCache, cas, actionCache);
        for (CompileResult.Diagnostic d : r.diagnostics()) {
            ctx.error("javac", d.render());
        }
        if (!r.success()) return false;
        ctx.label(r.cacheHit()
                ? taskId + ": cache hit " + r.actionKey().substring(0, 8)
                : taskId + ": compiled " + sources.size() + " sources");
        return true;
    }
}
