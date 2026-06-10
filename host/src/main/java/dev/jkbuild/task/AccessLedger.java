// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Append-only access journal for the CAS. Every CAS read should call
 * {@link #touch} so the LRU evictor has signal for "which objects are
 * actually being used."
 *
 * <p>Filesystem atime updates can't be trusted — most Linux mounts use
 * {@code relatime} (updates only when atime is more than 24h older than
 * mtime) or {@code noatime} (never updates). Our own ledger is the only
 * reliable way to know "this dep jar was touched again today."
 *
 * <p>Format (text, append-only, line-per-touch):
 * <pre>{@code
 *   <epoch-millis>\t<hex>
 * }</pre>
 *
 * <p>Compaction: when the ledger grows past {@link #COMPACT_THRESHOLD_BYTES}
 * the prune step rewrites it as one line per distinct sha with the
 * latest seen millis. Cheap, idempotent, never blocks a read.
 *
 * <p>This class is best-effort: every method swallows IO failures
 * because the journal is an optimisation signal, not a correctness
 * boundary.
 */
public final class AccessLedger {

    static final String FILE_NAME = "access-log";
    static final long COMPACT_THRESHOLD_BYTES = 1L * 1024 * 1024; // 1 MiB

    private final Path file;

    public AccessLedger(Path casRoot) {
        this.file = casRoot.resolve(FILE_NAME);
    }

    /**
     * Record that {@code hex} was just accessed. Single-line append; no
     * exception leaks. {@code FileChannel.open(APPEND)} on POSIX is
     * atomic for writes shorter than {@code PIPE_BUF}, so concurrent
     * touches don't interleave bytes within a line.
     */
    public void touch(String hex) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String line = System.currentTimeMillis() + "\t" + hex + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort. A missed touch just means slightly worse LRU
            // signal; nothing breaks.
        }
    }

    /** Latest access timestamp per hex; missing hashes don't appear. */
    public Map<String, Long> latestByHash() throws IOException {
        Map<String, Long> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            long millis;
            try {
                millis = Long.parseLong(line.substring(0, tab));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String hex = line.substring(tab + 1).trim();
            if (hex.isEmpty()) continue;
            out.merge(hex, millis, Math::max);
        }
        return out;
    }

    /**
     * Rewrite the ledger as one line per distinct sha. Safe to call
     * any time; no-op when the file is smaller than
     * {@link #COMPACT_THRESHOLD_BYTES}. Returns the new byte size.
     */
    public long compactIfLarge() throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        if (Files.size(file) < COMPACT_THRESHOLD_BYTES) return Files.size(file);
        Map<String, Long> latest = latestByHash();
        StringBuilder sb = new StringBuilder();
        // Stable order is nice for diff'ing the file in flight.
        latest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return Files.size(file);
    }

    /** Erase the journal — used by {@code jk cache clean}. */
    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }
}
