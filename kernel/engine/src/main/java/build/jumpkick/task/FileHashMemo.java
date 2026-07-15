// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Disk-backed {@code (path, size, mtime) → content-fingerprint} memo, so a no-op build doesn't
 * re-hash (and, for jars, re-inflate) unchanged artifacts on every run. This is the classic
 * Bazel/Gradle stat fast-path: trust {@code size+mtime} to prove a file unchanged, keep the
 * content hash authoritative whenever the stat is ambiguous.
 *
 * <p>Layout: one tiny file per memoized path under {@code <cache>/hash-memo/<aa>/<sha256(path)>},
 * containing {@code <size> <mtimeMillis> <token>}. The cache root comes from the current {@link
 * build.jumpkick.config.SessionContext session} ({@code --cache-dir} aware), so scoped sessions and
 * tests stay isolated. Deliberately no in-engine resident map — the engine has a hard heap budget
 * (see {@code docs/engine.md}); the OS page cache makes these one-block reads cheap.
 *
 * <h3>Correctness rules</h3>
 *
 * <ul>
 *   <li>A memo entry is trusted only when the file's current size <em>and</em> mtime match the
 *       recorded ones, <b>and</b> the mtime is at least {@link #SETTLE_MS} in the past. Filesystem
 *       mtimes are truncated, so a file modified "just now" could be changed again within the same
 *       tick without the stat noticing — such a file is re-hashed (the conservative same-second
 *       rule).
 *   <li>For the same reason an entry is only <em>stored</em> once the file's mtime has settled;
 *       hashing a file mid-write must not poison the memo.
 *   <li>Every failure (unreadable memo, cache dir missing, I/O error) fails open: the caller
 *       hashes content, exactly as before this memo existed.
 * </ul>
 */
public final class FileHashMemo {

    /** Distrust stat-identity for files modified within this window (mtime-granularity guard). */
    private static final long SETTLE_MS = 2_000;

    private FileHashMemo() {}

    /**
     * The memoized fingerprint token for {@code file}, or {@code null} when absent, stale, or not
     * yet settled. {@code size}/{@code mtimeMillis} are the caller's freshly-stat'ed values (the
     * caller stats anyway; passing them avoids a second stat).
     */
    public static String lookup(Path file, long size, long mtimeMillis) {
        if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return null;
        Path entry = entryPath(file);
        if (entry == null) return null;
        try {
            String content = Files.readString(entry, StandardCharsets.UTF_8);
            int sp1 = content.indexOf(' ');
            int sp2 = content.indexOf(' ', sp1 + 1);
            if (sp1 < 0 || sp2 < 0) return null;
            if (Long.parseLong(content.substring(0, sp1)) != size) return null;
            if (Long.parseLong(content.substring(sp1 + 1, sp2)) != mtimeMillis) return null;
            String token = content.substring(sp2 + 1).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException | NumberFormatException e) {
            return null; // fail open — caller hashes content
        }
    }

    /** Record {@code token} for {@code file}; best-effort (an I/O failure just skips the memo). */
    public static void store(Path file, long size, long mtimeMillis, String token) {
        if (System.currentTimeMillis() - mtimeMillis < SETTLE_MS) return; // not settled — don't trust the stat
        Path entry = entryPath(file);
        if (entry == null) return;
        try {
            Files.createDirectories(entry.getParent());
            // Write-then-move so a concurrent reader never sees a torn entry.
            Path tmp = Files.createTempFile(entry.getParent(), entry.getFileName().toString(), ".tmp");
            Files.writeString(tmp, size + " " + mtimeMillis + " " + token, StandardCharsets.UTF_8);
            Files.move(tmp, entry, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // best-effort — the memo is an optimisation, never a requirement
        }
    }

    /** {@code <cache>/hash-memo/<aa>/<sha256(abs path)>}, or {@code null} when no session cache resolves. */
    private static Path entryPath(Path file) {
        try {
            Path cache = build.jumpkick.config.SessionContext.current().cacheDir();
            String key = Hashing.sha256Hex(
                    file.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return cache.resolve("hash-memo").resolve(key.substring(0, 2)).resolve(key.substring(2));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
