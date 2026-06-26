// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.util.JkDirs;
import dev.jkbuild.util.PathUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deferred deletion of superseded JDK installs, recorded in
 * {@code ~/.jk/jdks/.to-be-removed} (one absolute path per line).
 *
 * <p>On Windows a running {@code java.exe} keeps its install directory locked,
 * so {@code jk jdk update} can't delete the old patch inline after repointing
 * the {@link StableJdkPointer}. Instead it {@link #enqueue}s the dir; every JDK
 * command then {@link #drain}s the queue, deleting whatever is no longer in use
 * and keeping the rest for next time. On POSIX deletion succeeds immediately, so
 * the queue drains on the very next run — the same code path, no platform fork.
 *
 * <p>Best-effort throughout: a locked dir, an unreadable queue, or a concurrent
 * {@code jk} draining the same file never raises — the worst case is an entry
 * surviving an extra round.
 */
public final class JdkGarbage {

    private static final String QUEUE_FILE = ".to-be-removed";

    private final Path jdksRoot;

    public JdkGarbage(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    public static JdkGarbage atDefaultRoot() {
        return new JdkGarbage(JkDirs.jdks());
    }

    /** Record {@code dir} for later deletion. No-op if it's not under the JDK root. */
    public void enqueue(Path dir) {
        Path abs = canonical(dir);
        if (!abs.startsWith(canonicalRoot())) return;   // never queue anything outside ~/.jk/jdks
        try {
            Files.createDirectories(jdksRoot);
            Files.writeString(queueFile(), abs + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort; a missed enqueue just leaves a stale dir behind.
        }
    }

    /** Delete every queued dir that's no longer in use; rewrite the queue with survivors. */
    public void drain() {
        Path queue = queueFile();
        if (!Files.isRegularFile(queue)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(queue, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        List<String> survivors = new ArrayList<>();
        Path root = canonicalRoot();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Path dir = canonical(Path.of(trimmed));
            if (!dir.startsWith(root)) continue;          // defensive: never wander outside ~/.jk/jdks
            if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue; // already gone
            deleteRecursively(dir);
            if (Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                survivors.add(trimmed);                    // still locked — try again next run
            }
        }
        try {
            if (survivors.isEmpty()) {
                Files.deleteIfExists(queue);
            } else {
                Files.write(queue, survivors, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Another jk may have rewritten it concurrently — fine.
        }
    }

    private Path queueFile() {
        return jdksRoot.resolve(QUEUE_FILE);
    }

    private Path canonicalRoot() {
        return canonical(jdksRoot);
    }

    /** Resolve symlinks when the path exists (macOS {@code /var}→{@code /private/var}); else normalize. */
    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
