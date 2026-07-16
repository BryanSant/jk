// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Append-only access journal for the CAS. Every CAS read (and every {@code jk.lock} read/write)
 * should record the shas involved so the GC and LRU evictor have signal for "which objects are
 * actually being used."
 *
 * <p>Filesystem atime updates can't be trusted — most Linux mounts use {@code relatime} (updates
 * only when atime is more than 24h older than mtime) or {@code noatime} (never updates). Our own
 * ledger is the only reliable way to know "this dep jar was touched again today."
 *
 * <p>Default location: {@code ~/.jk/cache/.access.log}.
 *
 * <p>Format (text, append-only, line-per-touch):
 *
 * <pre>{@code
 * <sha256-hex>\t<epoch-millis>\t<access-count>
 * }</pre>
 *
 * <p>The count is always {@code 1} on a fresh touch; it only grows when the GC compacts the log,
 * summing every entry for a sha into one line carrying the latest timestamp. {@link #entries()}
 * folds the raw log into that deduped view on read regardless of how many loose lines exist.
 *
 * <p>This class is best-effort: every write method swallows IO failures because the journal is an
 * optimisation signal, not a correctness boundary.
 */
public final class AccessLedger {

    static final String FILE_NAME = ".access.log";
    static final long COMPACT_THRESHOLD_BYTES = 1L * 1024 * 1024; // 1 MiB

    private final Path file;

    /** A folded ledger entry: the latest touch time and the summed count. */
    public record Entry(long latestMillis, long count) {}

    /** Default-path constructor — writes to {@code ~/.jk/cache/.access.log}. */
    public static AccessLedger atDefaultPath() {
        return new AccessLedger(JkDirs.cache().resolve(FILE_NAME));
    }

    public AccessLedger(Path file) {
        this.file = file;
    }

    /**
     * Record that {@code hex} was just accessed. Single-line append; no exception leaks. Small
     * appends on POSIX land without interleaving in practice; a LARGE {@link #touchAll} batch can
     * exceed that and tear against a concurrent writer — tolerable by design: {@code entries()}
     * skips corrupt lines, so the worst case is a lost LRU signal, never breakage.
     */
    public void touch(String hex) {
        touchAll(java.util.List.of(hex));
    }

    /** Record a batch of accesses in a single append (one line per hex; see torn-write note above). */
    public void touchAll(Collection<String> hexes) {
        if (hexes.isEmpty()) return;
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(hexes.size() * 80);
        for (String hex : hexes) {
            if (hex == null || hex.isBlank()) continue;
            sb.append(hex).append('\t').append(now).append('\t').append(1).append('\n');
        }
        if (sb.isEmpty()) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(
                    file, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort. A missed touch just means slightly worse LRU
            // signal; nothing breaks.
        }
    }

    /**
     * Touch every checksummed sha named by {@code lock} — its package jars and any sources jars.
     * Called whenever a {@code jk.lock} is read or written so the deps a project actually depends on
     * stay fresh against the 90-day GC.
     */
    public void touchLock(Lockfile lock) {
        Set<String> hexes = new LinkedHashSet<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            addHex(hexes, pkg.checksum());
            addHex(hexes, pkg.sourcesChecksum());
        }
        touchAll(hexes);
    }

    private static void addHex(Set<String> into, String checksum) {
        if (checksum == null || checksum.isBlank()) return;
        into.add(checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum);
    }

    /**
     * Fold the raw log into one {@link Entry} per sha: the latest timestamp seen and the summed
     * access count. Missing / corrupt lines are skipped.
     */
    public Map<String, Entry> entries() throws IOException {
        Map<String, Entry> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            // <hex>\t<millis>\t<count>
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            String hex = f[0].trim();
            if (hex.isEmpty() || !isLong(f[1]) || !isLong(f[2])) continue;
            long millis = Long.parseLong(f[1]);
            long count = Long.parseLong(f[2]);
            Entry prev = out.get(hex);
            if (prev == null) {
                out.put(hex, new Entry(millis, count));
            } else {
                out.put(hex, new Entry(Math.max(prev.latestMillis(), millis), prev.count() + count));
            }
        }
        return out;
    }

    /** Latest access timestamp per hex; missing hashes don't appear. */
    public Map<String, Long> latestByHash() throws IOException {
        Map<String, Long> out = new HashMap<>();
        for (var e : entries().entrySet()) {
            out.put(e.getKey(), e.getValue().latestMillis());
        }
        return out;
    }

    /**
     * Rewrite the ledger as one summed, deduped line per sha. Safe to call any time; no-op when the
     * file is smaller than {@link #COMPACT_THRESHOLD_BYTES}. Returns the new byte size.
     */
    public long compactIfLarge() throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        if (Files.size(file) < COMPACT_THRESHOLD_BYTES) return Files.size(file);
        writeAll(entries());
        return Files.size(file);
    }

    /**
     * Rewrite the ledger summed + deduped, dropping every sha in {@code drop} (the GC passes the shas
     * it just purged so their entries don't linger).
     */
    public void rewriteDropping(Set<String> drop) throws IOException {
        Map<String, Entry> folded = entries();
        folded.keySet().removeAll(drop);
        writeAll(folded);
    }

    /** Atomically replace the ledger with one line per sha, sorted by sha. */
    private void writeAll(Map<String, Entry> folded) throws IOException {
        StringBuilder sb = new StringBuilder();
        folded.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sb.append(e.getKey())
                .append('\t')
                .append(e.getValue().latestMillis())
                .append('\t')
                .append(e.getValue().count())
                .append('\n'));
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isLong(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Long.parseLong(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
