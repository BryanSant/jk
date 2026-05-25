// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Child-JVM entry point that drives JUnit Platform and streams events back
 * to the parent jk process over NDJSON on stdout. The parent forks us as:
 *
 * <pre>
 *   &lt;javaHome&gt;/bin/java -cp &lt;test-classpath&gt; \
 *       dev.jkbuild.test.runner.JkRunner \
 *       --scan-classpath=&lt;test-classes-dir&gt; \
 *       [--filter=&lt;regex&gt;] [--fail-fast]
 * </pre>
 *
 * <p>Exit codes:
 * <ul>
 *   <li>{@code 0} — all tests passed (or none were found).</li>
 *   <li>{@code 1} — at least one test failed.</li>
 *   <li>{@code 2} — bad CLI args / launcher initialisation error.</li>
 * </ul>
 *
 * <p>The user's tests print to stdout/stderr in the normal way. Protocol
 * events are tagged with {@link JsonEventWriter#PREFIX} so the parent can
 * separate them from arbitrary test output sharing the same pipe.
 */
public final class JkRunner {

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("jk-test-runner: " + e.getMessage());
            System.err.println("usage: jk-test-runner --scan-classpath=<dir> [--filter=<regex>] [--fail-fast]");
            System.exit(2);
            return;
        }

        // Buffered stdout: the listener flushes per event, but bursty writes
        // (e.g. dynamic-test registration of 10k cases) stay efficient.
        var stdout = new PrintStream(System.out, /* autoFlush */ false, java.nio.charset.StandardCharsets.UTF_8);
        try (var writer = new JsonEventWriter(stdout)) {
            var summary = new SummaryGeneratingListener();
            var streaming = new StreamingListener(writer);

            var builder = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(parsed.scanClasspath)));
            if (parsed.filter != null && !parsed.filter.isEmpty()) {
                builder.filters(ClassNameFilter.includeClassNamePatterns(parsed.filter));
            }
            LauncherDiscoveryRequest request = builder.build();

            try (var session = LauncherFactory.openSession()) {
                Launcher launcher = session.getLauncher();
                launcher.registerTestExecutionListeners(streaming, summary);
                launcher.execute(request);
            }

            var s = summary.getSummary();
            // PLAN_FINISHED stream summary — parent computes its own counts
            // from STARTED/FINISHED, but emitting these as a single event
            // saves it the bookkeeping for the common "I just want totals" case.
            var totals = new LinkedHashMap<String, Object>();
            totals.put("total", s.getTestsFoundCount());
            totals.put("successful", s.getTestsSucceededCount());
            totals.put("failed", s.getTestsFailedCount());
            totals.put("skipped", s.getTestsSkippedCount());
            totals.put("aborted", s.getTestsAbortedCount());
            writer.write(EventType.PLAN_FINISHED, totals);
            writer.flush();

            int exit = s.getTotalFailureCount() == 0 ? 0 : 1;
            System.exit(exit);
        } catch (Throwable t) {
            // Catch-all: anything that escapes here is a runner bug, not a
            // test failure. Print to stderr so the parent can surface it.
            System.err.println("jk-test-runner: " + t.getClass().getName()
                    + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    /** CLI args. */
    private record Args(Path scanClasspath, String filter, boolean failFast) {
        static Args parse(String[] argv) {
            Path scan = null;
            String filter = null;
            boolean failFast = false;
            for (var a : argv) {
                if (a.startsWith("--scan-classpath=")) {
                    scan = Path.of(a.substring("--scan-classpath=".length()));
                } else if (a.startsWith("--filter=")) {
                    filter = a.substring("--filter=".length());
                } else if (a.equals("--fail-fast")) {
                    failFast = true;
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (scan == null) {
                throw new IllegalArgumentException("--scan-classpath=<dir> is required");
            }
            return new Args(scan, filter, failFast);
        }
    }

    // Suppress unused warning while --fail-fast is parsed but not yet
    // wired into the discovery filter; lands in the follow-up that adds
    // a per-test cancellation hook.
    @SuppressWarnings("unused")
    private static final String UNUSED_NOTE = Locale.ROOT.toString();
}
