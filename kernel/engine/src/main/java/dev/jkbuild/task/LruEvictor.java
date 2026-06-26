// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.repo.JkMavenLocalRepo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * LRU-style size-cap eviction over the CAS pool. Runs <em>after</em>
 * {@link CasSweep} — by the time we get here, every survivor is
 * reachable from some root; the only reason to delete more is to
 * respect a user-configured budget.
 *
 * <p>Ordering signal: {@link AccessLedger} latest-touch millis when
 * present; falls back to filesystem mtime for objects the ledger
 * doesn't cover yet (newly added writers, or pre-ledger objects).
 *
 * <p>When the budget forces us to delete a still-reachable object,
 * that's counted as {@code reachableEvicted} and surfaced in the
 * report — the user gets a "your budget is below your live set" signal
 * without us silently corrupting the next build (the action / sync
 * layer naturally re-fetches deleted CAS objects).
 */
public final class LruEvictor {

    private LruEvictor() {}

    public record Report(int deleted, long freedBytes, int reachableEvicted, long finalSize) {}

    /**
     * Evict oldest objects until the CAS is at or below {@code maxBytes}.
     * No-op when already under budget. {@code reachable} is the live set
     * computed by {@link CacheRoots}; only used here to count how many
     * of the evictees were still in it (for the warning summary).
     */
    public static Report evictDownTo(Cas cas, long maxBytes, Set<String> reachable, AccessLedger ledger, boolean dryRun)
            throws IOException {
        Path shaRoot = cas.root().resolve("sha256");
        if (!Files.isDirectory(shaRoot)) {
            return new Report(0, 0L, 0, 0L);
        }

        Map<String, Long> atimes = ledger.latestByHash();

        // Build the candidate list once. Atime falls back to mtime for
        // anything not in the ledger (most things will be, eventually).
        record Entry(Path file, String hex, long size, long atime, boolean reachable) {}
        List<Entry> entries = new ArrayList<>();
        long totalSize = 0;
        try (Stream<Path> stream = Files.walk(shaRoot)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.startsWith(".put-")) continue;
                var hexOpt = cas.hashFromPath(file);
                if (hexOpt.isEmpty()) continue;
                String hex = hexOpt.get();
                long size = Files.size(file);
                long atime =
                        atimes.getOrDefault(hex, Files.getLastModifiedTime(file).toMillis());
                entries.add(new Entry(file, hex, size, atime, reachable.contains(hex)));
                totalSize += size;
            }
        }

        if (totalSize <= maxBytes) {
            return new Report(0, 0L, 0, totalSize);
        }

        // Oldest atime first; ties broken by size (delete the bigger one
        // for the same age, so we hit the budget faster).
        entries.sort(Comparator.<Entry>comparingLong(Entry::atime)
                .thenComparing(Comparator.comparingLong(Entry::size).reversed()));

        int deleted = 0;
        long freed = 0;
        int reachableEvicted = 0;
        long remaining = totalSize;
        Set<String> deletedShas = new HashSet<>();
        for (Entry e : entries) {
            if (remaining <= maxBytes) break;
            if (!dryRun) Files.deleteIfExists(e.file());
            deleted++;
            freed += e.size();
            if (e.reachable()) reachableEvicted++;
            remaining -= e.size();
            deletedShas.add(e.hex());
        }
        // Keep the m2 mirror in lock-step — drop hard-links to evicted blobs.
        new JkMavenLocalRepo(cas.root()).removeShas(deletedShas, dryRun);
        return new Report(deleted, freed, reachableEvicted, remaining);
    }

    /**
     * Parse a human-friendly byte size: {@code 500}, {@code 500M},
     * {@code 1G}, {@code 20GiB}, {@code 2.5GB}. Returns bytes. Throws
     * {@link IllegalArgumentException} on unparseable input.
     */
    public static long parseSize(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("size required");
        }
        String s = spec.trim();
        // Split numeric prefix from unit suffix.
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        if (i == 0) {
            throw new IllegalArgumentException("size must start with a number: " + spec);
        }
        double n;
        try {
            n = Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("unparseable size: " + spec, e);
        }
        String unit = s.substring(i).trim().toUpperCase(java.util.Locale.ROOT);
        long mult =
                switch (unit) {
                    case "", "B" -> 1L;
                    case "K", "KB", "KIB" -> 1024L;
                    case "M", "MB", "MIB" -> 1024L * 1024;
                    case "G", "GB", "GIB" -> 1024L * 1024 * 1024;
                    case "T", "TB", "TIB" -> 1024L * 1024 * 1024 * 1024;
                    default -> throw new IllegalArgumentException("unknown size unit `" + unit + "` in " + spec);
                };
        return Math.round(n * mult);
    }
}
