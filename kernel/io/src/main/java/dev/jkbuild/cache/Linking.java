// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File-materialisation strategy used by the CAS / ActionCache: try a hard
 * link first, fall back to a copy when the filesystem can't or won't.
 *
 * <p>Hard-linking is the right primitive for content-addressed I/O — two
 * paths share one inode, so restoring a 100 MB classes/ tree from the CAS
 * costs a few directory-entry writes instead of 100 MB of byte shuffling.
 * Safe for {@code .class}/{@code .jar}/etc. because every writer in the
 * pipeline (javac, jar packager) uses {@code O_CREAT|O_TRUNC} or
 * temp-and-rename, which break the link rather than mutating the inode in
 * place.
 *
 * <p>Fallback chain:
 * <ol>
 *   <li>{@link Files#createLink} — POSIX same-filesystem, the fast path.</li>
 *   <li>{@link Files#copy} with {@code COPY_ATTRIBUTES} — cross-filesystem
 *       on POSIX, plus all paths on Windows without Developer Mode. On
 *       reflink-capable filesystems (btrfs/xfs/zfs) Java's copy uses
 *       {@code copy_file_range(2)} which is also inode-cheap.</li>
 * </ol>
 *
 * <p>{@code target} is deleted first if present — {@code createLink} can't
 * replace an existing entry, and {@code copy} only does with the explicit
 * {@code REPLACE_EXISTING} option. Unifying the precondition keeps callers
 * simple.
 */
public final class Linking {

    private Linking() {}

    /**
     * Materialise {@code target} as a hard link to {@code source}, or copy
     * the bytes if linking isn't supported on this filesystem pair.
     * Replaces any existing entry at {@code target}.
     */
    public static void linkOrCopy(Path source, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.deleteIfExists(target);
        try {
            Files.createLink(target, source);
            return;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Fall through to copy. UnsupportedOperationException covers
            // filesystems that don't implement hard links at all
            // (older Windows configurations, some FUSE mounts);
            // FileSystemException covers cross-filesystem and
            // permission-denied cases on Linux/macOS.
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES,
                StandardCopyOption.REPLACE_EXISTING);
    }
}
