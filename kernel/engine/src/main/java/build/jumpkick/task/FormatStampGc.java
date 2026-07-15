// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Delete per-file format stamp entries under {@code <cacheRoot>/format-stamps/} that are older
 * than {@link #DEFAULT_TTL} (90 days). Invoked by {@code jk cache prune} alongside the other
 * steps.
 *
 * <p>Format stamps are keyed by file-content SHA-256, so they become unreachable when a file's
 * content changes (the old stamp is never hit again) or when a file is deleted entirely. They also
 * become unreachable when the formatter config changes (style, version, or OpenRewrite version),
 * since the config hash is baked into the key. A time-to-live sweep is the simplest way to bound
 * growth — any stamp that hasn't been refreshed in 90 days is either orphaned or so infrequently
 * touched that losing it (at most one extra format pass to re-stamp) is acceptable.
 *
 * <p>Stamps live under a two-level CAS-style shard tree ({@code AB/CD/<rest>}); after deleting
 * expired entries this class also removes any now-empty shard directories.
 */
public final class FormatStampGc {

    /** Standard retention window for format stamps. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(90);

    private FormatStampGc() {}

    public record Report(int deleted, long freedBytes) {}

    /**
     * Walk {@code <cacheRoot>/format-stamps/} and delete stamp files older than {@code ttl}.
     * Cleans up empty shard directories afterwards. Returns counts for the prune summary line.
     */
    public static Report sweep(Path cacheRoot, Duration ttl, boolean dryRun) throws IOException {
        Path stampsDir = cacheRoot.resolve("format-stamps");
        if (!Files.isDirectory(stampsDir)) return new Report(0, 0L);
        long cutoff = System.currentTimeMillis() - ttl.toMillis();

        int deleted = 0;
        long freedBytes = 0L;

        try (Stream<Path> stream = Files.walk(stampsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    freedBytes += Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    deleted++;
                }
            }
        }

        // After deleting expired stamps, remove any now-empty shard directories
        // (walk deepest-first so children are removed before parents).
        if (!dryRun && deleted > 0) {
            try (Stream<Path> dirs =
                    Files.walk(stampsDir).filter(Files::isDirectory).sorted(Comparator.reverseOrder())) {
                for (Path dir : (Iterable<Path>) dirs::iterator) {
                    if (dir.equals(stampsDir)) continue;
                    try (Stream<Path> contents = Files.list(dir)) {
                        if (contents.findFirst().isEmpty()) Files.deleteIfExists(dir);
                    }
                }
            }
        }

        return new Report(deleted, freedBytes);
    }
}
