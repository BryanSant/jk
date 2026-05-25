// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runs a project's compiled tests by forking a child JVM and invoking
 * {@code dev.jkbuild.test.runner.JkRunner} (jk's tiny test-runner shim
 * bundled as a classpath resource and extracted on first use).
 *
 * <p>The child JVM is the project's pinned JDK from {@code jk.toml} /
 * {@code jk.lock}, so a test compiled for JDK 25 actually runs on JDK 25.
 * The runner uses {@code junit-platform-launcher} + {@code junit-jupiter}
 * — both injected into the user's TEST scope at lock time by
 * {@code LockOrchestrator}, sourced from the CAS like any other dep.
 *
 * <p>Wire protocol: the child emits one NDJSON event per line on stdout,
 * each line prefixed with {@code ##JK:}. Lines without the prefix are the
 * user's tests printing — passed through to the parent's stdout verbatim.
 */
public final class JUnitLauncher {

    /** Marker prefix every protocol line carries. Must match {@code JsonEventWriter.PREFIX} in the runner. */
    private static final String PROTOCOL_PREFIX = "##JK:";

    /** Where the embedded runner jar lives inside the cli jar / native binary. */
    private static final String RUNNER_RESOURCE = "/dev/jkbuild/test/runner/jk-test-runner.jar";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public Result run(Path javaHome, Path testClassesDir, List<Path> runtimeClasspath,
                      Path runnerCacheDir, String jkVersion)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");
        Objects.requireNonNull(runnerCacheDir, "runnerCacheDir");
        Objects.requireNonNull(jkVersion, "jkVersion");

        Path runnerJar = extractRunner(runnerCacheDir, jkVersion);

        var classpath = new LinkedHashSet<Path>();
        classpath.add(testClassesDir);
        classpath.addAll(runtimeClasspath);
        classpath.add(runnerJar);

        Path javaBinary = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");

        var cmd = new ArrayList<String>();
        cmd.add(javaBinary.toString());
        cmd.add("-cp");
        cmd.add(joinClasspath(classpath));
        cmd.add("dev.jkbuild.test.runner.JkRunner");
        cmd.add("--scan-classpath=" + testClassesDir);

        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        var process = pb.start();

        var aggregator = new ResultAggregator();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(PROTOCOL_PREFIX)) {
                    aggregator.accept(line.substring(PROTOCOL_PREFIX.length()));
                } else {
                    // Plain test output — pass straight through to the user.
                    System.out.println(line);
                }
            }
        }
        int exit = process.waitFor();
        return aggregator.toResult(exit);
    }

    /**
     * Copy the runner jar out of {@code /dev/jkbuild/test/runner/jk-test-runner.jar}
     * (bundled at cli build time) into {@code <cacheDir>/<jkVersion>.jar}. The
     * cache is keyed by jk version, not jar SHA, because the resource lives in
     * the binary we're running — if jk gets upgraded the cache file is replaced.
     */
    private static Path extractRunner(Path cacheDir, String jkVersion) throws IOException {
        Path target = cacheDir.resolve(jkVersion + ".jar");
        if (Files.isRegularFile(target)) return target;
        Files.createDirectories(cacheDir);
        try (InputStream in = JUnitLauncher.class.getResourceAsStream(RUNNER_RESOURCE)) {
            if (in == null) {
                throw new IOException("jk test: " + RUNNER_RESOURCE + " missing from this build "
                        + "(processResources didn't bundle it)");
            }
            // Stage to a temp file in the same directory, then rename — keeps
            // concurrent jk invocations from racing on a half-written jar.
            Path tmp = Files.createTempFile(cacheDir, "jk-test-runner-", ".jar.tmp");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static String joinClasspath(Iterable<Path> entries) {
        var sb = new StringBuilder();
        for (Path p : entries) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ------------------------------------------------------------------
    // Event-stream aggregation
    // ------------------------------------------------------------------

    /**
     * Consumes the runner's NDJSON event stream and builds the {@link Result}
     * we hand back to {@code TestCommand}. {@code plan_finished} provides the
     * authoritative totals; we additionally collect per-test failure details
     * from {@code finished} events so the parent can render a {@code FAIL: …}
     * list without re-parsing later.
     */
    private static final class ResultAggregator {

        private long total;
        private long succeeded;
        private long failed;
        private long skipped;
        private final List<Failure> failures = new ArrayList<>();

        void accept(String json) {
            JsonNode node;
            try {
                node = JSON.readTree(json);
            } catch (RuntimeException e) {
                // Malformed event — surface to stderr but don't kill the run.
                // Jackson 3 throws JacksonException (unchecked) for parse errors.
                System.err.println("jk test: malformed protocol line: " + json);
                return;
            }
            var event = node.path("e").asString();
            switch (event) {
                case "finished" -> onFinished(node);
                case "plan_finished" -> onPlanFinished(node);
                // started / skipped / dynamic_registered / report / plan_started
                // are no-ops for the summary today — they'll feed live UI in a
                // follow-up that wires per-test rendering.
                default -> {}
            }
        }

        private void onFinished(JsonNode node) {
            var status = node.path("status").asString();
            if (!"FAILED".equals(status)) return;
            var id = node.path("id").asString();
            var throwable = node.path("throwable");
            String message = throwable.isMissingNode()
                    ? "(no throwable)"
                    : throwable.path("class").asString("?") + ": "
                            + throwable.path("message").asString("");
            failures.add(new Failure(id, message));
        }

        private void onPlanFinished(JsonNode node) {
            total = node.path("total").asLong(0);
            succeeded = node.path("successful").asLong(0);
            failed = node.path("failed").asLong(0);
            skipped = node.path("skipped").asLong(0);
        }

        Result toResult(int exitCode) {
            // Exit code is the source of truth — if the runner died abruptly
            // (no plan_finished event) we still report a failure.
            if (total == 0 && failed == 0 && exitCode != 0) {
                return new Result(1, 0, 1, 0, List.of(
                        new Failure("(test run)", "runner exited " + exitCode)));
            }
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
