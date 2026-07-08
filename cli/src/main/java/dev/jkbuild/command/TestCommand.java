// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same {@linkplain BuildPipeline#coreBuilder core pipeline} as {@code jk build}, in
 * {@code testOnly} mode: parse → sync → jdk → compile (Kotlin and/or Java, main and test) →
 * resources → compile-test → run-tests, stopping short of packaging a jar. Sharing the pipeline
 * means Kotlin test sources compile and run exactly as they do under {@code jk build} — no
 * separate, Java-only test path to keep in sync.
 *
 * <p>The test-runner's NDJSON event stream bridges into the goal's progress bar (the same {@code
 * ProgressBarListener} {@code jk compile}/{@code jk build} use): each completion ticks the
 * numerator, each failure becomes a {@code ctx.error}, discovery grows the denominator.
 */
public final class TestCommand implements CliCommand {

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String description() {
        return "Compile and run tests";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide());
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    GlobalOptions global;

    // Populated by the pipeline's run-tests phase; read for the summary + exit code.
    // Value-equal to BuildPipeline.TEST_RESULT (same name + type), so it resolves
    // the same slot in the shared goal state.
    private static final GoalKey<JUnitLauncher.Result> TEST_RESULT =
            GoalKey.of("test-result", JUnitLauncher.Result.class);

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk test} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=JkRunner): under the
        // self-hosted build, in-process dispatches would otherwise recurse into the very
        // engine hosting the test run and deadlock — see BuildCommand's javadoc.
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        var proj = ProjectContext.require(dir, "test").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path lockFile = proj.lockFile();
        // No jk.lock guard: the pipeline's parse-build phase resolves the lock on
        // first run and re-locks when jk.toml changed — same as `jk build`/`run`.

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        int workerCount = workers != null && workers > 0 ? workers : 1;

        GoalResult result;
        JUnitLauncher.Result testResult;
        if (engineDisabledForTests()) {
            // Size the test-runner JVMs' heaps (and cap how many fork at once) to the
            // host's free memory before launching them.
            dev.jkbuild.worker.HeapPlan.Plan heapPlan =
                    dev.jkbuild.worker.JvmOptions.planAndApply(dev.jkbuild.worker.HeapPlan.requestedJvms(
                            1, workerCount, false, Runtime.getRuntime().availableProcessors()));
            if (heapPlan != null && heapPlan.warning() != null && !global.outputIsJson()) {
                CliOutput.err("jk test: " + heapPlan.warning());
            }
            // Up-front lexical estimate (Java + Kotlin test sources) so the bar's
            // denominator is set once; the static plan gates the numerator and
            // phase-end auto-fill closes any residual gap.
            int estimatedTestCount = estimateTestCount(dir.resolve("src/test/java"))
                    + estimateTestCount(dir.resolve("src/test/kotlin"));

            // testOnly → the core pipeline runs through run-tests but never packages.
            BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                    dir,
                    cache,
                    buildFile,
                    lockFile,
                    lockFile.getParent(),
                    workerCount,
                    estimatedTestCount,
                    profileName,
                    jdksDir,
                    /* skipTests */ false,
                    global.verbose, /* testOnly */
                    true);
            Goal goal = BuildPipeline.coreBuilder(inputs).build();

            // Deferred lookup: goal.get(TEST_RESULT) is empty until the run-tests phase populates it
            // mid-run, so these lambdas must read it at invocation time (when the console listener's
            // own goalFinish fires), not eagerly here.
            ConsoleSpec spec = new ConsoleSpec(
                    "Test",
                    r -> testSummary(goal.get(TEST_RESULT).orElse(null), r),
                    r -> testFailureMessage(goal.get(TEST_RESULT).orElse(null), r));
            result = GoalConsole.runGoal(
                    goal, GoalConsole.modeFor(global), cache, spec, BuildCommand.buildTarget(buildFile, dir));
            testResult = goal.get(TEST_RESULT).orElse(null);
        } else {
            // Engine-hosted (Phase 3): the wire has no real Goal to attach a console listener to
            // ahead of time, so the listener is chosen once the phase list arrives over the socket —
            // see EngineBuildListenerAdapter.runTest. testResultHolder is populated (if the run-tests
            // phase actually ran) before the terminal goal-finish reaches that listener, exactly
            // mirroring how goal.get(TEST_RESULT) is already populated by the in-process path above.
            JUnitLauncher.Result[] testResultHolder = new JUnitLauncher.Result[1];
            ConsoleSpec spec = new ConsoleSpec(
                    "Test", r -> testSummary(testResultHolder[0], r), r -> testFailureMessage(testResultHolder[0], r));
            String module = BuildCommand.buildTarget(buildFile, dir);
            GoalConsole.Mode mode = GoalConsole.modeFor(global);
            try {
                result = dev.jkbuild.cli.engine.EngineClient.runTest(
                        dev.jkbuild.engine.EnginePaths.current(),
                        new dev.jkbuild.cli.engine.EngineClient.TestRequest(
                                dir, cache, jdksDir, workerCount, profileName, global.verbose),
                        phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, module),
                        testResultHolder);
            } catch (IOException e) {
                CliOutput.err("jk test: " + e.getMessage());
                return Exit.SOFTWARE;
            }
            testResult = testResultHolder[0];
        }

        if (result.success()) return 0;
        // Test failures get exit 4 (PRD §6); compile / launcher errors are exit 1.
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }

    /**
     * Success result line (sans the leading ✓): {@code Passed N tests in 32s}, or {@code No tests in
     * <t>} for a project with no test sources. Takes the resolved {@link JUnitLauncher.Result}
     * directly (rather than a {@code Goal} to look it up from) so both the in-process path (which
     * reads it off {@code goal.get(TEST_RESULT)}) and the engine-hosted path (which has no real
     * {@code Goal}, only a wire-populated holder) share this one rendering method.
     */
    private static String testSummary(JUnitLauncher.Result testResult, GoalResult result) {
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s");
    }

    private static String testFailureMessage(JUnitLauncher.Result testResult, GoalResult result) {
        return (testResult != null && !testResult.allPassed()) ? "Tests failed" : "Build failed";
    }

    /**
     * Up-front lexical estimate of the test count for the run-tests phase's scope. Counts
     * {@code @Test}, {@code @ParameterizedTest}, {@code @TestFactory}, {@code @TestTemplate}, and
     * {@code @RepeatedTest} occurrences across every {@code .java}/{@code .kt} file under {@code
     * root}.
     *
     * <p>The estimate is intentionally generous (a parameterized method counts once; an annotation in
     * a comment over-counts). Both biases are safe — the numerator is gated on the static plan, so
     * over-estimation just means the bar reaches ~98% and phase-end auto-fill closes the rest.
     */
    /** Bridge test-runner NDJSON events onto the goal's progress bar (delegates to TestSupport). */
    static TestProgressListener bridgeListener(PhaseContext ctx, int workerCount, boolean verbose) {
        return dev.jkbuild.runtime.TestSupport.bridgeListener(ctx, workerCount, verbose);
    }

    /** Delegates to the engine ({@link dev.jkbuild.runtime.TestSupport#estimateTestCount}). */
    static int estimateTestCount(Path testSrcDir) {
        return dev.jkbuild.runtime.TestSupport.estimateTestCount(testSrcDir);
    }
}
