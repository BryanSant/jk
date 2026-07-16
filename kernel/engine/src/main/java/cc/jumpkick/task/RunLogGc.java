// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Delete {@code <cacheRoot>/runs/*.ndjson} entries older than {@link #DEFAULT_TTL} (7 days).
 * Invoked by {@code jk cache prune} alongside the other steps.
 *
 * <p>Run logs are best-effort diagnostic artefacts — the data is useful for "what happened in last
 * week's build?" but doesn't need to persist forever. A weekly cadence keeps the directory bounded
 * without requiring user attention.
 */
public final class RunLogGc {

    /** Standard retention window for run logs. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private RunLogGc() {}

    public record Report(int deleted, long freedBytes) {}

    /**
     * Walk {@code <cacheRoot>/runs/} and delete files older than {@code ttl}. Returns counts for the
     * prune summary line.
     */
    public static Report sweep(Path cacheRoot, Duration ttl, boolean dryRun) throws IOException {
        Path runsDir = cacheRoot.resolve("runs");
        if (!Files.isDirectory(runsDir)) return new Report(0, 0L);
        long cutoff = System.currentTimeMillis() - ttl.toMillis();

        List<Path> doomed = new ArrayList<>();
        long total = 0;
        try (Stream<Path> stream = Files.list(runsDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (mtime < cutoff) {
                    doomed.add(file);
                    total += Files.size(file);
                }
            }
        }
        if (!dryRun) {
            for (Path file : doomed) Files.deleteIfExists(file);
        }
        return new Report(doomed.size(), total);
    }
}
