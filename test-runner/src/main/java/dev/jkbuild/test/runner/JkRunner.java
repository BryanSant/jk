// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;

/**
 * Child-JVM entry point for {@code jk test}. Three modes, selected by flags:
 *
 * <ol>
 *   <li><b>One-shot</b> (default): {@code --scan-classpath=<dir>} — discover
 *       and execute everything under the classpath root. Used when the parent
 *       wants a single fork with no work-stealing.</li>
 *   <li><b>Discovery</b>: {@code --list-only --scan-classpath=<dir>} — walk
 *       the test plan but execute nothing. Emit one {@link EventType#DISCOVERED}
 *       per top-level test class then exit. The parent uses this list to seed
 *       the pull-queue for parallel runs.</li>
 *   <li><b>Pull worker</b>: {@code --pull --worker=N --scan-classpath=<dir>}
 *       — open a long-lived {@link LauncherSession}, then loop on stdin:
 *       {@code RUN <fqcn>} runs that class via {@link Launcher#execute},
 *       {@code DONE} or EOF exits. After each class we emit
 *       {@link EventType#READY} so the parent knows to send the next.</li>
 * </ol>
 *
 * <p>Exit codes: 0 = success, 1 = at least one test failed, 2 = arg/launcher
 * error. The wire protocol on stdout matches the schema described in
 * {@link EventType}; lines without the {@link JsonEventWriter#PREFIX} marker
 * are user test output and pass through to the parent's stdout.
 */
public final class JkRunner {

