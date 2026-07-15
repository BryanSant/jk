// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.engine.journal;

import build.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * The persisted build-history journal under {@code ~/.jk/state/builds/journal/}. Each finished
 * engine build becomes one self-contained entry directory {@code <id>/} holding {@code record.json}
 * (the structured {@link BuildRecord}) plus snapshot copies of the run's heavy artifacts
 * ({@code test-results.md}, {@code jk.lock}, {@code diagnostics.txt}) — frozen to that run and
 * immune to a later rebuild or deletion of the project.
 *
 * <p><strong>Id scheme:</strong> {@code <UTC yyyyMMdd'T'HHmmssSSS>-<4 hex>}, e.g.
 * {@code 20260710T143022417-3f9a}. Lexicographic order equals chronological order (newest-first is a
 * reverse sort), and it survives engine restarts (unlike the in-memory request counter). The
 * millisecond timestamp plus 4 random hex makes intra-millisecond collisions between concurrent
 * builds vanishing; the final directory move is the atomic existence check that forces a retry.
 *
 * <p><strong>Concurrency:</strong> each build owns a unique {@code <id>/} and writes only within it,
 * so concurrent {@link #append} calls never contend and need no lock. {@link #list}/{@link #prune}
 * tolerate an entry appearing or vanishing mid-scan. {@link #append} stages into a dot-prefixed temp
 * directory and moves it into place atomically, so a reader never sees a half-written entry.
 *
 * <p>Every method is best-effort: {@link #append} never throws (a journaling failure must never fail
 * or slow a build), and the readers degrade to empty/false on IO or parse errors.
 */
public final class BuildJournal {

    /** Snapshot files a caller may fetch by name; also the traversal whitelist for {@link #artifact}. */
    public static final String TEST_RESULTS_MD = "test-results.md";

    public static final String LOCKFILE = "jk.lock";

    public static final String DIAGNOSTICS_TXT = "diagnostics.txt";

    private static final String RECORD = "record.json";

    private static final DateTimeFormatter ID_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");

    private static final int APPEND_RETRIES = 8;

    private final Path journalDir;

    public BuildJournal(Path journalDir) {
        this.journalDir = journalDir.normalize();
    }

    /** The live journal at {@code ~/.jk/state/builds/journal/}. */
    public static BuildJournal current() {
        return new BuildJournal(JkDirs.builds().resolve("journal"));
    }

    /** The heavy artifacts to snapshot beside {@code record.json}; any field may be {@code null}. */
    public record Snapshot(Path testResultsMd, Path lockfile, String diagnosticsText) {
        public static final Snapshot NONE = new Snapshot(null, null, null);
    }

    /** What a {@link #prune} pass reclaimed. */
    public record PruneResult(int removedEntries, long removedBytes) {}

    /**
     * Persist {@code record} (its {@code id} is (re)assigned here) plus {@code snapshot}. Returns the
     * assigned id, or {@code null} if persistence failed — never throws.
     */
    public String append(BuildRecord record, Snapshot snapshot) {
        try {
            Files.createDirectories(journalDir);
            long finishedAt = record.finishedAt() > 0 ? record.finishedAt() : System.currentTimeMillis();
            String stamp = ID_TS.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(finishedAt), ZoneOffset.UTC));
            for (int attempt = 0; attempt < APPEND_RETRIES; attempt++) {
                String id = stamp + "-" + randomHex();
                Path target = journalDir.resolve(id);
                Path tmp = journalDir.resolve("." + id + ".tmp");
                try {
                    Files.createDirectory(tmp);
                } catch (FileAlreadyExistsException e) {
                    continue; // vanishingly unlikely — regenerate the suffix
                }
                try {
                    Files.writeString(tmp.resolve(RECORD), Json.write(withId(record, id)), StandardCharsets.UTF_8);
                    writeSnapshot(tmp, snapshot);
                    move(tmp, target);
                    return id;
                } catch (FileAlreadyExistsException e) {
                    deleteTreeQuietly(tmp); // target id taken — retry with a fresh suffix
                } catch (IOException e) {
                    deleteTreeQuietly(tmp);
                    return null;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeSnapshot(Path dir, Snapshot s) throws IOException {
        if (s == null) return;
        if (s.testResultsMd() != null && Files.isRegularFile(s.testResultsMd())) {
            Files.copy(s.testResultsMd(), dir.resolve(TEST_RESULTS_MD), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.lockfile() != null && Files.isRegularFile(s.lockfile())) {
            Files.copy(s.lockfile(), dir.resolve(LOCKFILE), StandardCopyOption.REPLACE_EXISTING);
        }
        if (s.diagnosticsText() != null && !s.diagnosticsText().isBlank()) {
            Files.writeString(dir.resolve(DIAGNOSTICS_TXT), s.diagnosticsText(), StandardCharsets.UTF_8);
        }
    }

    /** Newest-first list of every readable entry. Bounded implicitly by {@link #prune}. */
    public List<BuildRecord> list() {
        List<BuildRecord> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            readRecord(dir).ifPresent(out::add);
        }
        return out;
    }

    public Optional<BuildRecord> get(String id) {
        return validId(id) ? readRecord(journalDir.resolve(id)) : Optional.empty();
    }

    /**
     * The newest {@code limit} entries' raw {@code record.json} contents (already valid JSON), for
     * assembling a list response verbatim without a parse/re-serialize round-trip.
     */
    public List<String> rawRecords(int limit) {
        List<String> out = new ArrayList<>();
        for (Path dir : entryDirs()) {
            if (out.size() >= limit) break;
            Path rec = dir.resolve(RECORD);
            try {
                if (Files.isRegularFile(rec)) out.add(Files.readString(rec, StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // skip an unreadable entry
            }
        }
        return out;
    }

    /** The raw {@code record.json} path (for verbatim HTTP passthrough), if the entry exists. */
    public Optional<Path> recordFile(String id) {
        if (!validId(id)) return Optional.empty();
        Path p = journalDir.resolve(id).resolve(RECORD);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** A snapshot artifact by whitelisted {@code name}, if present. */
    public Optional<Path> artifact(String id, String name) {
        if (!validId(id) || !isArtifactName(name)) return Optional.empty();
        Path p = journalDir.resolve(id).resolve(name);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** Delete one entry. {@code true} if it existed and was removed. */
    public boolean delete(String id) {
        if (!validId(id)) return false;
        Path dir = journalDir.resolve(id);
        if (!Files.isDirectory(dir)) return false;
        deleteTreeQuietly(dir);
        return true;
    }

    /**
     * Enforce retention: delete entries older than {@code maxAgeMillis} (0 = no age limit), then, if
     * the remaining total exceeds {@code maxDiskBytes} (0 = no cap), delete oldest-first until under
     * it. Ages come from the id timestamp (no file read), falling back to the dir's mtime.
     */
    public PruneResult prune(long maxAgeMillis, long maxDiskBytes, long nowMillis) {
        List<Entry> entries = new ArrayList<>();
        for (Path dir : entryDirs()) {
            entries.add(new Entry(dir, entryMillis(dir, nowMillis), sizeOf(dir)));
        }
        int removed = 0;
        long removedBytes = 0;
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries) {
            if (maxAgeMillis > 0 && nowMillis - e.millis > maxAgeMillis) {
                deleteTreeQuietly(e.dir);
                removed++;
                removedBytes += e.size;
            } else {
                kept.add(e);
            }
        }
        if (maxDiskBytes > 0) {
            long total = kept.stream().mapToLong(Entry::size).sum();
            if (total > maxDiskBytes) {
                kept.sort(Comparator.comparingLong(Entry::millis)); // oldest first
                for (Entry e : kept) {
                    if (total <= maxDiskBytes) break;
                    deleteTreeQuietly(e.dir);
                    removed++;
                    removedBytes += e.size;
                    total -= e.size;
                }
            }
        }
        return new PruneResult(removed, removedBytes);
    }

    // ---------------------------------------------------------------- internals

    private record Entry(Path dir, long millis, long size) {}

    /** Entry directories (dot-prefixed staging/stamp names excluded), newest id first. */
    private List<Path> entryDirs() {
        if (!Files.isDirectory(journalDir)) return List.of();
        try (Stream<Path> s = Files.list(journalDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Optional<BuildRecord> readRecord(Path dir) {
        Path record = dir.resolve(RECORD);
        if (!Files.isRegularFile(record)) return Optional.empty();
        try {
            return Optional.of(Json.read(Files.readString(record, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private long entryMillis(Path dir, long fallback) {
        String name = dir.getFileName().toString();
        int dash = name.indexOf('-');
        String stamp = dash > 0 ? name.substring(0, dash) : name;
        try {
            return LocalDateTime.parse(stamp, ID_TS).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            try {
                return Files.getLastModifiedTime(dir).toMillis();
            } catch (IOException io) {
                return fallback;
            }
        }
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Atomic if the filesystem supports it (same dir → same store), else a plain move. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort — a concurrent delete/append may have removed it already
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private static boolean isArtifactName(String name) {
        return TEST_RESULTS_MD.equals(name) || LOCKFILE.equals(name) || DIAGNOSTICS_TXT.equals(name);
    }

    /** Reject ids that could escape the journal directory (path traversal from a hostile query). */
    private boolean validId(String id) {
        if (id == null || id.isBlank() || id.startsWith(".")) return false;
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0 || id.contains("..")) return false;
        Path resolved = journalDir.resolve(id).normalize();
        return journalDir.equals(resolved.getParent());
    }

    private static String randomHex() {
        String hex = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "0000".substring(hex.length()) + hex;
    }

    private static BuildRecord withId(BuildRecord r, String id) {
        return new BuildRecord(
                id, r.buildNumber(), r.schema(), r.kind(), r.dir(), r.coord(),
                r.startedAt(), r.finishedAt(), r.millis(),
                r.success(), r.cancelled(), r.exitCode(), r.jkVersion(),
                r.tests(), r.modules(), r.steps(), r.diagnostics(), r.trigger(), r.commit());
    }
}
