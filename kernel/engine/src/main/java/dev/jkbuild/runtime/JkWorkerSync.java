// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.repo.RepoArtifactStore;
import dev.jkbuild.util.JkVersion;
import dev.jkbuild.worker.WorkerJar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures jk's own child-JVM worker jars ({@code jk-test-runner}, {@code jk-kotlin-compiler}, …)
 * are present in {@code repos/local/} so {@link WorkerJar#locate()} can find them by Maven
 * coordinate.
 *
 * <p>These aren't project dependencies — they're jk's tooling, pinned to jk's own version. Until
 * they're published to Maven Central, {@code jk sync} copies them from the local Maven repository
 * ({@code ~/.m2/repository}, populated by {@code ./gradlew publishToMavenLocal} in jk's tree) into
 * {@code <cache>/repos/local/} in the m2 layout that {@link RepoArtifactStore} understands.
 *
 * <p>Best-effort: a worker already in {@code repos/local/} or {@code repos/central/} is skipped,
 * and a worker absent from {@code ~/.m2} is reported but doesn't fail the sync.
 */
public final class JkWorkerSync {

    /** Group the worker artifacts publish under (see the worker modules' build.gradle.kts). */
    static final String GROUP = "dev.jkbuild";

    /** Per-worker progress callbacks. */
    public interface Observer {
        default void present(String artifact) {}

        default void fetched(String artifact) {}

        default void missing(String artifact, String detail) {}
    }

    public record Result(int present, int fetched, int missing) {}

    private JkWorkerSync() {}

    public static Result ensureInCas(Cas cas, Observer obs) throws IOException, InterruptedException {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path cacheRoot = cas.root();
        RepoArtifactStore localStore = new RepoArtifactStore(cacheRoot, "local");
        RepoArtifactStore centralStore = new RepoArtifactStore(cacheRoot, "central");
        int present = 0;
        int fetched = 0;
        int missing = 0;

        for (WorkerJar w : WorkerJar.values()) {
            String relPath = relativeM2Path(w.artifactId());

            // Already in local or central repos?
            if (localStore.locate(relPath).isPresent()
                    || centralStore.locate(relPath).isPresent()) {
                present++;
                obs.present(w.artifactId());
                continue;
            }

            // Try to copy from ~/.m2/repository into repos/local/
            Path m2Jar = m2.resolve(relPath.replace('/', java.io.File.separatorChar));
            if (!Files.isRegularFile(m2Jar)) {
                missing++;
                obs.missing(w.artifactId(), "not found in ~/.m2 or cache");
                continue;
            }

            try {
                byte[] jarBytes = Files.readAllBytes(m2Jar);
                String hex = dev.jkbuild.util.Hashing.sha256Hex(jarBytes);
                // Put into CAS first (hard-link source for materialize), then materialize into repos/local/
                Path casBlob = cas.put(jarBytes);
                localStore.materialize(relPath, casBlob, hex);
                fetched++;
                obs.fetched(w.artifactId());
            } catch (Exception e) {
                missing++;
                obs.missing(w.artifactId(), e.getMessage());
            }
        }
        return new Result(present, fetched, missing);
    }

    /** The m2-layout relative path for a worker artifact at the current jk version. */
    private static String relativeM2Path(String artifactId) {
        String version = JkVersion.VERSION;
        return "dev/jkbuild/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }
}
