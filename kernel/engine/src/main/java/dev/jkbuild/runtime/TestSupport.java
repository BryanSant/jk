// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.task.ActionCache;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.test.JUnitLauncher;
import dev.jkbuild.test.TestProgressListener;
import dev.jkbuild.util.JkVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test-phase building blocks shared by the build pipeline and the {@code test} verb. Coupled only
 * to {@link PhaseContext} (the view-agnostic progress callback), so it lives in {@code :runtime}
 * and embedders can drive it without the CLI/TUI.
 */
public final class TestSupport {

    private TestSupport() {}

    private static final Pattern TEST_ANNOTATION_REGEX =
            Pattern.compile("@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b");

    /**
     * Best-effort count of JUnit test methods under {@code testSrcDir} — scans {@code .java}/{@code
     * .kt} sources for {@code @Test}-family annotations. Feeds the build's {@code estimatedTestCount}
     * (progress-bar weighting); a zero estimate falls back to a flat bar. Never throws.
     */
    public static int estimateTestCount(Path testSrcDir) {
        if (!Files.isDirectory(testSrcDir)) return 0;
        int count = 0;
        try (Stream<Path> walk = Files.walk(testSrcDir)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).filter(p -> {
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

    /**
     * Render a failed test run as console lines — each failing test's name followed by its full stack
     * trace, indented. Mirrors what Maven/Gradle print on failure so {@code jk} doesn't just report a
     * count. Returns an empty list when nothing failed.
     */
    public static List<String> renderFailures(JUnitLauncher.Result result) {
        List<String> out = new ArrayList<>();
        List<JUnitLauncher.Failure> failures = result.failures();
        if (failures.isEmpty()) return out;
        out.add("");
        out.add(failures.size() + " test" + (failures.size() == 1 ? "" : "s") + " failed:");
        for (JUnitLauncher.Failure f : failures) {
            out.add("");
            out.add("  FAILED  " + f.testName());
            if (f.details() != null && !f.details().isBlank()) {
                for (String line : f.details().split("\n", -1)) {
                    if (!line.isEmpty()) out.add("    " + line);
                }
            } else {
                // No stack trace: surface the exception class and message on
                // their own lines rather than gluing them into one summary.
                if (!f.exceptionClass().isEmpty()) out.add("    " + f.exceptionClass());
                if (!f.message().isEmpty()) out.add("    " + f.message());
            }
        }
        out.add("");
        return out;
    }

    /**
     * Adapt the JUnit runner's events onto a {@link PhaseContext}.
     *
     * <p>The runTests phase is built with its scope baked in from an upfront lexical scan, so the
     * goal's denominator is fixed before any phase runs. We don't react to the runner's {@code
     * discovery_total} (that would reshape the bar after early phases moved). The numerator ticks
     * only for tests that were in the static plan ({@code wasStatic=true}); dynamic invocations run
     * and are counted in the pass/fail tally but never advance the bar — for parameterized-heavy
     * suites the bar saturates near 99% before execution ends and phase-end auto-fill snaps it to
     * 100% on success.
     */
    public static TestProgressListener bridgeListener(PhaseContext ctx, int workerCount, boolean verbose) {
        return new TestProgressListener() {
            @Override
            public void onTestFinished(
                    String id,
                    String display,
                    String status,
                    boolean isTest,
                    boolean wasStatic,
                    long durationMs,
                    int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
                ctx.label(display);
            }

            @Override
            public void onTestSkipped(
                    String id, String display, String reason, boolean isTest, boolean wasStatic, int workerId) {
                if (!isTest) return;
                if (wasStatic) ctx.progress(1);
            }

            @Override
            public void onFailure(String id, String display, String exClass, String message, int workerId) {
                // Code "test-failure" (not "test") marks a per-test failure that is
                // already shown in full by the run-tests renderFailures block. The
                // diagnostic still flows to JSON consumers, but the human listeners
                // suppress it so the same failure isn't printed twice. Test *infra*
                // errors (interrupt/IO) keep code "test" and still surface in text mode.
                //
                // Pass the test name and exception class as discrete fields rather
                // than gluing them into the message — JSON consumers get them apart.
                ctx.error("test-failure", message, display, exClass);
            }

            @Override
            public void onUserOutput(int workerId, String line) {
                // Muted by default; --verbose surfaces it. We hand the line to the
                // view via the phase context — only :cli owns the actual streams.
                if (!verbose) return;
                String prefix = workerCount > 1 ? "[w" + workerId + "] " : "";
                ctx.output(prefix + line);
            }
        };
    }

    /**
     * Compile test sources with action-cache lookup. Mirrors the compile-main phase: same task ID /
     * classpath / output-dir shape, and — crucially — the same {@code processorPath} + {@link
     * dev.jkbuild.task.JavaIncrementalCompile.ApSetup} wiring, so annotation processors (Lombok,
     * Immutables, …) run over test sources too. Modern javac only runs processors named by {@code
     * -processorpath}; without it, a test class using {@code @Getter} would fail to find its
     * generated modules even though main compilation handled the same annotation.
     */
    public static boolean compileWithCache(
            PhaseContext ctx,
            String taskId,
            Path srcDir,
            Path outputDir,
            List<Path> classpath,
            List<Path> processorPath,
            int release,
            List<String> javacArgs,
            Path javaHome,
            dev.jkbuild.task.JavaIncrementalCompile.ApSetup ap,
            Cas cas,
            Path cacheRoot)
            throws IOException {

        List<Path> sources = CompileSupport.collectJavaSources(srcDir);
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
                .processorPath(processorPath)
                .build();
        ActionCache actionCache = new ActionCache(cas, cacheRoot.resolve("actions"));
        boolean useCache = !dev.jkbuild.config.SessionContext.current().config().rerunOr(false);
        java.nio.file.Path stateDir =
                cacheRoot.resolve("actions").resolve("incremental-java").resolve(cacheTaskId);

        // Reweight the bar slice from the real request: a CAS hit is a cheap
        // restore (3), else a full compile. Same key JavaIncrementalCompile uses.
        if (useCache) {
            try {
                boolean restores = actionCache
                        .lookup(ActionKey.forJavac(cacheTaskId, request, JkVersion.VERSION))
                        .isPresent();
                ctx.reweight(restores ? EffortWeights.RESTORE : EffortWeights.compileWeight(sources.size()));
            } catch (Exception ignored) {
                /* keep the up-front estimate */
            }
        }
        ctx.label(taskId + ": " + sources.size() + " sources");
        dev.jkbuild.task.JavaIncrementalCompile.Result r = dev.jkbuild.task.JavaIncrementalCompile.run(
                cacheTaskId, request, JkVersion.VERSION, useCache, cas, actionCache, stateDir, ap);
        // Surface javac diagnostics by severity — errors fail, warnings (e.g.
        // deprecation/unchecked) are shown but don't. Mirrors the main-compile
        // phase so test sources report warnings the same way.
        boolean errored = false;
        for (CompileResult.Diagnostic d : r.diagnostics()) {
            if (d.severity() == CompileResult.Severity.ERROR) {
                ctx.error("javac", d.describe());
                errored = true;
            } else {
                ctx.warn("javac", d.describe());
            }
        }
        if (!r.success()) {
            // A failure must never be silent: if the compiler produced no ERROR
            // diagnostic (crash, swallowed output), say so explicitly.
            if (!errored) {
                ctx.error("javac", "test compile failed without compiler diagnostics (outcome: " + r.outcome() + ")");
            }
            return false;
        }
        ctx.label(
                r.cacheHit()
                        ? taskId + ": cache hit " + r.actionKey().substring(0, 8)
                        : taskId + ": compiled " + sources.size() + " sources");
        return true;
    }
}