    public static void main(String[] args) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("jk-test-runner: " + e.getMessage());
            System.err.println("usage: jk-test-runner --scan-classpath=<dir> "
                    + "[--list-only] [--pull --worker=<id>] [--filter=<regex>]");
            System.exit(2);
            return;
        }

        var stdout = new PrintStream(System.out, /* autoFlush */ false, StandardCharsets.UTF_8);
        try (var writer = new JsonEventWriter(stdout)) {
            if (parsed.listOnly) {
                runListOnly(parsed, writer);
            } else if (parsed.pull) {
                runPullMode(parsed, writer);
            } else {
                int exit = runOneShot(parsed, writer);
                System.exit(exit);
            }
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("jk-test-runner: " + t.getClass().getName()
                    + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    // --- mode 1: one-shot ----------------------------------------------------

    /**
     * Discover + execute everything reachable from {@code --scan-classpath},
     * emitting events as we go. Returns the exit code (0 on green).
     */
    private static int runOneShot(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var summary = new SummaryGeneratingListener();
        var request = baseRequest(args).build();
        try (var session = LauncherFactory.openSession()) {
            Launcher launcher = session.getLauncher();
            // Discover first so the parent has the test count before any
            // test runs — that's what populates the progress bar's "of N".
            emitDiscovery(launcher, request, streaming);
            launcher.registerTestExecutionListeners(streaming, summary);
            launcher.execute(request);
        }
        // Counts come from the parent's per-event aggregation of FINISHED /
        // SKIPPED events; we only need to return a pass/fail exit code here.
        return summary.getSummary().getTotalFailureCount() == 0 ? 0 : 1;
    }

    // --- mode 2: discovery ---------------------------------------------------

    /**
     * Walk the test plan and emit one {@link EventType#DISCOVERED} per
     * top-level test class, plus a {@link EventType#DISCOVERY_TOTAL} with
     * the {@code (classes, tests)} totals. Uses {@link Launcher#discover}
     * (not {@code execute}) so this completes in 100–300 ms even for big
     * suites.
     */
    private static void runListOnly(Args args, JsonEventWriter writer) {
        var streaming = new StreamingListener(writer, args.workerId);
        var request = baseRequest(args).build();
        try (var session = LauncherFactory.openSession()) {
            emitDiscovery(session.getLauncher(), request, streaming);
        }
    }

    /**
     * Shared discovery emission used by both one-shot and list-only modes:
     * walk the test plan once, emit a DISCOVERED per class, then a
     * DISCOVERY_TOTAL with cumulative counts. Single source of truth so the
     * wire shape is identical regardless of which mode invoked it.
     */
    private static void emitDiscovery(
            Launcher launcher, org.junit.platform.launcher.LauncherDiscoveryRequest request,
            StreamingListener listener) {
        TestPlan plan = launcher.discover(request);
        var counts = new int[]{0, 0}; // [classes, tests]
        for (var root : plan.getRoots()) {
            walkAndEmit(plan, root, listener, counts);
        }
        listener.emitDiscoveryTotal(counts[0], counts[1]);
    }

    /**
     * Depth-first walk over the TestPlan. Emits a DISCOVERED event for every
     * class-shaped CONTAINER and bumps the test counter for every TEST leaf.
     * Counts mirror what {@code SummaryGeneratingListener.getTestsFoundCount}
     * would report after a full execution, but without running anything.
     */
    private static void walkAndEmit(TestPlan plan, TestIdentifier node, StreamingListener listener, int[] counts) {
        boolean isContainer = node.getType() == org.junit.platform.engine.TestDescriptor.Type.CONTAINER;
        boolean isTest = node.getType() == org.junit.platform.engine.TestDescriptor.Type.TEST;
        node.getSource().ifPresent(src -> {
            if (src instanceof org.junit.platform.engine.support.descriptor.ClassSource cs && isContainer) {
                listener.emitDiscovered(cs.getClassName());
                counts[0]++;
            }
        });
        if (isTest) counts[1]++;
        for (var child : plan.getChildren(node)) {
            walkAndEmit(plan, child, listener, counts);
        }
    }

    // --- mode 3: pull worker -------------------------------------------------

    /**
     * Long-lived worker. Reuses one {@link LauncherSession} for the whole
     * lifetime so JIT/heap stay warm across the classes the parent feeds us.
     * Protocol on stdin (one line per command):
     * <pre>
     *   RUN com.example.FooTest
     *   DONE
     * </pre>
     */
    private static void runPullMode(Args args, JsonEventWriter writer) throws Exception {
        var streaming = new StreamingListener(writer, args.workerId);
        var summary = new SummaryGeneratingListener();

        try (var session = LauncherFactory.openSession();
             var stdin = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Launcher launcher = session.getLauncher();
            launcher.registerTestExecutionListeners(streaming, summary);

            // Initial ready — tells the parent the worker is up and waiting.
            streaming.emitReady();

            String line;
            while ((line = stdin.readLine()) != null) {
                if (line.equals("DONE")) break;
                if (!line.startsWith("RUN ")) {
                    System.err.println("jk-test-runner: ignoring unknown command: " + line);
                    continue;
                }
                String className = line.substring(4).trim();
                var classRequest = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(className))
                        .build();
                launcher.execute(classRequest);
                streaming.emitReady();
            }
        }
        // Exit code is informational — the parent's aggregator is the source
        // of truth for the overall pass/fail decision. SummaryGeneratingListener
        // resets per-execute (per-class), so this reflects only the last
        // class, which is fine for a per-worker liveness signal.
        System.exit(summary.getSummary().getTotalFailureCount() == 0 ? 0 : 1);
    }

    // --- shared --------------------------------------------------------------

    private static LauncherDiscoveryRequestBuilder baseRequest(Args args) {
        var b = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(args.scanClasspath)));
        if (args.filter != null && !args.filter.isEmpty()) {
            b.filters(ClassNameFilter.includeClassNamePatterns(args.filter));
        }
        return b;
    }


    /** Parsed CLI args. */
    private record Args(
            Path scanClasspath,
            String filter,
            boolean listOnly,
            boolean pull,
            int workerId) {

        static Args parse(String[] argv) {
            Path scan = null;
            String filter = null;
            boolean listOnly = false;
            boolean pull = false;
            int workerId = 0;
            for (var a : argv) {
                if (a.startsWith("--scan-classpath=")) {
                    scan = Path.of(a.substring("--scan-classpath=".length()));
                } else if (a.startsWith("--filter=")) {
                    filter = a.substring("--filter=".length());
                } else if (a.equals("--list-only")) {
                    listOnly = true;
                } else if (a.equals("--pull")) {
                    pull = true;
                } else if (a.startsWith("--worker=")) {
                    workerId = Integer.parseInt(a.substring("--worker=".length()));
                } else if (a.equals("--fail-fast")) {
                    // accepted but currently a no-op — wired in a follow-up
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (scan == null) {
                throw new IllegalArgumentException("--scan-classpath=<dir> is required");
            }
            if (listOnly && pull) {
                throw new IllegalArgumentException("--list-only and --pull are mutually exclusive");
            }
            return new Args(scan, filter, listOnly, pull, workerId);
        }
    }
}
