// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.repo.RepoArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mark-and-sweep over the CAS pool.
 *
 * <p>Given the live set produced by {@link CacheRoots}, walks the {@code sha256/AA/BB/<rest>} tree
 * and deletes every object that's both unreferenced and old enough to be sweep-eligible. Two age
 * guards keep the sweep safe under concurrency:
 *
 * <ul>
 *   <li><strong>Watermark:</strong> never touch a file whose mtime is later than the sweep's start
 *       time. Anything written during the sweep is by definition not in the live snapshot we took
 *       up front; the watermark prevents us deleting it just for being new.
 *   <li><strong>Min-age:</strong> never touch a file younger than {@link Sweep#MIN_AGE_FOR_SWEEP}.
 *       Closes the gap between "writer puts an object in the CAS" and "writer finishes stamping the
 *       root that references it" — the gap is normally milliseconds, but {@code jk sync} runs
 *       root-stamping after the parallel fetches resolve.
 * </ul>
 *
 * <p>Both guards are unconditional (not configurable). Dropping either trades reproducibility for
 * slightly more aggressive collection, and the GC isn't the bottleneck anyone is asking us to
 * optimise.
 */
public final class CasSweep {

    private CasSweep() {}

    public record Report(int deleted, long freedBytes, int kept) {}

    /**
     * Walk the CAS and delete objects not present in {@code liveRefs} (subject to the age guards
     * above). {@code dryRun = true} reports what would be deleted without touching the filesystem.
     */
    public static Report sweep(Cas cas, Set<String> liveRefs, boolean dryRun) throws IOException {
        long sweepStartMillis = System.currentTimeMillis();
        long minAgeMillis = Sweep.MIN_AGE_FOR_SWEEP.toMillis();

        Path shaRoot = cas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) {
            return new Report(0, 0L, 0);
        }

        int deleted = 0;
        long freedBytes = 0;
        int kept = 0;
        Set<String> deletedShas = new HashSet<>();
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                // Skip atomic-write tempfiles (covered by phase 1 of prune).
                String name = file.getFileName().toString();
                if (name.startsWith(".put-")) continue;

                var hexOpt = cas.hashFromPath(file);
                if (hexOpt.isEmpty()) {
                    // File in sha256/ that doesn't fit the layout — leave
                    // it alone, it isn't our garbage to collect.
                    continue;
                }
                String hex = hexOpt.get();

                if (liveRefs.contains(hex)) {
                    kept++;
                    continue;
                }

                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime > sweepStartMillis) continue; // concurrent write
                if (sweepStartMillis - mtime < minAgeMillis) continue; // grace period

                long size = Files.size(file);
                if (!dryRun) {
                    Files.deleteIfExists(file);
                }
                deleted++;
                freedBytes += size;
                deletedShas.add(hex);
            }
        }
        // Keep every named repo store (repos/<name>/) in lock-step with the CAS.
        RepoArtifactStore.removeShasFromAll(cas.root(), deletedShas, dryRun);
        return new Report(deleted, freedBytes, kept);
    }
}
