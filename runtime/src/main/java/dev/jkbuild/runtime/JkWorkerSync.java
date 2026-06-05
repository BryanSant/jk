// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.Journal;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.util.JkVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Pulls jk's own child-JVM worker jars ({@code jk-test-runner},
 * {@code jk-kotlin-compiler}) into the local CAS so {@code jk test} / Kotlin
 * builds can locate them by SHA without a manual {@code installLocalCas}.
 *
 * <p>These aren't project dependencies — they're jk's tooling, pinned to jk's
 * own version. Until they're published to Maven Central, {@code jk sync} fetches
 * them from the local Maven repository ({@code ~/.m2/repository}, populated by
 * {@code ./gradlew publishToMavenLocal} in jk's tree). The jar lands in the CAS
 * keyed by its content SHA, which must match the expected-SHA resource this jk
 * build was paired with (see {@code writeRunnerSha} / {@code writeKotlinWorkerSha}).
 *
 * <p>Best-effort: a worker already in the CAS is left alone, and a worker absent
 * from {@code ~/.m2} is reported but doesn't fail the sync (the project may not
 * use Kotlin or tests).
 */
public final class JkWorkerSync {

    /** Group the worker artifacts publish under (see the worker modules' build.gradle.kts). */
    static final String GROUP = "dev.jkbuild";

    private record Worker(String artifactId, String shaResource) {}

    private static final List<Worker> WORKERS = List.of(
            new Worker("jk-test-runner", "/META-INF/jk-test-runner-sha256.txt"),
            new Worker("jk-kotlin-compiler", "/META-INF/jk-kotlin-compiler-sha256.txt"),
            new Worker("jk-java-compiler", "/META-INF/jk-java-compiler-sha256.txt"),
            new Worker("jk-audit-runner", "/META-INF/jk-audit-runner-sha256.txt"));

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
        RepoGroup mavenLocal = null;   // built lazily, only if something's missing
        int present = 0;
        int fetched = 0;
        int missing = 0;

        for (Worker w : WORKERS) {
            String expectedSha = readSha(w.shaResource());
            if (expectedSha == null || expectedSha.isBlank()) {
                continue;   // this jk build didn't bundle the resource — nothing to pin to
            }
            if (cas.contains(expectedSha)) {
                present++;
                obs.present(w.artifactId());
                continue;
            }
            if (mavenLocal == null) mavenLocal = mavenLocal(m2, cas);
            Coordinate coord = Coordinate.of(GROUP, w.artifactId(), JkVersion.VERSION);
            String got;
            try {
                var hit = mavenLocal.tryFetchArtifact(coord);
                if (hit.isEmpty()) {
                    missing++;
                    obs.missing(w.artifactId(), "not found in " + m2
                            + " — run `./gradlew publishToMavenLocal` in jk's tree");
                    continue;
                }
                got = hit.get().fetched().sha256();
            } catch (IOException e) {
                missing++;
                obs.missing(w.artifactId(), e.getMessage());
                continue;
            }
            if (!got.equals(expectedSha)) {
                // The published jar is a different build than this jk — it landed
                // at the wrong CAS key, so the expected one still isn't satisfied.
                missing++;
                obs.missing(w.artifactId(), "published sha " + shortSha(got)
                        + " != expected " + shortSha(expectedSha)
                        + " — rebuild jk and re-run publishToMavenLocal");
                continue;
            }
            fetched++;
            obs.fetched(w.artifactId());
        }
        return new Result(present, fetched, missing);
    }

    private static RepoGroup mavenLocal(Path m2, Cas cas) {
        URI base = m2.toUri();   // file:///<home>/.m2/repository/
        Journal journal = new Journal(cas.root());
        return RepoGroup.of(new MavenRepo("mavenLocal", base, new Http(), cas, journal));
    }

    private static String readSha(String resource) throws IOException {
        try (InputStream in = JkWorkerSync.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String shortSha(String sha) {
        return sha.length() >= 8 ? sha.substring(0, 8) : sha;
    }
}
