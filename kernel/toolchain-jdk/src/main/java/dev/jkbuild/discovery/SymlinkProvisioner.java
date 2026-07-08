// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.discovery;

import dev.jkbuild.jdk.HostPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Helpers for the link side of the discover-and-link pattern. Splits out so {@code JdkRegistry} and
 * the Maven/Gradle/Kotlin {@code ToolRegistry} can share the same Windows guard and the same
 * link-vs-real semantics.
 *
 * <p>Per the goal: never symlink on Windows — {@link #canSymlink()} short-circuits there. Callers
 * fall back to a regular download path.
 */
public final class SymlinkProvisioner {

    private SymlinkProvisioner() {}

    /** False on Windows (junction handling is too quirky). True elsewhere. */
    public static boolean canSymlink() {
        return !HostPlatform.isWindows();
    }

    /**
     * Create a symbolic link {@code target → source}. Caller has already checked {@link
     * #canSymlink()}; this method throws on Windows.
     *
     * <p>{@code target}'s parent is created if missing. Pre-existing entries at {@code target} are
     * removed first (idempotent).
     */
    public static void link(Path target, Path source) throws IOException {
        if (!canSymlink()) {
            throw new IOException("symlinking is disabled on Windows");
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            removeRecursivelyOrUnlink(target);
        }
        Files.createSymbolicLink(target, source);
    }

    /**
     * True when the path is a symlink whose target no longer resolves to an existing entry. Useful
     * for the broken-link healthcheck before exec.
     */
    public static boolean isBrokenLink(Path path) {
        if (!Files.isSymbolicLink(path)) return false;
        return !Files.exists(path); // follows the link by default
    }

    /** Remove the link itself (not its target). Safe even when the target has gone away. */
    public static void unlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Recursively delete a directory tree, or unlink a symlink. Used when we're about to overwrite a
     * jk-managed entry with a fresh link.
     */
    private static void removeRecursivelyOrUnlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
            return;
        }
        Files.deleteIfExists(path);
    }
}
