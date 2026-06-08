// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.Ndjson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
     * Build-time-generated resource carrying the SHA-256 of the
     * jk-test-runner jar this build of engine pairs with. Engine reads
     * the hash, then looks the jar up in the CAS at that key. See
     * {@code engine/build.gradle.kts:writeRunnerSha}.
     */
    private static final String RUNNER_SHA_RESOURCE = "/META-INF/jk-test-runner-sha256.txt";

    /** Override for the jk-test-runner jar path (tests, dev). Takes precedence over the CAS lookup. */
    static final String RUNNER_JAR_PROPERTY = "jk.test.runner.jar";

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
        var cmd = new ArrayList<String>();
        cmd.add(javaBinary.toString());
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("dev.jkbuild.test.runner.JkRunner");
        cmd.add("--scan-classpath=" + testClassesDir);

        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        var process = pb.start();
        var aggregator = new ResultAggregator(listener, /* workerId */ 0);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(PROTOCOL_PREFIX)) {
                    aggregator.accept(line.substring(PROTOCOL_PREFIX.length()));
                } else {
                    listener.onUserOutput(0, line);
                }
            }
        }
        int exit = process.waitFor();
        return aggregator.toResult(exit);
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
        var workerProcesses = new ArrayList<Process>();

        for (int w = 1; w <= actualWorkers; w++) {
            final int workerId = w;
            var pb = new ProcessBuilder(
                    javaBinary.toString(),
                    "-cp", classpath,
                    "dev.jkbuild.test.runner.JkRunner",
                    "--pull",
                    "--worker=" + workerId,
                    "--scan-classpath=" + testClassesDir)
                    .redirectErrorStream(true);
            var proc = pb.start();
            workerProcesses.add(proc);
            var agg = new ResultAggregator(listener, workerId);
            aggregators.add(agg);
            var t = new Thread(
                    () -> driveWorker(proc, workerId, queue, agg, listener),
                    "jk-test-worker-" + workerId);
            t.start();
            workerThreads.add(t);
        }
        // Wait for all reader threads first — they drain stdout fully. Then
        // join the OS processes (they should already be exiting by the time
        // we get here since their stdin was closed).
        for (Thread t : workerThreads) t.join();
        int worstExit = 0;
        for (Process p : workerProcesses) {
            int exit = p.waitFor();
            if (exit != 0) worstExit = exit;
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
    private static void driveWorker(
            Process proc, int workerId, ConcurrentLinkedDeque<String> queue,
            ResultAggregator aggregator, TestProgressListener listener) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8), true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(PROTOCOL_PREFIX)) {
                    listener.onUserOutput(workerId, line);
                    continue;
                }
                String json = line.substring(PROTOCOL_PREFIX.length());
                String event = Ndjson.str(json, "e");
                if ("ready".equals(event)) {
                    String next = queue.pollFirst();
                    if (next != null) {
                        writer.println("RUN " + next);
                    } else {
                        // Closing stdin makes the child's readLine return
                        // null so it exits its pull loop cleanly. Sending
                        // DONE first is for symmetry — either signal works.
                        writer.println("DONE");
                        writer.flush();
                        try {
                            proc.getOutputStream().close();
                        } catch (IOException ignored) {
                            // already-closed pipe is fine
                        }
                    }
                } else {
                    aggregator.accept(json);
                }
            }
        } catch (IOException e) {
            listener.onUserOutput(workerId, "reader error: " + e.getMessage());
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
        var pb = new ProcessBuilder(
                javaBinary.toString(),
                "-cp", classpath,
                "dev.jkbuild.test.runner.JkRunner",
                "--list-only",
                "--scan-classpath=" + testClassesDir)
                .redirectErrorStream(true);
        var proc = pb.start();
        var classes = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(PROTOCOL_PREFIX)) continue;
                String json = line.substring(PROTOCOL_PREFIX.length());
                String event = Ndjson.str(json, "e");
                if ("discovered".equals(event)) {
                    classes.add(Ndjson.str(json, "class"));
                } else if ("discovery_total".equals(event)) {
                    listener.onDiscoveryTotal(
                            Ndjson.intValue(json, "classes", 0),
                            Ndjson.intValue(json, "tests", 0));
                }
            }
        }
        proc.waitFor();
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
        // Override (tests / dev) wins over the CAS lookup, mirroring the Kotlin
        // worker's jk.kotlin.worker.jar — lets a test JVM point at a freshly-built
        // runner without side-loading it into an ephemeral cache.
        String override = System.getProperty(RUNNER_JAR_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            if (Files.isRegularFile(jar)) return jar;
            throw new IOException(RUNNER_JAR_PROPERTY + " is set to '" + override
                    + "' but no file exists there.");
        }
        String expectedHash = readExpectedHash();
        Cas cas = new Cas(cacheRoot);
        Path target = cas.pathFor(expectedHash);
        if (Files.isRegularFile(target)) {
            return target;
        }
        throw new IOException(
                "jk test: jk-test-runner.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Until jk-test-runner is published to a primary repo, side-load it.");
    }

    private static String readExpectedHash() throws IOException {
        try (InputStream in = JUnitLauncher.class.getResourceAsStream(RUNNER_SHA_RESOURCE)) {
            if (in == null) {
                throw new IOException(
                        "jk test: " + RUNNER_SHA_RESOURCE + " missing from this engine build "
                        + "(writeRunnerSha didn't run)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
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
