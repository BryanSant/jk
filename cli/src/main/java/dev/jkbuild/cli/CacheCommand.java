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

        @Override
        public Integer call() throws IOException {
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                System.out.println("Nothing to prune — " + root + " does not exist.");
                return 0;
            }
            long cutoffMillis = System.currentTimeMillis()
                    - (long) olderThanDays * 24L * 60L * 60L * 1000L;

            int prunedCount = 0;
            long freedBytes = 0;

            // Stale action-cache entries.
            Path actionsDir = root.resolve("actions");
            if (Files.isDirectory(actionsDir)) {
                for (Path file : olderThan(actionsDir, cutoffMillis)) {
                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    prunedCount++;
                    freedBytes += size;
                }
            }

            // Atomic-write tempfiles left behind by interrupted Cas.put calls.
            Path shaDir = root.resolve("sha256");
            if (Files.isDirectory(shaDir)) {
                for (Path file : tempFiles(shaDir)) {
                    long size = Files.size(file);
                    if (!dryRun) Files.deleteIfExists(file);
                    prunedCount++;
                    freedBytes += size;
                }
            }

            String verb = dryRun ? "Would prune" : "Pruned";
            System.out.printf("%s %s entries, %s freed.%n",
                    verb, fmtCount(prunedCount), fmtBytes(freedBytes));
            return 0;
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
