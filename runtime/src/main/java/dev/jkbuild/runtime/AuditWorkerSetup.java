// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the {@code jk-audit-runner} worker jar for forking by
 * {@code jk audit}. Mirrors {@link KotlinWorkerSetup}: checks a dev/test
 * system-property override first, then falls back to the CAS keyed by the
 * SHA-256 this build of jk was paired with.
 */
public final class AuditWorkerSetup {

    /** Override for the {@code jk-audit-runner} jar path (tests, dev). Takes precedence over the CAS lookup. */
    public static final String WORKER_JAR_PROPERTY = "jk.audit.worker.jar";

    /** Expected worker-jar SHA-256, emitted into runtime's resources at build time. */
    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-audit-runner-sha256.txt";

    private AuditWorkerSetup() {}

    /**
     * Locate the {@code jk-audit-runner} worker jar, in order:
     * <ol>
     *   <li>the {@value #WORKER_JAR_PROPERTY} system property (tests / dev override);</li>
     *   <li>the local CAS, keyed by the SHA-256 this build of jk was paired with
     *       (populated by {@code jk sync} once the worker is published, or by
     *       {@code ./gradlew :audit-runner:installLocalCas} in jk's own tree).</li>
     * </ol>
     * Throws with side-load instructions if neither is available.
     */
    public static Path locateWorkerJar(Cas cas) {
        String prop = System.getProperty(WORKER_JAR_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            Path jar = Path.of(prop);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException(WORKER_JAR_PROPERTY + " is set to '" + prop
                    + "' but no file exists there.");
        }
        String expectedHash = readExpectedHash();
        Path target = cas.pathFor(expectedHash);
        if (Files.isRegularFile(target)) {
            return target;
        }
        throw new IllegalStateException(
                "jk-audit-runner.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Until jk-audit-runner is published to Maven Central, side-load it:\n"
                + "    ./gradlew :audit-runner:installLocalCas   (in jk's own tree)\n"
                + "  or set -D" + WORKER_JAR_PROPERTY + "=<path to the worker jar>.");
    }

    private static String readExpectedHash() {
        try (InputStream in = AuditWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(WORKER_SHA_RESOURCE
                        + " missing from this jk build (writeAuditWorkerSha didn't run)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
