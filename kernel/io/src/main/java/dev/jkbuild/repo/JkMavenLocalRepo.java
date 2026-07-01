// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * GC-only remnant of the legacy {@code <cache>/repo/} m2 mirror, superseded by {@link
 * RepoArtifactStore} (index-only mode pointing to {@code ~/.m2/repository}) and direct
 * Maven-compatible writes via {@link M2CompatWriter}.
 *
 * <p>Nothing writes to {@code <cache>/repo/} anymore — every fetch/materialize/offline-lookup path
 * has moved to {@link RepoArtifactStore}. This class survives solely so {@link #removeShas} /
 * {@link #indexBySha} can sweep hard links left behind under {@code <cache>/repo/} by jk versions
 * that predate the named-repo store, so the CAS blobs they hold can actually be reclaimed. Delete
 * this class once that tree is empty on every machine that matters (i.e. once every install has run
 * {@code jk cache prune --sweep} at least once since the migration).
 */
public final class JkMavenLocalRepo {

    /** Root of the m2 tree. */
    private final Path root;

    /**
     * @param cacheRoot the jk cache directory (e.g. {@code ~/.jk/cache}).
     */
    public JkMavenLocalRepo(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.root = cacheRoot.resolve("repo");
    }

    /**
     * Remove every repo entry whose content hashes to one of {@code shas}, pruning directories left
     * empty. Used by the cache GC / sweep / LRU evictor to keep the mirror in lock-step with the CAS
     * — a hard link left behind would keep the inode (and its bytes) alive after the CAS blob is
     * deleted. Returns the number of repo files removed. Never throws.
     */
    public int removeShas(Set<String> shas, boolean dryRun) {
        if (shas.isEmpty() || !Files.isDirectory(root)) return 0;
        int removed = 0;
        for (var entry : indexBySha().entrySet()) {
            if (!shas.contains(entry.getKey())) continue;
            for (Path p : entry.getValue()) {
                try {
                    if (!dryRun) {
                        Files.deleteIfExists(p);
                        pruneEmptyParents(p.getParent());
                    }
                    removed++;
                } catch (IOException ignored) {
                    // best-effort; a stuck file just stays mirrored
                }
            }
        }
        return removed;
    }

    /**
     * Index the repo by the SHA-256 of each file's content. The m2 filename encodes the coordinate,
     * not the hash, so we re-hash — done only when something is actually being purged, so the cost is
     * paid rarely.
     */
    public Map<String, List<Path>> indexBySha() {
        Map<String, List<Path>> out = new HashMap<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                try {
                    String hex = Hashing.sha256Hex(Files.readAllBytes(p));
                    out.computeIfAbsent(hex, k -> new ArrayList<>()).add(p);
                } catch (IOException ignored) {
                    // unreadable file — skip
                }
            }
        } catch (IOException ignored) {
            // walk failure — return what we have
        }
        return out;
    }

    private void pruneEmptyParents(Path dir) {
        Path cur = dir;
        while (cur != null && cur.startsWith(root) && !cur.equals(root)) {
            try {
                Files.delete(cur); // throws if non-empty — stop climbing
            } catch (IOException stop) {
                return;
            }
            cur = cur.getParent();
        }
    }
}
