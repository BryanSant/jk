// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the {@code jk-compat-runner} worker jar. */
public final class CompatWorkerSetup {

    public static final String WORKER_JAR_PROPERTY = "jk.compat.worker.jar";
    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-compat-runner-sha256.txt";

    private CompatWorkerSetup() {}

    public static Path locateWorkerJar(Cas cas) {
        String prop = System.getProperty(WORKER_JAR_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            Path jar = Path.of(prop);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException(WORKER_JAR_PROPERTY + " set to '" + prop
                    + "' but no file exists there.");
        }
        String expectedHash = readExpectedHash();
        Path target = cas.pathFor(expectedHash);
        if (Files.isRegularFile(target)) return target;
        throw new IllegalStateException(
                "jk-compat-runner.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Side-load it:  ./gradlew :compat-runner:installLocalCas\n"
                + "  or set -D" + WORKER_JAR_PROPERTY + "=<path>");
    }

    private static String readExpectedHash() {
        try (InputStream in = CompatWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) throw new IllegalStateException(
                    WORKER_SHA_RESOURCE + " missing from this jk build");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
