// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.lock.Lockfile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * jk now injects the latest-stable JUnit Platform into every project's TEST scope (see {@code
 * LockOrchestrator.DEFAULT_TEST_DEPS}), so any test that runs a real {@code jk lock}/{@code jk
 * build} against a mock Maven server must offer those coords. This seeds them with minimal,
 * dependency-free POMs + stub jars so resolution succeeds without pulling the real JUnit closure.
 */
final class DefaultTestDepsFixture {

    /** The version the mock repo advertises as JUnit's latest stable. */
    static final String JUNIT_VERSION = "6.1.0";

    /** Coords jk injects into every TEST scope — present in every resolved lock. */
    static final String JUPITER = "org.junit.jupiter:junit-jupiter";

    static final String LAUNCHER = "org.junit.platform:junit-platform-launcher";

    private DefaultTestDepsFixture() {}

    /** Artifact names in {@code lock}, minus the always-injected JUnit defaults. */
    static List<String> projectCoords(Lockfile lock) {
        return lock.artifacts().stream()
                .map(Lockfile.Artifact::name)
                .filter(n -> !n.equals(JUPITER) && !n.equals(LAUNCHER))
                .toList();
    }

    /** Register junit-jupiter + junit-platform-launcher into a test's {@code served} map. */
    static void seed(Map<String, byte[]> served) {
        seedArtifact(served, "org.junit.jupiter", "junit-jupiter", JUNIT_VERSION);
        seedArtifact(served, "org.junit.platform", "junit-platform-launcher", JUNIT_VERSION);
    }

    private static void seedArtifact(Map<String, byte[]> served, String group, String artifact, String version) {
        String base = "/" + group.replace('.', '/') + "/" + artifact;
        put(
                served,
                base + "/maven-metadata.xml",
                "<metadata><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><versioning><versions><version>"
                        + version
                        + "</version></versions></versioning></metadata>");
        String dir = base + "/" + version + "/" + artifact + "-" + version;
        put(
                served,
                dir + ".pom",
                "<project><groupId>"
                        + group
                        + "</groupId><artifactId>"
                        + artifact
                        + "</artifactId><version>"
                        + version
                        + "</version></project>");
        served.put(dir + ".jar", (artifact + "-stub").getBytes(StandardCharsets.UTF_8));
    }

    private static void put(Map<String, byte[]> served, String path, String body) {
        served.put(path, body.getBytes(StandardCharsets.UTF_8));
    }
}
