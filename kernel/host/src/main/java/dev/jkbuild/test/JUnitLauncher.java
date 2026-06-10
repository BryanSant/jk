// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Runs a project's compiled tests by forking child JVM(s) that invoke
 * {@code dev.jkbuild.test.runner.JkRunner}. Supports two modes:
 *
 * <ul>
 *   <li><b>Single-worker</b> ({@code workers=1}, default): one child JVM
 *       discovers and runs everything, streaming events back via NDJSON on
 *       stdout. The baseline path — preserves today's behavior exactly.</li>
 *   <li><b>Parallel pull-queue</b> ({@code workers>1}): spawn a discovery
 *       child first to enumerate test classes, then spawn N pull-mode
 *       workers concurrently. Each worker stays alive across multiple
 *       classes; the parent dispatches one class at a time via RUN/DONE
 *       commands on the worker's stdin, in response to {@code ready}
 *       events the worker emits after each class completes. Process
 *       isolation between forks; serial within a fork.</li>
 * </ul>
 *
 * <p>Wire protocol: child emits one NDJSON event per line on stdout, each
 * line prefixed with {@code ##JK:}. Lines without the prefix are the user's
 * test stdout/stderr — forwarded to the parent's stdout with a
 * {@code [w<N>] } worker prefix in parallel mode.
 */
public final class JUnitLauncher {

    /** Marker prefix every protocol line carries. Must match {@code JsonEventWriter.PREFIX}. */
    private static final String PROTOCOL_PREFIX = "##JK:";


    /**
     * Run the project's tests. {@code workers} of 1 (today's default) takes
     * the one-shot path; anything higher fans out across pull-mode workers.
     *
     * <p>{@code listener} receives a stream of progress callbacks as events
     * arrive — pass {@link TestProgressListener#noop()} when no UI is wired.
     */
    public Result run(
            Path javaHome,
            Path testClassesDir,
            List<Path> runtimeClasspath,
            Path cacheRoot,
            int workers,
            TestProgressListener listener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(listener, "listener");
        if (workers < 1) throw new IllegalArgumentException("workers must be >= 1");

        Path runnerJar = locateRunner(cacheRoot);
        var classpathBase = new LinkedHashSet<Path>();
        classpathBase.add(testClassesDir);
        classpathBase.addAll(runtimeClasspath);
        classpathBase.add(runnerJar);
        String classpath = joinClasspath(classpathBase);
        Path javaBinary = javaBinary(javaHome);

        if (workers == 1) {
            return runSingle(javaBinary, classpath, testClassesDir, listener);
        }
        return runParallel(javaBinary, classpath, testClassesDir, workers, listener);
    }

    // -------- single-worker ---------------------------------------------

    private Result runSingle(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener)
            throws IOException, InterruptedException {
        // Phase 6: test-runner is a friendly plugin — load in-process by default.
        // PluginLoader falls back to fork if manifest says isolation=process.
        var aggregator = new ResultAggregator(listener, /* workerId */ 0);
        int exit = dev.jkbuild.host.PluginLoader.run(
                runnerJarFromClasspath(classpath),
                classpathEntries(classpath),
                List.of("--scan-classpath=" + testClassesDir),
                aggregator::accept,
                line -> listener.onUserOutput(0, line));
        return aggregator.toResult(exit);
    }

    /** Extract the runner jar path from the combined classpath string. */
    private static java.nio.file.Path runnerJarFromClasspath(String classpath) {
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.contains("jk-test-runner") || entry.contains("test-runner")) {
                return java.nio.file.Path.of(entry);
            }
        }
        // Fallback: last entry is usually the runner jar
        String[] parts = classpath.split(java.io.File.pathSeparator);
        return java.nio.file.Path.of(parts[parts.length - 1]);
    }

    /** All classpath entries except the runner jar (go on the extra-classpath). */
    private static List<java.nio.file.Path> classpathEntries(String classpath) {
        var result = new java.util.ArrayList<java.nio.file.Path>();
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.contains("jk-test-runner") && !entry.contains("test-runner")) {
                result.add(java.nio.file.Path.of(entry));
            }
        }
        return result;
    }

    // -------- parallel pull-queue ---------------------------------------

    private Result runParallel(
            Path javaBinary, String classpath, Path testClassesDir, int workers, TestProgressListener listener)
            throws IOException, InterruptedException {
        // 1. Discovery — one fork, list-only mode, harvest class FQCNs.
        List<String> classes = discoverClasses(javaBinary, classpath, testClassesDir, listener);
        if (classes.isEmpty()) {
            return new Result(0, 0, 0, 0, List.of());
        }
        // Don't waste workers on small suites — N workers > N classes leaves
        // some idle waiting for a class that'll never come.
        int actualWorkers = Math.min(workers, classes.size());

        var queue = new ConcurrentLinkedDeque<>(classes);
        var aggregators = new java.util.ArrayList<ResultAggregator>();
        var workerThreads = new ArrayList<Thread>();
        int[] exits = new int[actualWorkers];

        for (int w = 0; w < actualWorkers; w++) {
            final int workerId = w + 1;
            final int idx = w;
            List<String> cmd = List.of(
                    javaBinary.toString(), "-cp", classpath,
                    "dev.jkbuild.plugin.host.PluginHostMain",
                    "--pull",
                    "--worker=" + workerId,
                    "--scan-classpath=" + testClassesDir);
            var agg = new ResultAggregator(listener, workerId);
            aggregators.add(agg);
            final String classpathForWorker = classpath;
            var t = new Thread(
                    () -> exits[idx] = driveWorker(cmd, classpathForWorker, workerId, queue, agg, listener),
                    "jk-test-worker-" + workerId);
            t.start();
            workerThreads.add(t);
        }
        // Each worker thread owns its process (via WorkerProcess.converse) and
        // returns its exit code once stdout is fully drained and the process
        // has exited. Join them and take the worst exit.
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        for (int e : exits) {
            if (e != 0) worstExit = e;
        }
        // Merge per-worker aggregators into one Result.
        long total = 0, succeeded = 0, failed = 0, skipped = 0;
        var allFailures = new ArrayList<Failure>();
        for (var agg : aggregators) {
            var r = agg.snapshot();
            total += r.total;
            succeeded += r.succeeded;
            failed += r.failed;
            skipped += r.skipped;
            allFailures.addAll(r.failures);
        }
        if (total == 0 && worstExit != 0) {
            return new Result(1, 0, 1, 0, List.of(
                    new Failure("(test run)", "runner exited " + worstExit)));
        }
        return new Result(total, succeeded, failed, skipped, allFailures);
    }

    /**
     * Per-worker reader thread. Reads the child's stdout line-by-line. On
     * each {@code ready} event, dispatch the next class from the shared
     * queue (or {@code DONE} when the queue is empty) by writing one line
     * to the child's stdin. Non-protocol lines are user test output —
     * passed through to the parent's stdout, tagged with the worker id.
     */
    private int driveWorker(
            List<String> cmd, String classpath, int workerId,
            ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator, TestProgressListener listener) {
        // Phase 6: pull-mode workers run in-process via PluginLoader.converse.
        java.util.function.BiConsumer<String, WorkerProcess.Conversation> handler = (json, convo) -> {
            String event = Ndjson.str(json, "e");
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
        java.util.function.Consumer<String> passthrough = line -> listener.onUserOutput(workerId, line);

        // Extract the plugin args: everything after PluginHostMain in cmd.
        List<String> pluginArgs = new java.util.ArrayList<>();
        boolean afterMain = false;
        for (String s : cmd) {
            if (afterMain) pluginArgs.add(s);
            else if ("dev.jkbuild.plugin.host.PluginHostMain".equals(s)) afterMain = true;
        }

        try {
            return dev.jkbuild.host.PluginLoader.converse(
                    runnerJarFromClasspath(classpath),
                    classpathEntries(classpath),
                    pluginArgs, handler, passthrough);
        } catch (IOException e) {
            listener.onUserOutput(workerId, "reader error: " + e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * Phase one of parallel mode: list every top-level test class without
     * running anything. Uses {@code Launcher.discover} (not {@code execute})
     * so this completes in 100–300 ms even for big suites.
     */
    private List<String> discoverClasses(
            Path javaBinary, String classpath, Path testClassesDir, TestProgressListener listener)
            throws IOException, InterruptedException {
        // Phase 6: in-process via PluginLoader (same fallback logic as runSingle).
        var classes = new ArrayList<String>();
        dev.jkbuild.host.PluginLoader.run(
                runnerJarFromClasspath(classpath),
                classpathEntries(classpath),
                List.of("--list-only", "--scan-classpath=" + testClassesDir),
                json -> {
                    String event = Ndjson.str(json, "e");
                    if ("discovered".equals(event)) {
                        classes.add(Ndjson.str(json, "class"));
                    } else if ("discovery_total".equals(event)) {
                        listener.onDiscoveryTotal(
                                Ndjson.intValue(json, "classes", 0),
                                Ndjson.intValue(json, "tests", 0));
                    }
                }, null);
        return classes;
    }

    // -------- shared helpers --------------------------------------------

    /**
     * Look up the jk-test-runner jar in the local CAS, keyed by its
     * SHA-256 (the hash this build of engine was paired against —
     * embedded as a resource at {@link #RUNNER_SHA_RESOURCE} by
     * Gradle's {@code writeRunnerSha} task).
     *
     * <p>Until jk-test-runner ships to Maven Central, the user is
     * responsible for side-loading the jar into the CAS — typically by
     * running {@code ./gradlew :test-runner:installLocalCas} in jk's
     * own tree. Once the runner is published, {@code jk sync} will
     * populate the CAS automatically.
     *
     * <p>Throws {@link IOException} with side-load instructions if the
     * jar isn't in the CAS at the expected hash. The error message
     * spells out the exact destination path the user needs to populate.
     */
    private static Path locateRunner(Path cacheRoot) throws IOException {
        // Location (override → CAS-by-SHA) is shared with every other worker via
        // WorkerJar; adapt its IllegalStateException to this method's IOException.
        try {
            return WorkerJar.TEST_RUNNER.locate(new Cas(cacheRoot));
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
        return javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // -------- event aggregation -----------------------------------------

    /**
     * Thread-safe accumulator. Multiple driveWorker threads call into
     * {@link #accept} concurrently. Counts come from FINISHED / SKIPPED
     * events for {@code type == TEST} — we ignore CONTAINER nodes (the
     * engine root, classes themselves) so totals match
     * {@code SummaryGeneratingListener} semantics. Failure detail is
     * pulled from FINISHED[status=FAILED] events.
     *
     * <p>Counting per-event (not summing per-worker plan totals) sidesteps
     * a {@link org.junit.platform.launcher.listeners.SummaryGeneratingListener}
     * quirk: it resets its accumulator on every {@code testPlanExecutionStarted}
     * — which fires per {@code Launcher.execute()} call — so in pull mode a
     * worker's final summary reflects only its last class.
     */
    static final class ResultAggregator {

        private final TestProgressListener listener;
        private final int workerId;
        private long succeeded;
        private long failed;
        private long skipped;
        private final List<Failure> failures = new ArrayList<>();
        // Tests whose `dynamic_registered` event we observed at execute-time
        // — i.e., @ParameterizedTest / @TestFactory / @TestTemplate /
        // @RepeatedTest invocations that weren't in the static plan. Used
        // to mark their later `finished`/`skipped` events as wasStatic=false
        // so progress UIs can keep a stable static-plan denominator.
        private final java.util.Set<String> dynamicIds = new java.util.HashSet<>();

        /** Test-friendly ctor: no listener, no worker id. */
        ResultAggregator() {
            this(TestProgressListener.noop(), 0);
        }

        ResultAggregator(TestProgressListener listener, int workerId) {
            this.listener = listener;
            this.workerId = workerId;
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
            String event = Ndjson.str(json, "e");
            if (event == null) return;
            switch (event) {
                case "discovery_total" -> listener.onDiscoveryTotal(
                        Ndjson.intValue(json, "classes", 0),
                        Ndjson.intValue(json, "tests", 0));
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
            listener.onTestStarted(
                    Ndjson.str(json, "id"),
                    Ndjson.str(json, "display"),
                    isTest,
                    workerId);
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
                    case "FAILED" -> {
                        failed++;
                        String throwableJson = Ndjson.nested(json, "throwable");
                        String exClass = throwableJson != null ? Ndjson.str(throwableJson, "class") : null;
                        if (exClass == null) exClass = "?";
                        String message = throwableJson != null ? Ndjson.str(throwableJson, "message") : null;
                        if (message == null) message = "";
                        failures.add(new Failure(display, exClass + ": " + message));
                        listener.onFailure(id, display, exClass, message, workerId);
                    }
                    case "ABORTED" -> skipped++;
                    default -> {}
                }
            }
            listener.onTestFinished(id, display, status, isTest, wasStatic, duration, workerId);
        }

        private void onSkipped(String json) {
            boolean isTest = "TEST".equals(Ndjson.str(json, "type"));
            String id = Ndjson.str(json, "id");
            boolean wasStatic = isTest && !dynamicIds.contains(id);
            if (isTest) skipped++;
            String reason = Ndjson.str(json, "reason");
            listener.onTestSkipped(
                    id,
                    Ndjson.str(json, "display"),
                    reason != null ? reason : "",
                    isTest,
                    wasStatic,
                    workerId);
        }

        synchronized Result toResult(int exitCode) {
            long total = succeeded + failed + skipped;
            if (total == 0 && exitCode != 0) {
                return new Result(1, 0, 1, 0, List.of(
                        new Failure("(test run)", "runner exited " + exitCode)));
            }
            return new Result(total, succeeded, failed, skipped, List.copyOf(failures));
        }

        /** Snapshot of just the counters — used by the parallel-merge path. */
        synchronized Result snapshot() {
            long total = succeeded + failed + skipped;
            return new Result(total, succeeded, failed, skipped, List.copyOf(failures));
        }
    }

    public record Result(
            long total,
            long succeeded,
            long failed,
            long skipped,
            List<Failure> failures) {

        public Result {
            failures = List.copyOf(failures);
        }

        public boolean allPassed() { return failed == 0; }
    }

    public record Failure(String testName, String message) {}
}
