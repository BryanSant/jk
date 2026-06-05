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
 * Locates the {@code jk-publish-runner} worker jar. Mirrors
 * {@link KotlinWorkerSetup}: system-property override for dev/tests,
 * then CAS lookup by the SHA-256 paired with this jk build.
 */
public final class PublishWorkerSetup {

    public static final String WORKER_JAR_PROPERTY = "jk.publish.worker.jar";
    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-publish-runner-sha256.txt";

    private PublishWorkerSetup() {}

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
                "jk-publish-runner.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Until jk-publish-runner is published to Maven Central, side-load it:\n"
                + "    ./gradlew :publish-runner:installLocalCas   (in jk's own tree)\n"
                + "  or set -D" + WORKER_JAR_PROPERTY + "=<path to the worker jar>.");
    }

    private static String readExpectedHash() {
        try (InputStream in = PublishWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(WORKER_SHA_RESOURCE
                        + " missing from this jk build (writePublishWorkerSha didn't run)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
