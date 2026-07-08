// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.task.CacheGc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * The shared cache-maintenance goals behind {@code jk cache prune}, {@code jk cache purge}, and
 * {@code jk clean --cache} — hoisted out of the CLI so the resident engine can host them as
 * idle-boundary jobs (Wave 4 of {@code docs/architecture/slim-client.md}: these mutate caches the
 * engine's pipelines read concurrently, so the engine runs them only when no pipeline is in flight)
 * while the commands' test-only in-process path builds the exact same goals.
 */
public final class CacheGoals {

    private CacheGoals() {}

    /** Files removed (or, on a dry run, that would be). {@code gc}: purged CAS blobs. */
    public static final GoalKey<Long> FILES = GoalKey.of("cache-files", Long.class);

    /** Bytes freed (or reclaimable, on a dry run). */
    public static final GoalKey<Long> BYTES = GoalKey.of("cache-bytes", Long.class);

    /** Reachable CAS objects the LRU evictor removed to fit {@code --max-size} (prune only). */
    public static final GoalKey<Long> REACHABLE_EVICTED = GoalKey.of("cache-reachable-evicted", Long.class);

    /** Repo-mirror links removed ({@code gc} only). */
    public static final GoalKey<Long> REPO_LINKS = GoalKey.of("cache-repo-links", Long.class);

    /**
     * Build the prune goal for the cache at {@code root}: expire stale action-cache entries and
     * leftover temp files, GC run logs / format stamps / phase timings, then optionally
     * mark-and-sweep the CAS ({@code sweep}) and LRU-evict down to {@code maxSize}.
     *
     * @param includeJkTmp also sweep {@code ~/.jk/tmp} — only when the default cache dir is in use
     *     (the command passes {@code --cache-dir == null}), matching the historical behavior
     */
    public static Goal pruneGoal(
            Path root, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {
        Phase prunePhase = Phase.builder("prune")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("Pruning cache…");
                    long cutoffMillis = System.currentTimeMillis() - (long) olderThanDays * 24L * 60L * 60L * 1000L;
                    long totalFiles = 0;
                    long totalBytes = 0;

                    Path shaDir = root.resolve("sha256");
                    if (Files.isDirectory(shaDir)) {
                        for (Path file : tempFiles(shaDir)) {
                            long sz = Files.size(file);
                            if (!dryRun) Files.deleteIfExists(file);
                            totalFiles++;
                            totalBytes += sz;
                        }
                    }
                    Path actionsDir = root.resolve("actions");
                    if (Files.isDirectory(actionsDir)) {
                        Path keysDir = actionsDir.resolve("keys");
                        if (Files.isDirectory(keysDir)) {
                            for (Path file : olderThan(keysDir, cutoffMillis)) {
                                long sz = Files.size(file);
                                if (!dryRun) Files.deleteIfExists(file);
                                totalFiles++;
                                totalBytes += sz;
                            }
                        }
                    }
                    var runLogReport = dev.jkbuild.task.RunLogGc.sweep(root, dev.jkbuild.task.RunLogGc.DEFAULT_TTL, dryRun);
                    totalFiles += runLogReport.deleted();
                    totalBytes += runLogReport.freedBytes();

                    var formatStampReport =
                            dev.jkbuild.task.FormatStampGc.sweep(root, dev.jkbuild.task.FormatStampGc.DEFAULT_TTL, dryRun);
                    totalFiles += formatStampReport.deleted();
                    totalBytes += formatStampReport.freedBytes();

                    var timingsReport = PhaseTimings.prune(
                            root,
                            PhaseTimings.Limits.resolve(dev.jkbuild.util.JkDirs.userConfigFile(), System::getenv),
                            System.currentTimeMillis(),
                            dryRun);
                    totalFiles += timingsReport.evictedByAge() + timingsReport.evictedBySize();

                    if (includeJkTmp) {
                        var tmpReport = dev.jkbuild.task.TmpGc.sweep(
                                dev.jkbuild.util.JkDirs.tmp(), dev.jkbuild.task.TmpGc.DEFAULT_TTL, dryRun);
                        totalFiles += tmpReport.deleted();
                        totalBytes += tmpReport.freedBytes();
                    }

                    boolean doSweep = sweep || maxSize != null;
                    long budgetBytes = maxSize != null ? dev.jkbuild.task.LruEvictor.parseSize(maxSize) : -1L;
                    if (doSweep) {
                        dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(root);
                        Path toolsDir = root.resolve("tools");
                        Path actionsDir2 = root.resolve("actions");
                        var liveRefs = dev.jkbuild.task.CacheRoots.collect(cas, actionsDir2, toolsDir);
                        var sweepReport = dev.jkbuild.task.CasSweep.sweep(cas, liveRefs, dryRun);
                        totalFiles += sweepReport.deleted();
                        totalBytes += sweepReport.freedBytes();
                        if (budgetBytes > 0) {
                            var ledger = dev.jkbuild.task.AccessLedger.atDefaultPath();
                            var evictReport =
                                    dev.jkbuild.task.LruEvictor.evictDownTo(cas, budgetBytes, liveRefs, ledger, dryRun);
                            totalFiles += evictReport.deleted();
                            totalBytes += evictReport.freedBytes();
                            ctx.put(REACHABLE_EVICTED, (long) evictReport.reachableEvicted());
                            if (!dryRun) {
                                try {
                                    ledger.compactIfLarge();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }

                    ctx.put(FILES, totalFiles);
                    ctx.put(BYTES, totalBytes);
                    ctx.progress(1);
                })
                .build();
        return Goal.builder("cache-prune").addPhase(prunePhase).build();
    }

    /** Build the purge goal: delete everything under {@code root} (the root dir itself survives). */
    public static Goal purgeGoal(Path root) {
        Phase purgePhase = Phase.builder("purge")
                .execute(ctx -> {
                    ctx.label("Purging cache…");
                    deleteContents(root);
                })
                .build();
        return Goal.builder("cache-purge").addPhase(purgePhase).build();
    }

    /** Build the GC goal ({@code jk clean --cache}): purge CAS blobs idle 90+ days via {@link CacheGc}. */
    public static Goal gcGoal(Path root) {
        Phase gcPhase = Phase.builder("gc")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("Collecting cache…");
                    CacheGc.Report report = CacheGc.run(root, false);
                    ctx.put(FILES, (long) report.purgedBlobs());
                    ctx.put(BYTES, report.freedBytes());
                    ctx.put(REPO_LINKS, (long) report.repoLinksRemoved());
                    ctx.progress(1);
                })
                .build();
        return Goal.builder("cache-gc").addPhase(gcPhase).build();
    }

    /** Recursively delete everything under {@code root}, keeping {@code root} itself. */
    public static void deleteContents(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static List<Path> olderThan(Path dir, long cutoffMillis) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoffMillis;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
    }

    private static List<Path> tempFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(".put-"))
                    .toList();
        }
    }
}
