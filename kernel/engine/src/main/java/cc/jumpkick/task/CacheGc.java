// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The cache garbage collector run by {@code jk clean --cache}. A gentler pass than {@code jk cache
 * prune --sweep}: it never touches still-reachable blobs and only reaps unreferenced ones that
 * haven't been used in a long time.
 *
 * <p>Steps:
 *
 * <ol>
 *   <li><strong>Mark.</strong> Collect every sha reachable from the on-disk roots via {@link
 *       CacheRoots} — those are always kept.
 *   <li><strong>Read the access log.</strong> A sha's age is "now minus its latest recorded access"
 *       (falling back to file mtime when the log has never seen it).
 *   <li><strong>Sweep.</strong> Delete unmarked blobs older than {@link #MAX_AGE} from the {@code
 *       sha256/} pool, and the matching hard-links from the {@code repo/} mirror — leaving one
 *       behind would keep the inode's bytes on disk.
 *   <li><strong>Rewrite the access log.</strong> Sum each sha's counts, dedupe to the latest entry,
 *       drop entries for purged shas, write back.
 * </ol>
 */
public final class CacheGc {

    /** Unreferenced blobs idle longer than this are collectible. */
    public static final Duration MAX_AGE = Duration.ofDays(90);

    private CacheGc() {}

    public record Report(int purgedBlobs, long freedBytes, int repoLinksRemoved) {}

    public static Report run(Path cacheRoot, boolean dryRun) throws IOException {
        Cas cas = new Cas(cacheRoot);
        Path shaRoot = cacheRoot.resolve("sha256");
        Set<String> reachable = CacheRoots.collect(cas, cacheRoot.resolve("actions"), cacheRoot.resolve("tools"));

        Path logFile = cacheRoot.resolve(AccessLedger.FILE_NAME);
        AccessLedger ledger = new AccessLedger(logFile);
        Map<String, AccessLedger.Entry> access = ledger.entries();

        long now = System.currentTimeMillis();
        long maxAgeMillis = MAX_AGE.toMillis();

        Set<String> purged = new HashSet<>();
        long freed = 0;
        if (Files.isDirectory(shaRoot)) {
            try (Stream<Path> stream = Files.walk(shaRoot)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    if (file.getFileName().toString().startsWith(".put-")) continue;
                    var hexOpt = cas.hashFromPath(file);
                    if (hexOpt.isEmpty()) continue;
                    String hex = hexOpt.get();
                    if (reachable.contains(hex)) continue; // marked — always kept

                    AccessLedger.Entry e = access.get(hex);
                    long last = e != null
                            ? e.latestMillis()
                            : Files.getLastModifiedTime(file).toMillis();
                    if (now - last < maxAgeMillis) continue; // still warm

                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    purged.add(hex);
                    freed += size;
                }
            }
        }

        int repoLinks = cc.jumpkick.repo.RepoArtifactStore.removeShasFromAll(cacheRoot, purged, dryRun);

        // Compact the access log: sum each sha's counts, dedupe to the latest
        // entry, and drop entries for anything we just purged.
        if (!dryRun && Files.exists(logFile)) {
            ledger.rewriteDropping(purged);
        }
        return new Report(purged.size(), freed, repoLinks);
    }
}
