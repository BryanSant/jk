// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Delete stale {@code .sha256} sidecar files under {@code <cacheRoot>/repos/m2local/} that are
 * older than {@link #DEFAULT_TTL} (90 days). Invoked by {@code jk cache prune} alongside the other
 * phases.
 *
 * <p>Sidecar files serve as completion markers for artifacts stored in the per-named-repo store:
 * their existence signals that the accompanying artifact is fully on disk. When an artifact is
 * evicted or replaced, the sidecar may be left behind as an orphan. A time-to-live sweep bounds
 * sidecar growth — any sidecar not refreshed in 90 days corresponds to an artifact that has either
 * been swept or is stale enough to re-fetch on the next build.
 *
 * <p>Only the {@code repos/m2local/} tree is swept here; named remote-repo stores ({@code
 * repos/<name>/}) have their sidecars managed in lock-step with their artifact files by
 * {@link dev.jkbuild.repo.RepoArtifactStore#removeShas}.
 *
 * <p>Sidecar files live in the standard m2 layout tree; after deleting expired sidecars this class
 * also removes any now-empty directories.
 */
public final class M2LocalSidecarGc {

    /** Standard retention window for m2local sidecar files. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private M2LocalSidecarGc() {}

    public record Report(int deleted, long freedBytes) {}

    /**
     * Walk {@code <cacheRoot>/repos/m2local/} and delete {@code .sha256} sidecar files older than
     * {@code ttl}. Cleans up empty directories afterwards. Returns counts for the prune summary line.
     */
    public static Report sweep(Path cacheRoot, Duration ttl, boolean dryRun) throws IOException {
        Path m2LocalDir = cacheRoot.resolve("repos").resolve("m2local");
        if (!Files.isDirectory(m2LocalDir)) return new Report(0, 0L);
        long cutoff = System.currentTimeMillis() - ttl.toMillis();

        int deleted = 0;
        long freedBytes = 0L;

        try (Stream<Path> stream = Files.walk(m2LocalDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (!file.getFileName().toString().endsWith(".sha256")) continue;
                if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    freedBytes += Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    deleted++;
                }
            }
        }

        // After deleting expired sidecars, remove any now-empty directories
        // (walk deepest-first so children are removed before parents).
        if (!dryRun && deleted > 0) {
            try (Stream<Path> dirs =
                    Files.walk(m2LocalDir).filter(Files::isDirectory).sorted(Comparator.reverseOrder())) {
                for (Path dir : (Iterable<Path>) dirs::iterator) {
                    if (dir.equals(m2LocalDir)) continue;
                    try (Stream<Path> contents = Files.list(dir)) {
                        if (contents.findFirst().isEmpty()) Files.deleteIfExists(dir);
                    }
                }
            }
        }

        return new Report(deleted, freedBytes);
    }
}
