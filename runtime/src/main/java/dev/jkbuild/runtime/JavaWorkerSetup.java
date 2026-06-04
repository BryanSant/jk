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
 * Locates the {@code jk-java-compiler} worker jar — used for incremental
 * annotation processing (in-process javac + Filer provenance capture). The
 * worker needs nothing but itself + the project's JDK (no implementation
 * closure, unlike the Kotlin worker), so this is just jar location:
 * {@code -Djk.java.worker.jar} override → CAS-by-SHA (the SHA this jk build was
 * paired with, populated by {@code jk sync} / {@code :java-compiler:installLocalCas}).
 */
public final class JavaWorkerSetup {

    /** Override for the worker jar path (tests, dev). Takes precedence over the CAS lookup. */
    public static final String WORKER_JAR_PROPERTY = "jk.java.worker.jar";

    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-java-compiler-sha256.txt";

    private JavaWorkerSetup() {}

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
                "jk-java-compiler.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Until jk-java-compiler is published to Maven Central, side-load it:\n"
                + "    ./gradlew :java-compiler:installLocalCas   (in jk's own tree)\n"
                + "  or set -D" + WORKER_JAR_PROPERTY + "=<path to the worker jar>.");
    }

    private static String readExpectedHash() {
        try (InputStream in = JavaWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(WORKER_SHA_RESOURCE
                        + " missing from this jk build (writeJavaWorkerSha didn't run)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
