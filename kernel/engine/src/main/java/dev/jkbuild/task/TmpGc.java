// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Aggressively GC jk's scratch dir ({@code ~/.jk/tmp/}): delete <em>any</em>
 * file older than {@link #DEFAULT_TTL} (7 days). Invoked by {@code jk cache
 * prune} alongside the other phases.
 *
 * <p>The scratch dir holds transient, regenerable artefacts — e.g. {@code jk
 * import} reports. Nothing here is load-bearing, so a flat 7-day age cutoff
 * (no liveness/reachability check) keeps it bounded without user attention.
 */
public final class TmpGc {

    /** Standard retention window for scratch files. */
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private TmpGc() {}

    public record Report(int deleted, long freedBytes) {}

    /**
     * Recursively walk {@code tmpDir} and delete every regular file whose mtime
     * is older than {@code ttl}. Missing dir is a no-op. Returns counts for the
     * prune summary line.
     */
    public static Report sweep(Path tmpDir, Duration ttl, boolean dryRun) throws IOException {
        if (!Files.isDirectory(tmpDir)) return new Report(0, 0L);
        long cutoff = System.currentTimeMillis() - ttl.toMillis();

        List<Path> doomed = new ArrayList<>();
        long total = 0;
        try (Stream<Path> stream = Files.walk(tmpDir)) {
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
