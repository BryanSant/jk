// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.config.ActiveConfig;
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

/**
 * Test-phase building blocks shared by the build pipeline and the {@code test}
 * verb. Coupled only to {@link PhaseContext} (the view-agnostic progress
 * callback), so it lives in {@code :runtime} and embedders can drive it without
 * the CLI/TUI.
 */
public final class TestSupport {

    private TestSupport() {}

    /**
     * Render a failed test run as console lines — each failing test's name
     * followed by its full stack trace, indented. Mirrors what Maven/Gradle
     * print on failure so {@code jk} doesn't just report a count. Returns an
     * empty list when nothing failed.
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
            String detail = (f.details() != null && !f.details().isBlank())
                    ? f.details() : f.message();
            for (String line : detail.split("\n", -1)) {
                if (!line.isEmpty()) out.add("    " + line);
            }
        }
        out.add("");
        return out;
    }

    /**
     * Adapt the JUnit runner's events onto a {@link PhaseContext}.
     *
     * <p>The runTests phase is built with its scope baked in from an upfront
     * lexical scan, so the goal's denominator is fixed before any phase runs.
     * We don't react to the runner's {@code discovery_total} (that would reshape
     * the bar after early phases moved). The numerator ticks only for tests that
     * were in the static plan ({@code wasStatic=true}); dynamic invocations run
     * and are counted in the pass/fail tally but never advance the bar — for
     * parameterized-heavy suites the bar saturates near 99% before execution
     * ends and phase-end auto-fill snaps it to 100% on success.
     */
    public static TestProgressListener bridgeListener(
            PhaseContext ctx,
            int workerCount,
            boolean verbose) {
        return new TestProgressListener() {
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
                // Muted by default; --verbose surfaces it. We hand the line to the
                // view via the phase context — only :cli owns the actual streams.
                if (!verbose) return;
                String prefix = workerCount > 1 ? "[w" + workerId + "] " : "";
                ctx.output(prefix + line);
            }
        };
    }

    /**
     * Compile sources with action-cache lookup. Shared by the compile-main and
     * compile-test phases — each calls it with its own task ID, source dir,
     * classpath, and output dir.
     */
    public static boolean compileWithCache(
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
                .build();
        ActionCache actionCache = new ActionCache(cas, cacheRoot.resolve("actions"));
        boolean useCache = !ActiveConfig.get().noCacheOr(false);
        java.nio.file.Path stateDir = cacheRoot.resolve("actions")
                .resolve("incremental-java").resolve(cacheTaskId);

        ctx.label(taskId + ": " + sources.size() + " sources");
        dev.jkbuild.task.JavaIncrementalCompile.Result r = dev.jkbuild.task.JavaIncrementalCompile.run(
                cacheTaskId, request, JkVersion.VERSION, useCache, cas, actionCache, stateDir);
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
