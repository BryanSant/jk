// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code jk cache} — manage the on-disk cache at {@code $JK_CACHE_DIR}
 * (XDG default: {@code ~/.cache/jk/}). Mirrors {@code uv cache} in shape:
 * {@code dir} / {@code info} / {@code prune} / {@code clean}.
 *
 * <p>Scope: only the cache directory. Installed JDKs ({@code $JK_JDKS_DIR}),
 * user config ({@code $JK_CONFIG_DIR}), installed launchers ({@code
 * $JK_BIN_DIR}) and tool state ({@code $JK_STATE_DIR}) are untouched — each
 * has its own lifecycle verb.
 */
@Command(name = "cache",
        description = "Manage the jk download / action cache",
        subcommands = {
                CacheCommand.Dir.class,
                CacheCommand.Info.class,
                CacheCommand.Prune.class,
                CacheCommand.Clean.class,
        })
public final class CacheCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }

    @Command(name = "dir", description = "Print the cache directory path")
    public static final class Dir implements Callable<Integer> {

        @Option(names = "--cache-dir", hidden = true,
                description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
        Path cacheDir;

        @Override
        public Integer call() {
            System.out.println(resolveCacheRoot(cacheDir));
            return 0;
        }
    }

    @Command(name = "info", description = "Show cache size and contents")
    public static final class Info implements Callable<Integer> {

        @Option(names = "--cache-dir", hidden = true,
                description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
        Path cacheDir;

        @Override
        public Integer call() throws IOException {
            Path root = resolveCacheRoot(cacheDir);
            System.out.println("Cache directory: " + root);
            if (!Files.isDirectory(root)) {
                System.out.println("  (not yet created)");
                return 0;
            }
            Stats sha = statsOf(root.resolve("sha256"));
            Stats actions = statsOf(root.resolve("actions"));
            System.out.println();
            System.out.printf("  CAS blobs:     %s files, %s%n",
                    fmtCount(sha.files), fmtBytes(sha.bytes));
            System.out.printf("  Action cache:  %s files, %s%n",
                    fmtCount(actions.files), fmtBytes(actions.bytes));
            System.out.printf("  Total:         %s files, %s%n",
                    fmtCount(sha.files + actions.files), fmtBytes(sha.bytes + actions.bytes));
            return 0;
        }
    }

    @Command(name = "prune",
            description = "Remove stale action-cache entries and leftover temp files")
    public static final class Prune implements Callable<Integer> {

        @Option(names = "--cache-dir", hidden = true,
                description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
        Path cacheDir;

        @Option(names = "--older-than",
                description = "Prune action-cache entries with mtime older than N days. Default: 30.",
                paramLabel = "<days>")
        int olderThanDays = 30;

        @Option(names = "--dry-run",
                description = "Print what would be removed; touch nothing.")
        boolean dryRun;

        @Option(names = "--sweep",
                description = "After expiring stale records, mark-and-sweep "
                        + "unreferenced objects out of the CAS pool. Off by default.")
        boolean sweep;

        @Option(names = "--max-size",
                paramLabel = "<size>",
                description = "After sweep, evict oldest-accessed objects until the "
                        + "CAS pool is under <size> (e.g. 20G, 500M). Forces --sweep.")
        String maxSize;

        @Option(names = "--background", hidden = true,
                description = "Internal: opportunistic prune. Acquires a flock, "
                        + "exits silently if another prune is running, writes a "
                        + ".last-pruned stamp on success.")
        boolean background;

        @Override
        public Integer call() throws IOException {
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                System.out.println("Nothing to prune — " + root + " does not exist.");
                return 0;
            }

            // Background prune: acquire flock first; if another prune is
            // running, exit silently. Redirect output to a sidecar log
            // instead of stdout/stderr.
            java.nio.channels.FileChannel lockChan = null;
            java.nio.channels.FileLock lock = null;
            java.io.PrintStream originalOut = null;
            java.io.PrintStream originalErr = null;
            if (background) {
                Files.createDirectories(root);
                Path lockFile = root.resolve(".prune.lock");
                lockChan = java.nio.channels.FileChannel.open(lockFile,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE);
                lock = lockChan.tryLock();
                if (lock == null) {
                    lockChan.close();
                    return 0; // another prune is running; not an error.
                }
                Path logFile = root.resolve(".prune-log");
                var logStream = new java.io.PrintStream(java.nio.file.Files.newOutputStream(
                        logFile, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
                originalOut = System.out;
                originalErr = System.err;
                System.setOut(logStream);
                System.setErr(logStream);
            }
            try {
            long cutoffMillis = System.currentTimeMillis()
                    - (long) olderThanDays * 24L * 60L * 60L * 1000L;

            int recordsExpired = 0;
            long recordsBytes = 0;
            int tempsCleared = 0;
            long tempsBytes = 0;

            // Phase 1: atomic-write tempfiles left behind by interrupted Cas.put.
            Path shaDir = root.resolve("sha256");
            if (Files.isDirectory(shaDir)) {
                for (Path file : tempFiles(shaDir)) {
                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    tempsCleared++;
                    tempsBytes += size;
                }
            }

            // Phase 2: TTL action records + sync manifests. Run BEFORE the
            // sweep so freshly-orphaned manifests' refs get collected in
            // the same prune cycle.
            Path actionsDir = root.resolve("actions");
            if (Files.isDirectory(actionsDir)) {
                for (Path file : olderThan(actionsDir, cutoffMillis)) {
                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    recordsExpired++;
                    recordsBytes += size;
                }
            }

            // --max-size implies --sweep — there's no scenario where you
            // want LRU eviction without first dropping the GC-collectible
            // floor.
            boolean doSweep = sweep || maxSize != null;
            long budgetBytes = maxSize != null
                    ? dev.jkbuild.task.LruEvictor.parseSize(maxSize)
                    : -1L;

            // Phase 3: mark-and-sweep CAS (opt-in via --sweep / --max-size).
            int sweptCount = 0;
            long sweptBytes = 0;
            int sweptKept = 0;
            int evictedCount = 0;
            long evictedBytes = 0;
            int evictedReachable = 0;
            long finalSize = -1L;
            if (doSweep) {
                dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(root);
                Path toolsDir = root.resolve("tools");
                var liveRefs = dev.jkbuild.task.CacheRoots.collect(cas, actionsDir, toolsDir);
                var sweepReport = dev.jkbuild.task.CasSweep.sweep(cas, liveRefs, dryRun);
                sweptCount = sweepReport.deleted();
                sweptBytes = sweepReport.freedBytes();
                sweptKept = sweepReport.kept();

                // Phase 4: LRU eviction (only when --max-size set).
                if (budgetBytes > 0) {
                    var ledger = new dev.jkbuild.task.AccessLedger(root);
                    var evictReport = dev.jkbuild.task.LruEvictor.evictDownTo(
                            cas, budgetBytes, liveRefs, ledger, dryRun);
                    evictedCount = evictReport.deleted();
                    evictedBytes = evictReport.freedBytes();
                    evictedReachable = evictReport.reachableEvicted();
                    finalSize = evictReport.finalSize();
                    if (!dryRun) {
                        try {
                            ledger.compactIfLarge();
                        } catch (IOException ignored) {
                            // Compaction is opportunistic; failures don't
                            // break the prune.
                        }
                    }
                }
            }

            String verb = dryRun ? "Would prune" : "Pruned";
            System.out.printf("%s: records expired %s (%s), temps %s (%s)",
                    verb,
                    fmtCount(recordsExpired), fmtBytes(recordsBytes),
                    fmtCount(tempsCleared), fmtBytes(tempsBytes));
            if (doSweep) {
                System.out.printf(", swept %s (%s); kept %s",
                        fmtCount(sweptCount), fmtBytes(sweptBytes), fmtCount(sweptKept));
            }
            if (budgetBytes > 0) {
                System.out.printf("; evicted %s (%s) to fit %s",
                        fmtCount(evictedCount), fmtBytes(evictedBytes),
                        fmtBytes(budgetBytes));
            }
            System.out.println();
            if (evictedReachable > 0) {
                System.err.println("Warning: evicted " + evictedReachable
                        + " reachable objects to fit the budget — consider raising --max-size.");
            }
            return 0;
            } finally {
                if (background && !dryRun) {
                    // Stamp the successful prune so the scheduler can
                    // honor the interval.
                    try {
                        Files.writeString(root.resolve(
                                dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE),
                                Long.toString(System.currentTimeMillis()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IOException ignored) {
                        // Worst case: next opportunistic prune fires
                        // again sooner than expected.
                    }
                }
                if (originalOut != null) System.setOut(originalOut);
                if (originalErr != null) System.setErr(originalErr);
                if (lock != null) {
                    try { lock.release(); } catch (IOException ignored) {}
                }
                if (lockChan != null) {
                    try { lockChan.close(); } catch (IOException ignored) {}
                }
            }
        }

        private static List<Path> olderThan(Path dir, long cutoffMillis) throws IOException {
            try (var stream = Files.walk(dir)) {
                return stream
                        .filter(Files::isRegularFile)
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
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(".put-"))
                        .toList();
            }
        }
    }

    @Command(name = "clean", description = "Delete everything in the cache")
    public static final class Clean implements Callable<Integer> {

        @Option(names = "--cache-dir", hidden = true,
                description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
        Path cacheDir;

        @Option(names = "--dry-run",
                description = "Print what would be removed; touch nothing.")
        boolean dryRun;

        @Override
        public Integer call() throws IOException {
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                System.out.println(root + " does not exist; nothing to clean.");
                return 0;
            }
            Stats stats = statsOf(root);
            String verb = dryRun ? "Would remove" : "Removed";
            if (!dryRun) {
                deleteContents(root);
            }
            System.out.printf("%s %s files (%s) from %s%n",
                    verb, fmtCount(stats.files), fmtBytes(stats.bytes), root);
            return 0;
        }
    }

    // --- shared helpers ----------------------------------------------------

    static Path resolveCacheRoot(Path override) {
        return override != null ? override : JkDirs.cache();
    }

    /** Aggregate file count + total byte size under {@code dir}. */
    record Stats(long files, long bytes) {}

    static Stats statsOf(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new Stats(0, 0);
        long files = 0;
        long bytes = 0;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                files++;
                bytes += Files.size(p);
            }
        }
        return new Stats(files, bytes);
    }

    /** Wipe everything under {@code root} but leave {@code root} itself in place. */
    static void deleteContents(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    static String fmtCount(long n) {
        return String.format("%,d", n);
    }

    /** IEC-style human-readable bytes ({@code 1.2 MiB}, {@code 512 B}). */
    static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int unit = -1;
        do {
            v /= 1024.0;
            unit++;
        } while (v >= 1024.0 && unit < units.length - 1);
        return String.format("%.1f %s", v, units[unit]);
    }
}
