// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.ProjectContext;
import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.run.ConsoleSpec;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.StepContext;
import build.jumpkick.run.TestSummary;
import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same core pipeline ({@code BuildPipelines.coreBuilder}) as {@code jk build}, in
 * {@code testOnly} mode: parse → sync → jdk → compile (Kotlin and/or Java, main and test) →
 * resources → compile-test → run-tests, stopping short of packaging a jar. Sharing the pipeline
 * means Kotlin test sources compile and run exactly as they do under {@code jk build} — no
 * separate, Java-only test path to keep in sync.
 *
 * <p>The test-runner's NDJSON event stream bridges into the pipeline's progress bar (the same {@code
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
        var opts = new java.util.ArrayList<Opt>(List.of(
                Opt.value("<name>", "Apply a build profile. Default: auto (ci on CI).", "--profile"),
                Opt.value("<N>", "Test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                        .hide()));
        opts.addAll(VariantSelection.options());
        return opts;
    }

    String profileName;
    Integer workers;
    Path cacheDir;
    Path jdksDir;
    GlobalOptions global;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk test} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        // Also bypass inside a jk-forked test worker (jk.plugin.class=TestRunnerPlugin): under the
        // self-hosted build, in-process dispatches would otherwise recurse into the very
        // engine hosting the test run and deadlock — see BuildCommand's javadoc.
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunnerPlugin".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);
        Path dir = global.workingDir();
        VariantSelection.install(in, dir);
        var proj = ProjectContext.require(dir, "test").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path lockFile = proj.lockFile();
        // No jk.lock guard: the pipeline's parse-build step resolves the lock on
        // first run and re-locks when jk.toml changed — same as `jk build`/`run`.

        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        int workerCount = workers != null && workers > 0 ? workers : 1;

        PipelineResult result;
        TestSummary testResult;
        if (engineDisabledForTests()) {
            // The in-process pipeline assembly lives behind the ServiceLoader seam (:cli-engine) since
            // Stage 5 cut the engine off this module's classpath — same code, different module.
            var outcome = build.jumpkick.cli.engine.InProcessEngine.require()
                    .testPipeline(dir, cache, buildFile, lockFile, workerCount, profileName, jdksDir, global);
            result = outcome.result();
            testResult = outcome.testResult();
        } else {
            // Engine-hosted (Step 3): the wire has no real Pipeline to attach a console listener to
            // ahead of time, so the listener is chosen once the step list arrives over the socket —
            // see EngineBuildListenerAdapter.runTest. testResultHolder is populated (if the run-tests
            // step actually ran) before the terminal pipeline-finish reaches that listener, exactly
            // mirroring how pipeline.get(TEST_RESULT) is already populated by the in-process path above.
            TestSummary[] testResultHolder = new TestSummary[1];
            ConsoleSpec spec = new ConsoleSpec(
                    "Test", r -> testSummary(testResultHolder[0], r), r -> testFailureMessage(testResultHolder[0], r));
            String module = BuildCommand.buildTarget(buildFile, dir);
            PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
            try {
                result = build.jumpkick.cli.engine.EngineClient.runTest(
                        build.jumpkick.engine.EnginePaths.current(),
                        new build.jumpkick.cli.engine.EngineClient.TestRequest(
                                dir,
                                cache,
                                jdksDir,
                                workerCount,
                                profileName,
                                global.verbose,
                                // Global flags are consumed into the session before dispatch —
                                // the session (not the Invocation) is their authority, exactly as
                                // BuildCommand's request wiring reads them.
                                build.jumpkick.config.SessionContext.current().offline(),
                                build.jumpkick.config.SessionContext.current().force()),
                        steps -> PipelineConsole.chooseConsoleListener(steps, mode, spec, module),
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
     * <t>} for a project with no test sources. Takes the resolved {@link TestSummary}
     * directly (rather than a {@code Pipeline} to look it up from) so both the in-process path (which
     * reads it off {@code pipeline.get(TEST_RESULT)}) and the engine-hosted path (which has no real
     * {@code Pipeline}, only a wire-populated holder) share this one rendering method.
     */
    static String testSummary(TestSummary testResult, PipelineResult result) {
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s");
    }

    static String testFailureMessage(TestSummary testResult, PipelineResult result) {
        return (testResult != null && !testResult.allPassed()) ? "Tests failed" : "Build failed";
    }

}
