// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the {@code jk-git-runner} worker jar. Mirrors
 * {@link KotlinWorkerSetup}: system-property override for dev/tests,
 * then CAS lookup by the SHA-256 paired with this jk build.
 */
public final class GitWorkerSetup {

    public static final String WORKER_JAR_PROPERTY = "jk.git.worker.jar";
    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-git-runner-sha256.txt";

    private GitWorkerSetup() {}

    /** Locate the worker jar — system property override first, then CAS. */
    public static Path locateWorkerJar() {
        String prop = System.getProperty(WORKER_JAR_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            Path jar = Path.of(prop);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException(WORKER_JAR_PROPERTY + " is set to '" + prop
                    + "' but no file exists there.");
        }
        String expectedHash = readExpectedHash();
        // CAS root: the git cache is $JK_CACHE_DIR/git, so CAS is $JK_CACHE_DIR.
        // Use JkDirs rather than guessing from any path the caller holds.
        Path casRoot = dev.jkbuild.util.JkDirs.cache();
        dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(casRoot);
        Path target = cas.pathFor(expectedHash);
        if (Files.isRegularFile(target)) return target;
        throw new IllegalStateException(
                "jk-git-runner.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Side-load it:  ./gradlew :git-runner:installLocalCas\n"
                + "  or set -D" + WORKER_JAR_PROPERTY + "=<path>");
    }

    private static String readExpectedHash() {
        try (InputStream in = GitWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) throw new IllegalStateException(
                    WORKER_SHA_RESOURCE + " missing from this jk build");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
