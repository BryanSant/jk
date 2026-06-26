// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Filesystem utility methods shared across kernel modules. Intentionally
 * minimal — only consolidates patterns that appear in 3+ unrelated classes.
 */
public final class PathUtil {

    private PathUtil() {}

    /**
     * Recursively delete {@code root} and all of its contents.
     *
     * <p>Deletes children before parents (reverse walk order) so non-empty
     * directories are emptied before the directory itself is removed.
     * Per-entry {@link IOException}s are silently ignored; if the walk itself
     * fails (e.g. the path vanished concurrently) the exception is also
     * swallowed. A missing or {@code null} root is a no-op.
     *
     * <p>Callers that need to propagate {@link IOException} (e.g. on a
     * critical cleanup path) should keep their own implementation.
     */
    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
