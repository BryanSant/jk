// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The single registry of jk's child-JVM worker jars, and the one place that
 * locates one on disk.
 *
 * <p>Each worker is jk's own tooling — pinned to jk's version, not a project
 * dependency — and is addressed by the SHA-256 this build of jk was paired
 * with (emitted as a {@code /META-INF/<artifact>-sha256.txt} resource by the
 * worker module's Gradle wiring). Location order is uniform:
 * <ol>
 *   <li>the {@code -D<jarProperty>} override (tests / dev), then</li>
 *   <li>the local CAS, keyed by the expected SHA (populated by {@code jk sync}
 *       once the worker is published, or {@code ./gradlew <module>:installLocalCas}
 *       in jk's own tree).</li>
 * </ol>
 *
 * <p>This enum replaces the seven near-identical {@code *WorkerSetup} locator
 * classes and {@code JkWorkerSync}'s hand-maintained worker list — one source
 * of truth for the property name, the SHA resource, and the side-load hint.
 */
public enum WorkerJar {
    TEST_RUNNER("jk-test-runner", "jk.test.runner.jar", ":test-runner:installLocalCas"),
    KOTLIN_COMPILER("jk-kotlin-compiler", "jk.kotlin.worker.jar", ":kotlin-compiler:installLocalCas"),
    JAVA_COMPILER("jk-java-compiler", "jk.java.worker.jar", ":java-compiler:installLocalCas"),
    AUDITOR("jk-auditor", "jk.auditor.worker.jar", ":auditor:installLocalCas"),
    PUBLISHER("jk-publisher", "jk.publisher.worker.jar", ":publisher:installLocalCas"),
    IMAGE_BUILDER("jk-image-builder", "jk.image-builder.worker.jar", ":image-builder:installLocalCas"),
    COMPAT_BRIDGE("jk-compat-bridge", "jk.compat-bridge.worker.jar", ":compat-bridge:installLocalCas"),
    GIT_CLIENT("jk-git-client", "jk.git-client.worker.jar", ":git-client:installLocalCas"),
    FORMATTER("jk-formatter", "jk.formatter.worker.jar", ":formatter:installLocalCas");

    private final String artifactId;
    private final String jarProperty;
    private final String installTask;
    private final String shaResource;

    WorkerJar(String artifactId, String jarProperty, String installTask) {
        this.artifactId = artifactId;
        this.jarProperty = jarProperty;
        this.installTask = installTask;
        this.shaResource = "/META-INF/" + artifactId + "-sha256.txt";
    }

    /** Maven artifactId the worker publishes under (group is always {@code dev.jkbuild}). */
    public String artifactId() {
        return artifactId;
    }

    /** System property that overrides jar location (tests / dev). */
    public String jarProperty() {
        return jarProperty;
    }

    /** Classpath resource holding the expected SHA-256 this jk build was paired with. */
    public String shaResource() {
        return shaResource;
    }

    /**
     * The expected SHA-256 this jk build was paired with, or {@code null} when
     * the resource is absent (a jk build that didn't bundle this worker).
     */
    public String expectedShaOrNull() {
        try (InputStream in = WorkerJar.class.getResourceAsStream(shaResource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String expectedSha() {
        String sha = expectedShaOrNull();
        if (sha == null || sha.isBlank()) {
            throw new IllegalStateException(shaResource
                    + " missing from this jk build (the worker-sha resource wasn't generated)");
        }
        return sha;
    }

    /**
     * Locate the worker jar against {@code cas}: {@code -D<jarProperty>} override
     * first, then the CAS by expected SHA. Throws {@link IllegalStateException}
     * with side-load instructions if neither resolves.
     */
    public Path locate(Cas cas) {
        String override = System.getProperty(jarProperty);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException("-D" + jarProperty + " is set to '" + override
                    + "' but no file exists there.");
        }
        String expectedHash = expectedSha();
        Path target = cas.pathFor(expectedHash);
        if (Files.isRegularFile(target)) return target;
        throw new WorkerJarNotFoundException(artifactId, expectedHash, target, jarProperty);
    }

    /** Locate using the default jk CAS ({@code $JK_CACHE_DIR}). */
    public Path locate() {
        return locate(new Cas(JkDirs.cache()));
    }

    /** As {@link #locate(Cas)} but {@code null} (not throwing) when the worker can't be located. */
    public Path locateOrNull(Cas cas) {
        try {
            return locate(cas);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The worker whose {@code artifactId} (e.g. {@code jk-git-client}) matches, if any. */
    public static java.util.Optional<WorkerJar> byArtifactId(String artifactId) {
        for (WorkerJar w : values()) {
            if (w.artifactId.equals(artifactId)) return java.util.Optional.of(w);
        }
        return java.util.Optional.empty();
    }
}
