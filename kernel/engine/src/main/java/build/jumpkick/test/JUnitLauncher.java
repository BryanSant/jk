// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.test;

import build.jumpkick.cache.Cas;
import build.jumpkick.jdk.HostPlatform;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.run.TestSummary;
import build.jumpkick.engine.plugin.PluginJar;
import build.jumpkick.engine.plugin.PluginProcess;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Runs a project's compiled tests by forking child JVM(s) that invoke {@code
 * build.jumpkick.testrunner.TestRunnerPlugin}. Supports two modes:
 *
 * <ul>
 *   <li><b>Single-worker</b> ({@code workers=1}, default): one child JVM discovers and runs
 *       everything, streaming events back via NDJSON on stdout. The baseline path — preserves
 *       today's behavior exactly.
 *   <li><b>Parallel pull-queue</b> ({@code workers>1}): spawn a discovery child first to enumerate
 *       test classes, then spawn N pull-mode workers concurrently. Each worker stays alive across
 *       multiple classes; the parent dispatches one class at a time via RUN/DONE commands on the
 *       worker's stdin, in response to {@code ready} events the worker emits after each class
 *       completes. Process isolation between forks; serial within a fork.
 * </ul>
 *
 * <p>Wire protocol: child emits one NDJSON event per line on stdout, each line prefixed with {@code
 * ##JK:}. Lines without the prefix are the user's test stdout/stderr — forwarded to the parent's
 * stdout with a {@code [w<N>] } worker prefix in parallel mode.
 */
public final class JUnitLauncher {

    /** Marker prefix every protocol line carries. Must match {@code JsonEventWriter.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JKT:";

    /**
     * The plugin the worker host must run. The test-runner jar is launched with the module-under-test
     * on its classpath (to discover its tests); when that module is itself a plugin, the host would
     * otherwise see two {@code Plugin} services. Naming the runner explicitly via {@code
     * -Djk.plugin.class} keeps the host on {@code TestRunnerPlugin} regardless of what the module registers.
     */
    private static final String RUNNER_PLUGIN_CLASS = "build.jumpkick.testrunner.TestRunnerPlugin";

    /**
     * {@code jk.<worker>.plugin.jar} overrides handed to the test JVM so tests that fork a
     * first-party worker (e.g. the git client) locate its jar by path. Mirrors what Gradle's test
     * config provides; under {@code jk build} the {@code run-tests} step resolves the freshly-built
     * sibling worker jars (built before this module via the {@code [build.embed-sha]} order-after
     * edges) and passes them here. Empty when none are built (e.g. a scoped single-module build) —
     * tests then fall back to CAS-by-sha.
     */
    private Map<String, String> workerJarProps = Map.of();

    /**
     * Worker JVM flags: the heap/GC tuning, the {@code jk.plugin.class} selector for the runner, and
     * any {@code jk.<worker>.plugin.jar} overrides.
     */
    private List<String> runnerFlags(int concurrency) {
        List<String> flags = new ArrayList<>(build.jumpkick.engine.plugin.JvmOptions.workerFlags(concurrency));
        flags.add("-Djk.plugin.class=" + RUNNER_PLUGIN_CLASS);
        workerJarProps.forEach((prop, jar) -> flags.add("-D" + prop + "=" + jar));
        return flags;
    }

    /**
     * Run the project's tests. {@code workers} of 1 (today's default) takes the one-shot path;
     * anything higher fans out across pull-mode workers.
     *
     * <p>{@code listener} receives a stream of progress callbacks as events arrive — pass {@link
     * TestProgressListener#noop()} when no UI is wired.
     *
     * <p>{@code workerJarProps} maps {@code jk.<worker>.plugin.jar} property names to built
     * worker-jar paths, forwarded to the test JVM so worker-forking tests can locate their worker by
     * path (see {@link #workerJarProps}).
     */
    public TestSummary run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            Map<String, String> workerJarProps,
            TestProgressListener listener)
            throws IOException, InterruptedException {
        return run(javaHome, testClassesDir, runtimeClasspath, cacheRoot, workers, workerJarProps, listener, null);
    }

    /**
     * As {@link #run(Path, Path, List, Path, int, Map, TestProgressListener)} but also writes
     * Gradle-compatible {@code TEST-<classname>.xml} files into {@code testResultsDir} after the run
     * completes. {@code testResultsDir} may be {@code null} to skip XML output.
     */
    public TestSummary run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            Map<String, String> workerJarProps,
            TestProgressListener listener,
            Path testResultsDir)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(listener, "listener");
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");
        this.workerJarProps = workerJarProps == null ? Map.of() : Map.copyOf(workerJarProps);

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        classpathBase.add(runnerJar);
        String classpath = joinClasspath(classpathBase);
        Path javaBinary = javaBinary(javaHome);

        if (workers == 1) {
            return runSingle(javaBinary, classpath, testClassesDir, listener, testResultsDir);
        }
        return runParallel(javaBinary, classpath, testClassesDir, workers, listener, testResultsDir);
    }

    // -------- single-worker ---------------------------------------------

    private TestSummary runSingle(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener, Path testResultsDir)
            throws IOException, InterruptedException {
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = testResultsDir != null ? new MarkdownTestReport() : null;
        var aggregator = new ResultAggregator(listener, /* workerId */ 0, xml, md);
        // Capture the worker's non-protocol output so a hard crash (uncaught
        // throwable / System.exit before any test event) can be explained instead
        // of surfacing only as "runner exited N".
        var crash = new CaptureBuffer();
        int exit = build.jumpkick.engine.plugin.PluginLoader.run(
                javaBinary,
                classpath,
                runnerFlags(1),
                PROTOCOL_PREFIX,
                List.of("--scan-classpath=" + testClassesDir),
                aggregator::accept,
                line -> {
                    crash.add(line);
                    listener.onUserOutput(0, line);
                });
        TestSummary result = aggregator.toResult(exit, crash.text());
        if (xml != null) {
            try {
                xml.writeAll(testResultsDir);
            } catch (IOException e) {
                /* non-fatal: tests ran, just report writing failed */
            }
        }
        if (md != null) {
            try {
                md.writeAll(testResultsDir.getParent());
            } catch (IOException e) {
                /* non-fatal */
            }
        }
        return result;
    }

    // -------- parallel pull-queue ---------------------------------------

    private TestSummary runParallel(
            Path javaBinary,
            String classpath,
            Path testClassesDir,
            int workers,
            TestProgressListener listener,
            Path testResultsDir)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs.
        List<String> classes = discoverClasses(javaBinary, classpath, testClassesDir, listener);
        if (classes.isEmpty()) {
            return new TestSummary(0, 0, 0, 0, List.of());
        }
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());

        // One shared report per format — all worker threads write into them (both are thread-safe).
        XmlTestReport xml = testResultsDir != null ? new XmlTestReport() : null;
        MarkdownTestReport md = testResultsDir != null ? new MarkdownTestReport() : null;

        var queue = new ConcurrentLinkedDeque<>(classes);
        var aggregators = new java.util.ArrayList<ResultAggregator>();
        var workerThreads = new ArrayList<Thread>();
        int[] exits = new int[actualWorkers];
        var captures = new ArrayList<CaptureBuffer>();

        for (int w = 0; w < actualWorkers; w++) {
            final int workerId = w + 1;
            final int idx = w;
            List<String> args = List.of("--pull", "--worker=" + workerId, "--scan-classpath=" + testClassesDir);
            var agg = new ResultAggregator(listener, workerId, xml, md);
            aggregators.add(agg);
            final var crash = new CaptureBuffer();
            captures.add(crash);
            final int totalWorkers = actualWorkers;
            var t = new Thread(
                    () -> exits[idx] = driveWorker(
                            javaBinary, classpath, workerId, totalWorkers, args, queue, agg, listener, crash),
                    "jk-test-worker-" + workerId);
            t.start();
            workerThreads.add(t);
        }
        // Each worker thread owns its process (via PluginProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        for (int e : exits) {
            if (e != 0) worstExit = e;
        }
        // Merge per-worker aggregators into one TestSummary.
        long total = 0, succeeded = 0, failed = 0, skipped = 0;
        var allFailures = new ArrayList<TestSummary.Failure>();
        for (var agg : aggregators) {
            var r = agg.snapshot();
            total += r.total();
            succeeded += r.succeeded();
            failed += r.failed();
            skipped += r.skipped();
            allFailures.addAll(r.failures());
        }
        if (total == 0 && worstExit != 0) {
            // No test events but a worker died — surface what the crashed worker(s)
            // printed (the dropped stderr) instead of a bare "runner exited N".
            StringBuilder crash = new StringBuilder();
            for (int i = 0; i < actualWorkers; i++) {
                if (exits[i] != 0 && !captures.get(i).isEmpty()) {
                    if (crash.length() > 0) crash.append('\n');
                    crash.append(captures.get(i).text());
                }
            }
            return new TestSummary(
                    1, 0, 1, 0, List.of(new TestSummary.Failure("(test run)", "", "runner exited " + worstExit, crash.toString())));
        }
        if (xml != null) {
            try {
                xml.writeAll(testResultsDir);
            } catch (IOException e) {
                /* non-fatal: tests ran, just report writing failed */
            }
        }
        if (md != null) {
            try {
                md.writeAll(testResultsDir.getParent());
            } catch (IOException e) {
                /* non-fatal */
            }
        }
        return new TestSummary(total, succeeded, failed, skipped, allFailures);
    }

    /**
     * Per-worker reader thread. Reads the child's stdout line-by-line. On each {@code ready} event,
     * dispatch the next class from the shared queue (or {@code DONE} when the queue is empty) by
     * writing one line to the child's stdin. Non-protocol lines are user test output — passed through
     * to the parent's stdout, tagged with the worker id.
     */
    private int driveWorker(
            Path javaBinary,
            String classpath,
            int workerId,
            int totalWorkers,
            List<String> args,
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator,
            TestProgressListener listener,
            CaptureBuffer crash) {
        // Pull protocol: each "ready" pulls the next class from the shared queue.
        java.util.function.BiConsumer<String, PluginProcess.Conversation> handler = (json, convo) -> {
            String event = Ndjson.str(json, "event");
            if ("ready".equals(event)) {
                String next = queue.pollFirst();
                if (next != null) {
                    convo.send("RUN " + next);
                } else {
                    convo.send("DONE");
                    convo.closeInput();
                }
            } else {
                aggregator.accept(json);
            }
        };
        java.util.function.Consumer<String> passthrough = line -> {
            crash.add(line);
            listener.onUserOutput(workerId, line);
        };

        try {
            return build.jumpkick.engine.plugin.PluginLoader.converse(
                    javaBinary,
                    classpath,
                    // N test JVMs run at once → divide the heap cap by N so they fit.
                    runnerFlags(totalWorkers),
                    PROTOCOL_PREFIX,
                    args,
                    handler,
                    passthrough);
        } catch (IOException e) {
            listener.onUserOutput(workerId, "reader error: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Step one of parallel mode: list every top-level test class without running anything. Uses
     * {@code Launcher.discover} (not {@code execute}) so this completes in 100–300 ms even for big
     * suites.
     */
    private List<String> discoverClasses(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener)
            throws IOException, InterruptedException {
        var classes = new ArrayList<String>();
        build.jumpkick.engine.plugin.PluginLoader.run(
                javaBinary,
                classpath,
                runnerFlags(1),
                PROTOCOL_PREFIX,
                List.of("--list-only", "--scan-classpath=" + testClassesDir),
                json -> {
                    String event = Ndjson.str(json, "event");
                    if ("discovered".equals(event)) {
                        classes.add(Ndjson.str(json, "class"));
                    } else if ("discovery_total".equals(event)) {
                        listener.onDiscoveryTotal(
                                Ndjson.intValue(json, "classes", 0), Ndjson.intValue(json, "tests", 0));
                    }
                },
                null);
        return classes;
    }

    // -------- shared helpers --------------------------------------------

    /**
     * Look up the jk-test-runner jar in the local CAS, keyed by its SHA-256 (the hash this build of
     * engine was paired against — embedded as a resource at {@link #RUNNER_SHA_RESOURCE} by Gradle's
     * {@code writeRunnerSha} task).
     *
     * <p>Until jk-test-runner ships to Maven Central, the user is responsible for side-loading the
     * jar into the CAS — typically by running {@code ./gradlew :test-runner:installLocalCas} in jk's
     * own tree. Once the runner is published, {@code jk sync} will populate the CAS automatically.
     *
     * <p>Throws {@link IOException} with side-load instructions if the jar isn't in the CAS at the
     * expected hash. The error message spells out the exact destination path the user needs to
     * populate.
     */
    private static Path locateRunner(Path cacheRoot) throws IOException {
        // Location (override → CAS-by-SHA) is shared with every other worker via
        // PluginJar; adapt its IllegalStateException to this method's IOException.
        try {
            return PluginJar.TEST_RUNNER.locate(new Cas(cacheRoot));
        } catch (IllegalStateException e) {
            throw new IOException("jk test: " + e.getMessage(), e);
        }
    }

    private static String joinClasspath(Iterable<Path> entries) {
        var sb = new StringBuilder();
        for (Path p : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(p);
        }
        return sb.toString();
    }

    private static Path javaBinary(Path javaHome) {
        return javaHome.resolve("bin").resolve(HostPlatform.isWindows() ? "java.exe" : "java");
    }

    // -------- event aggregation -----------------------------------------

    /**
     * Thread-safe accumulator. Multiple driveWorker threads call into {@link #accept} concurrently.
     * Counts come from FINISHED / SKIPPED events for {@code type == TEST} — we ignore CONTAINER nodes
     * (the engine root, classes themselves) so totals match {@code SummaryGeneratingListener}
     * semantics. Failure detail is pulled from FINISHED[status=FAILED] events.
     *
     * <p>Counting per-event (not summing per-worker plan totals) sidesteps a {@link
     * org.junit.platform.launcher.listeners.SummaryGeneratingListener} quirk: it resets its
     * accumulator on every {@code testPlanExecutionStarted} — which fires per {@code
     * Launcher.execute()} call — so in pull mode a worker's final summary reflects only its last
     * class.
     */
    static final class ResultAggregator {

        private final TestProgressListener listener;
        private final int workerId;
        private final XmlTestReport xmlReport;
        private final MarkdownTestReport mdReport;
        private long succeeded;
        private long failed;
        private long skipped;
        private final List<TestSummary.Failure> failures = new ArrayList<>();
        // Tests whose `dynamic_registered` event we observed at execute-time
        // — i.e., @ParameterizedTest / @TestFactory / @TestTemplate /
        // @RepeatedTest invocations that weren't in the static plan. Used
        // to mark their later `finished`/`skipped` events as wasStatic=false
        // so progress UIs can keep a stable static-plan denominator.
        private final java.util.Set<String> dynamicIds = new java.util.HashSet<>();

        /** Test-friendly ctor: no listener, no worker id, no reports. */
        ResultAggregator() {
            this(TestProgressListener.noop(), 0, null, null);
        }

        ResultAggregator(TestProgressListener listener, int workerId) {
            this(listener, workerId, null, null);
        }

        ResultAggregator(
                TestProgressListener listener, int workerId, XmlTestReport xmlReport, MarkdownTestReport mdReport) {
            this.listener = listener;
            this.workerId = workerId;
            this.xmlReport = xmlReport;
            this.mdReport = mdReport;
        }

        synchronized void accept(String json) {
            // A non-protocol line shows up here only in tests that call accept()
            // directly. In production the caller already stripped the prefix, so
            // any line that doesn't look like a JSON object is user output.
            if (json == null || !json.startsWith("{")) {
                listener.onUserOutput(workerId, json);
                return;
            }
            acceptJson(json);
        }

        private void acceptJson(String json) {
            String event = Ndjson.str(json, "event");
            if (event == null) return;
            switch (event) {
                case "discovery_total" ->
                    listener.onDiscoveryTotal(Ndjson.intValue(json, "classes", 0), Ndjson.intValue(json, "tests", 0));
                case "dynamic_registered" -> {
                    if ("TEST".equals(Ndjson.str(json, "type"))) {
                        dynamicIds.add(Ndjson.str(json, "id"));
                    }
                }
                case "started" -> onStarted(json);
                case "finished" -> onFinished(json);
                case "skipped" -> onSkipped(json);
                default -> {}
            }
        }

        private void onStarted(String json) {
            boolean isTest = "TEST".equals(Ndjson.str(json, "type"));
            listener.onTestStarted(Ndjson.str(json, "id"), Ndjson.str(json, "display"), isTest, workerId);
        }

        private void onFinished(String json) {
            boolean isTest = "TEST".equals(Ndjson.str(json, "type"));
            String id = Ndjson.str(json, "id");
            String status = Ndjson.str(json, "status");
            String display = Ndjson.str(json, "display");
            if (display == null) display = id;
            long duration = Ndjson.intValue(json, "duration_ms", 0);
            boolean wasStatic = isTest && !dynamicIds.contains(id);
            if (isTest) {
                switch (status != null ? status : "") {
                    case "SUCCESSFUL" -> succeeded++;
                    case "FAILED" -> captureFailure(id, display, json);
                    case "ABORTED" -> skipped++;
                    default -> {}
                }
            } else if ("FAILED".equals(status)) {
                // A container-level failure (class initializer / @BeforeAll / engine):
                // no per-test event follows, so without capturing it the run would
                // surface only as a bare "runner exited N". Record it with its stack.
                captureFailure(id, display + " (container)", json);
            }
            listener.onTestFinished(id, display, status, isTest, wasStatic, duration, workerId);
            if (isTest) {
                String throwable = Ndjson.nested(json, "throwable");
                if ("ABORTED".equals(status)) {
                    if (xmlReport != null) xmlReport.recordSkipped(id, display, "aborted");
                    if (mdReport != null) mdReport.recordSkipped(id, display, "aborted");
                } else {
                    if (xmlReport != null) xmlReport.recordFinished(id, display, duration, throwable);
                    if (mdReport != null) mdReport.recordFinished(id, display, duration, throwable);
                }
            }
        }

        /** Record a FAILED test/container: count it and keep its summary + full stack. */
        private void captureFailure(String id, String display, String json) {
            failed++;
            String throwableJson = Ndjson.nested(json, "throwable");
            String exClass = throwableJson != null ? Ndjson.str(throwableJson, "class") : null;
            if (exClass == null) exClass = "?";
            String message = throwableJson != null ? Ndjson.str(throwableJson, "message") : null;
            if (message == null) message = "";
            // The runner emits the full stack trace under "stack"; keep it so the
            // build can print it (we used to read only class + message).
            String stack = throwableJson != null ? Ndjson.str(throwableJson, "stack") : null;
            failures.add(new TestSummary.Failure(display, exClass, message, stack == null ? "" : stack));
            listener.onFailure(id, display, exClass, message, workerId);
        }

        private void onSkipped(String json) {
            boolean isTest = "TEST".equals(Ndjson.str(json, "type"));
            String id = Ndjson.str(json, "id");
            boolean wasStatic = isTest && !dynamicIds.contains(id);
            if (isTest) skipped++;
            String reason = Ndjson.str(json, "reason");
            listener.onTestSkipped(
                    id, Ndjson.str(json, "display"), reason != null ? reason : "", isTest, wasStatic, workerId);
            if (isTest) {
                String display = Ndjson.str(json, "display");
                if (xmlReport != null) xmlReport.recordSkipped(id, display, reason);
                if (mdReport != null) mdReport.recordSkipped(id, display, reason);
            }
        }

        synchronized TestSummary toResult(int exitCode) {
            return toResult(exitCode, "");
        }

        /**
         * As {@link #toResult(int)}, attaching {@code crashOutput} (the worker's captured
         * stdout/stderr) to the synthetic "runner exited" failure so a hard crash with no test events
         * still explains itself.
         */
        synchronized TestSummary toResult(int exitCode, String crashOutput) {
            long total = succeeded + failed + skipped;
            if (total == 0 && exitCode != 0) {
                return new TestSummary(
                        1,
                        0,
                        1,
                        0,
                        List.of(new TestSummary.Failure(
                                "(test run)",
                                "",
                                "runner exited " + exitCode,
                                crashOutput == null ? "" : crashOutput)));
            }
            return new TestSummary(total, succeeded, failed, skipped, List.copyOf(failures));
        }

        /** Snapshot of just the counters — used by the parallel-merge path. */
        synchronized TestSummary snapshot() {
            long total = succeeded + failed + skipped;
            return new TestSummary(total, succeeded, failed, skipped, List.copyOf(failures));
        }
    }

    /**
     * Bounded, thread-safe tail of a worker's non-protocol output. Kept so a hard crash (uncaught
     * throwable / {@code System.exit} before any test event) can be explained — the runner prints the
     * stack to stderr, which is otherwise dropped unless {@code --verbose}. Capped to the last {@link
     * #MAX_LINES} lines so a chatty-then-crashing worker can't blow up memory.
     */
    static final class CaptureBuffer {
        private static final int MAX_LINES = 400;
        private final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();

        synchronized void add(String line) {
            if (line == null) return;
            lines.addLast(line);
            if (lines.size() > MAX_LINES) lines.removeFirst();
        }

        synchronized boolean isEmpty() {
            return lines.isEmpty();
        }

        synchronized String text() {
            return String.join("\n", lines);
        }
    }

}
