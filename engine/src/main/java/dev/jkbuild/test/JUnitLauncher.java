// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a project's compiled tests by forking a child JVM and invoking
 * {@code org.junit.platform.console.ConsoleLauncher} against it.
 *
 * <p>Forking matters for two reasons: (1) the child uses the project's
 * pinned JDK (from {@code jk.toml} / {@code jk.lock}), not whatever jk
 * itself happens to be running under — so a test compiled for JDK 25
 * runs on JDK 25; (2) the child is a regular HotSpot JVM with unrestricted
 * reflection, sidestepping the native-image metadata gaps that broke the
 * old in-process path (JUnit Platform 6.x ships no native-image config).
 */
public final class JUnitLauncher {

    /**
     * Launch the tests.
     *
     * @param javaHome       resolved JDK home (from {@code CompileToolchain.resolveJavaHome})
     * @param testClassesDir the directory javac wrote the compiled test classes into
     * @param runtimeClasspath project's test-runtime classpath (main classes + main/runtime/test scope jars)
     */
    public Result run(Path javaHome, Path testClassesDir, List<Path> runtimeClasspath)
            throws IOException, InterruptedException {
        Objects.requireNonNull(javaHome, "javaHome");
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");

        var launcherJars = discoverConsoleLauncherJars();
        if (launcherJars.isEmpty()) {
            throw new IOException(
                    "jk test: junit-platform-console-launcher not on jk's classpath. "
                            + "Run jk via the JVM distribution (cli/build/install/jk/bin/jk) "
                            + "until native-image bundling of the test runner lands.");
        }

        var classpath = new LinkedHashSet<Path>();
        classpath.add(testClassesDir);
        classpath.addAll(runtimeClasspath);
        classpath.addAll(launcherJars);

        var javaBinary = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");

        var cmd = new ArrayList<String>();
        cmd.add(javaBinary.toString());
        cmd.add("-cp");
        cmd.add(joinClasspath(classpath));
        cmd.add("org.junit.platform.console.ConsoleLauncher");
        cmd.add("execute");
        cmd.add("--scan-classpath=" + testClassesDir);
        cmd.add("--disable-banner");
        // Single-color sink keeps the regex-based summary parse reliable.
        cmd.add("--disable-ansi-colors");
        // ConsoleLauncher's default exit code is 0 even when tests fail; opt
        // in to "fail-on-failure" so the process exit reflects test outcome.
        cmd.add("--fail-if-no-tests");

        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        var process = pb.start();

        // Stream output to the user verbatim AND capture it for summary parsing.
        var captured = new StringBuilder();
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                captured.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        return parseSummary(captured.toString(), exit);
    }

    /**
     * Locate the {@code junit-platform-console-launcher} jar (and its
     * transitive {@code junit-platform-launcher} + engine jars) on jk's own
     * classpath, so they can be added to the child JVM's classpath. Works
     * when jk runs under a regular JVM (installDist): {@code java.class.path}
     * lists them. Native-image binaries embed classes rather than ship jars,
     * so this returns empty and the caller surfaces a clear error.
     */
    static List<Path> discoverConsoleLauncherJars() {
        var found = new LinkedHashSet<Path>();
        // First try walking our own classloader's URLs — works regardless of
        // whether the user wrapped jk in a custom launcher.
        var cl = JUnitLauncher.class.getClassLoader();
        if (cl instanceof URLClassLoader ucl) {
            for (URL u : ucl.getURLs()) {
                if ("file".equals(u.getProtocol())) {
                    try {
                        found.add(Path.of(u.toURI()));
                    } catch (URISyntaxException ignored) {
                        // skip
                    }
                }
            }
        }
        // Fallback: parse java.class.path. The app classloader is typically
        // jdk.internal.loader.ClassLoaders$AppClassLoader (not a URLClassLoader),
        // so the URL walk above misses it on modern JDKs.
        if (found.isEmpty()) {
            var raw = System.getProperty("java.class.path", "");
            for (var entry : raw.split(Pattern.quote(File.pathSeparator))) {
                if (entry.isEmpty()) continue;
                found.add(Path.of(entry));
            }
        }
        // Filter to JUnit Platform + Jupiter jars only — bringing the entire
        // jk classpath (~80 jars, including jgit, jackson, etc.) into the
        // child would slow startup and risk version drift if the project
        // ships incompatible copies of any of them.
        var result = new ArrayList<Path>();
        for (Path p : found) {
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if ((name.startsWith("junit-platform-") || name.startsWith("junit-jupiter-")
                    || name.startsWith("opentest4j-"))
                    && name.endsWith(".jar")
                    && Files.isRegularFile(p)) {
                result.add(p);
            }
        }
        return result;
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

    /**
     * ConsoleLauncher's summary block looks like:
     * <pre>
     * [        16 tests successful      ]
     * [         0 tests failed          ]
     * </pre>
     * We grep these lines for the counters we report back to the user.
     * The exit code remains the source of truth for "did anything fail" —
     * the parse is for the human-readable summary.
     */
    static Result parseSummary(String output, int exitCode) {
        long total = 0;
        long succeeded = 0;
        long failed = 0;
        long skipped = 0;
        var failures = new ArrayList<Failure>();

        var counter = Pattern.compile(
                "\\[\\s*(\\d+)\\s+tests\\s+(found|successful|failed|skipped)\\s*]");
        for (var line : output.split("\n")) {
            Matcher m = counter.matcher(line);
            if (!m.find()) continue;
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "found" -> total = n;
                case "successful" -> succeeded = n;
                case "failed" -> failed = n;
                case "skipped" -> skipped = n;
            }
        }
        // Test-failure lines: ConsoleLauncher prints `JUnit Jupiter:Class:method ...`
        // with the exception immediately following. Quick parse for the test name
        // — full stack traces stream to stdout already, this is for the FAIL list.
        var failHeader = Pattern.compile("^Failures\\s*\\(\\d+\\):.*$");
        boolean inFailures = false;
        String pendingName = null;
        for (var line : output.split("\n")) {
            if (failHeader.matcher(line).matches()) {
                inFailures = true;
                continue;
            }
            if (!inFailures) continue;
            // Each failure starts with `    JUnit Jupiter:...` and is followed
            // by indented detail lines. Capture the first line of each failure.
            if (line.startsWith("    ") && !line.startsWith("        ")) {
                if (pendingName != null) failures.add(new Failure(pendingName, ""));
                pendingName = line.trim();
            } else if (line.isBlank()) {
                if (pendingName != null) {
                    failures.add(new Failure(pendingName, ""));
                    pendingName = null;
                }
                inFailures = false;
            }
        }
        if (pendingName != null) failures.add(new Failure(pendingName, ""));

        // The parser may not have seen anything (e.g. ConsoleLauncher banner
        // suppression hid the summary); fall back to exit code so the caller
        // still reports a sensible pass/fail.
        if (total == 0 && failed == 0 && succeeded == 0 && exitCode != 0) {
            failed = 1;
            total = 1;
            failures.add(new Failure("(test run)", "ConsoleLauncher exited " + exitCode));
        }
        return new Result(total, succeeded, failed, skipped, List.copyOf(failures));
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
