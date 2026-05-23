// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runs a project's compiled tests via the JUnit Platform launcher.
 *
 * <p>v0.2 first iteration: in-process. We open a {@link URLClassLoader}
 * containing the project's test classpath, parent it to jk's own
 * classloader (so JUnit's launcher/engine classes are reachable), and
 * call {@link Launcher} with the test-classes directory as a classpath
 * root. JVM forking + parallel test workers per PRD §18.4 land later.
 */
public final class JUnitLauncher {

    public Result run(Path testClassesDir, List<Path> runtimeClasspath) {
        Objects.requireNonNull(testClassesDir, "testClassesDir");
        Objects.requireNonNull(runtimeClasspath, "runtimeClasspath");

        URLClassLoader loader = newLoader(testClassesDir, runtimeClasspath);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClasspathRoots(Set.of(testClassesDir)))
                    .build();
            try (var session = LauncherFactory.openSession()) {
                Launcher launcher = session.getLauncher();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);
                return toResult(listener.getSummary());
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            try {
                loader.close();
            } catch (IOException ignored) {
                // best-effort cleanup of a short-lived loader
            }
        }
    }

    private static URLClassLoader newLoader(Path testClassesDir, List<Path> runtimeClasspath) {
        List<URL> urls = new ArrayList<>(runtimeClasspath.size() + 1);
        try {
            urls.add(testClassesDir.toUri().toURL());
            for (Path p : runtimeClasspath) urls.add(p.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new UncheckedIOException(e);
        }
        return new URLClassLoader(urls.toArray(new URL[0]),
                JUnitLauncher.class.getClassLoader());
    }

    private static Result toResult(TestExecutionSummary summary) {
        List<Failure> failures = new ArrayList<>();
        for (TestExecutionSummary.Failure f : summary.getFailures()) {
            failures.add(new Failure(
                    f.getTestIdentifier().getDisplayName(),
                    f.getException() == null ? "" : f.getException().toString()));
        }
        return new Result(
                summary.getTestsFoundCount(),
                summary.getTestsSucceededCount(),
                summary.getTestsFailedCount(),
                summary.getTestsSkippedCount(),
                List.copyOf(failures));
    }

    public record Result(
            long total,
            long succeeded,
            long failed,
            long skipped,
            List<Failure> failures) {

        public boolean allPassed() { return failed == 0; }
    }

    public record Failure(String testName, String message) {}
}
