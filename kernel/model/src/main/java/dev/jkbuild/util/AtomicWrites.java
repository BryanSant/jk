// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The ONE atomic-write idiom: bytes land in a temp sibling (same directory, so the move never
 * crosses filesystems), then move atomically over the target; {@code REPLACE_EXISTING} fallback
 * where the filesystem can't promise atomic replace. A crash leaves at most a {@code .tmp}
 * sibling, never a torn target.
 *
 * <p>Exists so every writer (CAS, version store, ledgers, pointer flips) shares one temp-naming
 * and one move policy instead of five hand-rolled variants.
 */
public final class AtomicWrites {

    private AtomicWrites() {}

    /** Write {@code bytes} to {@code target} atomically. */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        try {
            Files.write(tmp, bytes);
            moveInto(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Write {@code content} (UTF-8) to {@code target} atomically. */
    public static void replace(Path target, String content) throws IOException {
        replace(target, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Move a fully-written temp file over {@code target} atomically ({@code REPLACE_EXISTING}
     * fallback). The temp file must live in {@code target}'s directory.
     */
    public static void moveInto(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
