// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The cache-directory breakdown {@code GET /api/cache} reports — the same sections {@code jk cache
 * info} renders (CAS blobs, action cache, worker jars, run logs, format stamps), so the dashboard
 * and the CLI can never drift apart. {@code maxBytes} is the configured LRU ceiling ({@code [cache]
 * max-size-gb}, default 20 GiB) so the utilization meter always has a denominator;
 * {@code lastPrunedMillis} is {@code 0} when the cache has never been pruned.
 */
public record CacheSnapshot(
        long casCount,
        long casBytes,
        long actionsCount,
        long actionsBytes,
        long workerJarsCount,
        long workerJarsBytes,
        long runLogsCount,
        long runLogsBytes,
        long formatStampsCount,
        long formatStampsBytes,
        long maxBytes,
        long lastPrunedMillis) {

    public long totalCount() {
        return casCount + actionsCount + workerJarsCount + runLogsCount + formatStampsCount;
    }

    public long totalBytes() {
        return casBytes + actionsBytes + workerJarsBytes + runLogsBytes + formatStampsBytes;
    }

    /**
     * Walk {@code cacheRoot}'s sections and snapshot their sizes — the identical dirs and
     * recursive file-count/byte-sum semantics as {@code jk cache info}. IO-shaped (a full walk of
     * the CAS), so callers invoke it per request, never on a hot path. Best-effort: an unreadable
     * section counts as empty.
     */
    public static CacheSnapshot capture(Path cacheRoot) {
        long[] cas = statsOf(cacheRoot.resolve("sha256"));
        long[] actions = statsOf(cacheRoot.resolve("actions"));
        long[] repos = statsOf(cacheRoot.resolve("repos"));
        long[] runs = statsOf(cacheRoot.resolve("runs"));
        long[] stamps = statsOf(cacheRoot.resolve("format-stamps"));
        long maxBytes = configuredMaxBytes();
        long lastPruned = readLastPrunedMillis(cacheRoot);
        return new CacheSnapshot(
                cas[0],
                cas[1],
                actions[0],
                actions[1],
                repos[0],
                repos[1],
                runs[0],
                runs[1],
                stamps[0],
                stamps[1],
                maxBytes,
                lastPruned);
    }

    /** {files, bytes} of every regular file under {@code dir}; missing/unreadable → zeros. */
    private static long[] statsOf(Path dir) {
        long files = 0, bytes = 0;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    files++;
                    bytes += Files.size(p);
                }
            } catch (Exception ignored) {
                // best-effort — a vanished file mid-walk must not fail the endpoint
            }
        }
        return new long[] {files, bytes};
    }

    /** The configured LRU ceiling, or the documented 20 GiB default when unset/unreadable. */
    private static long configuredMaxBytes() {
        int gb = 20;
        try {
            gb = cc.jumpkick.config.JkCacheConfig.resolve().maxSizeGb().orElse(20);
        } catch (Exception ignored) {
            // keep the default
        }
        return gb * 1024L * 1024L * 1024L;
    }

    /** The prune scheduler's stamp, or {@code 0} when the cache has never been pruned. */
    private static long readLastPrunedMillis(Path cacheRoot) {
        Path stamp = cacheRoot.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return 0;
        try {
            return Long.parseLong(Files.readString(stamp).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
