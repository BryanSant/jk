// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.runtime.BuildPipeline;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code jk test} — compile main + test sources and run JUnit Platform tests.
 *
 * <p>Runs the same {@linkplain BuildPipeline#coreBuilder core pipeline} as
 * {@code jk build}, in {@code testOnly} mode: parse → sync → jdk → compile
 * (Kotlin and/or Java, main and test) → resources → compile-test → run-tests,
 * stopping short of packaging a jar. Sharing the pipeline means Kotlin test
 * sources compile and run exactly as they do under {@code jk build} — no
 * separate, Java-only test path to keep in sync.
 *
 * <p>The test-runner's NDJSON event stream bridges into the goal's progress
 * bar (the same {@code ProgressBarListener} {@code jk compile}/{@code jk build}
 * use): each completion ticks the numerator, each failure becomes a
 * {@code ctx.error}, discovery grows the denominator.
 */
public final class TestCommand implements CliCommand {

    @Override public String name() { return "test"; }
    @Override public String description() { return "Compile and run tests"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<name>", "Build profile to apply.", "--profile"),
                Opt.value("<N>", "Number of test-runner JVMs to fork in parallel. Default 1.", "-w", "--workers"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Run the test build in-process instead of via the Workspace Host JVM.", "--no-host").hide());
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

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.profileName = in.value("profile").orElse(null);
        this.workers = in.value("workers").map(Integer::parseInt).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);
        boolean useHost = !in.isSet("no-host");
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
        int workerCount = workers != null && workers > 0 ? workers : 1;
        // Up-front lexical estimate (Java + Kotlin test sources) so the bar's
        // denominator is set once; the static plan gates the numerator and
        // phase-end auto-fill closes any residual gap.
        int estimatedTestCount = estimateTestCount(dir.resolve("src/test/java"))
                + estimateTestCount(dir.resolve("src/test/kotlin"));

        // Phase 4: fork the Workspace Host by default; --no-host runs in-process.
        if (useHost) {
            dev.jkbuild.host.HostInvocation inv = new dev.jkbuild.host.HostInvocation(
                    "test", dir, cache, lockFile, jdksDir, profileName, workerCount,
                    false, global.verbose, global.outputIsJson());
            var consoleSpec = new ConsoleSpec("Testing", r -> "Tests passed", r -> "Tests failed");
            int code = dev.jkbuild.cli.run.HostLauncher.tryRun(
                    inv, GoalConsole.modeFor(global), consoleSpec, global.verbose);
            if (code >= 0) return code;
        }

        // testOnly → the core pipeline runs through run-tests but never packages.
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                dir, cache, buildFile, lockFile, lockFile.getParent(),
                workerCount, estimatedTestCount, profileName, jdksDir,
                /* skipTests */ false, global.verbose, /* testOnly */ true);
        Goal goal = BuildPipeline.coreBuilder(inputs).build();

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
     * Success result line (sans the leading ✓): {@code Passed N tests in 32s},
     * or {@code No tests in <t>} for a project with no test sources.
     */
    private String testSummary(Goal goal, GoalResult result) {
        var testResult = goal.get(TEST_RESULT).orElse(null);
        if (testResult == null || testResult.total() == 0) return "No tests";
        long total = testResult.total();
        String passed = Theme.colorize("Passed", Theme.active().focused());
        return passed + " " + total + " test" + (total == 1 ? "" : "s");
    }

    private static String testFailureMessage(Goal goal, GoalResult result) {
        var tr = goal.get(TEST_RESULT).orElse(null);
        return (tr != null && !tr.allPassed()) ? "Tests failed" : "Build failed";
    }

    /**
     * Up-front lexical estimate of the test count for the run-tests phase's
     * scope. Counts {@code @Test}, {@code @ParameterizedTest},
     * {@code @TestFactory}, {@code @TestTemplate}, and {@code @RepeatedTest}
     * occurrences across every {@code .java}/{@code .kt} file under {@code root}.
     *
     * <p>The estimate is intentionally generous (a parameterized method counts
     * once; an annotation in a comment over-counts). Both biases are safe — the
     * numerator is gated on the static plan, so over-estimation just means the
     * bar reaches ~98% and phase-end auto-fill closes the rest.
     */
    private static final Pattern TEST_ANNOTATION_REGEX = Pattern.compile(
            "@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b");

    /** Bridge test-runner NDJSON events onto the goal's progress bar (delegates to TestSupport). */
    static TestProgressListener bridgeListener(PhaseContext ctx, int workerCount, boolean verbose) {
        return dev.jkbuild.runtime.TestSupport.bridgeListener(ctx, workerCount, verbose);
    }

    static int estimateTestCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            for (Path file : (Iterable<Path>) walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".java") || n.endsWith(".kt");
                    })::iterator) {
                try {
                    String content = Files.readString(file);
                    count += (int) TEST_ANNOTATION_REGEX.matcher(content).results().count();
                } catch (IOException ignored) {
                    // best-effort: skip unreadable files, keep counting
                }
            }
        } catch (IOException ignored) {
            // best-effort: zero estimate falls back to a flat (empty) bar
        }
        return count;
    }
}
