// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.kotlin.KotlinResolver;
import dev.jkbuild.repo.RepoGroup;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares everything a Kotlin compile needs to fork the {@code jk-kotlin-compiler}
 * worker: the worker JVM classpath (the worker jar + the resolved Build Tools API
 * implementation closure, version-matched to the project's Kotlin) and the
 * version-matched {@code kotlin-stdlib} for the compilation classpath.
 *
 * <p>Shared by every Kotlin compile entry point ({@code jk build}'s
 * {@code compile-kotlin}, {@code jk compile}, {@code jk run} scripts) so they
 * resolve and locate consistently.
 */
public final class KotlinWorkerSetup {

    /** Override for the {@code jk-kotlin-compiler} jar path (tests, dev). Takes precedence over the CAS lookup. */
    public static final String WORKER_JAR_PROPERTY = "jk.kotlin.worker.jar";

    /** Expected worker-jar SHA-256, emitted into runtime's resources at build time. */
    private static final String WORKER_SHA_RESOURCE = "/META-INF/jk-kotlin-compiler-sha256.txt";

    private KotlinWorkerSetup() {}

    /**
     * @param workerClasspath worker JVM {@code -cp}: worker jar + BTA closure
     * @param stdlib          the version-matched kotlin-stdlib for the compile classpath
     */
    public record Prepared(List<Path> workerClasspath, Path stdlib) {}

    /**
     * Resolve the closure + stdlib for {@code kotlinVersion} (null ⇒ jk's
     * default) against {@code repos}, and locate the worker jar.
     */
    public static Prepared prepare(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        String version = (kotlinVersion == null || kotlinVersion.isBlank())
                ? KotlinResolver.DEFAULT_VERSION : kotlinVersion;
        List<Path> closure = KotlinBtaResolver.resolveClasspath(repos, cas, version);
        Path stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, version);

        List<Path> workerClasspath = new ArrayList<>(closure.size() + 1);
        workerClasspath.add(locateWorkerJar(cas));
        workerClasspath.addAll(closure);
        return new Prepared(workerClasspath, stdlib);
    }

    /**
     * Locate the {@code jk-kotlin-compiler} worker jar, in order:
     * <ol>
     *   <li>the {@value #WORKER_JAR_PROPERTY} system property (tests / dev override);</li>
     *   <li>the local CAS, keyed by the SHA-256 this build of jk was paired with
     *       (populated by {@code jk sync} once the worker is published, or by
     *       {@code ./gradlew :kotlin-compiler:installLocalCas} in jk's own tree).</li>
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
                "jk-kotlin-compiler.jar is not in the CAS.\n"
                + "  expected sha256: " + expectedHash + "\n"
                + "  expected path:   " + target + "\n"
                + "  Until jk-kotlin-compiler is published to a primary repo, side-load it.");
    }

    private static String readExpectedHash() {
        try (InputStream in = KotlinWorkerSetup.class.getResourceAsStream(WORKER_SHA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(WORKER_SHA_RESOURCE
                        + " missing from this jk build (writeKotlinWorkerSha didn't run)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
