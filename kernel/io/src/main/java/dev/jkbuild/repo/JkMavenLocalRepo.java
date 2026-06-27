// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Linking;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A materialised Maven local repository under {@code <cache>/repo/}, the offline counterpart to the
 * {@link Cas}.
 *
 * <p>The CAS is keyed by SHA-256 only, so given a Maven coordinate there's no way to find the blob
 * we hold for it — which makes offline resolution impossible. This repo closes that gap by
 * mirroring the standard m2 layout ({@code
 * <group-as-dirs>/<artifact>/<version>/<artifact>-<version>[-<classifier>].<ext>}) and
 * <em>hard-linking</em> each fetched POM, JAR, and sources JAR back to its CAS blob. One inode, two
 * paths: no second copy on disk, but the artifact is now addressable by coordinate and the bytes
 * survive a CAS sweep until both links are gone.
 *
 * <p>Hard links (not symlinks) are used deliberately — {@link Linking} falls back to a byte copy
 * when the filesystem can't link, so this works on Windows without Developer Mode and across
 * filesystem boundaries on Linux/macOS, none of it requiring elevated privileges.
 *
 * <p>{@code maven-metadata.xml} is deliberately not mirrored — it has no version key and is stale
 * offline; version enumeration comes from the directory listing instead.
 *
 * <p>Mirroring is best-effort: it must never fail a successful fetch, so {@link #materialize}
 * swallows IO errors and read paths degrade to "not present". The repo lives under the cache so a
 * cache wipe invalidates it together — it is a derived index, rebuilt by fetching online.
 */
/**
 * @deprecated Superseded by {@link RepoArtifactStore} (index-only mode pointing to
 *             {@code ~/.m2/repository}) and direct Maven-compatible writes via
 *             {@link M2CompatWriter}. Kept only for GC integration ({@link #removeShas} /
 *             {@link #indexBySha}) which sweeps the legacy {@code <cache>/repo/} mirror tree
 *             left by prior jk builds. Will be removed once all users have migrated their caches
 *             (i.e. after running {@code jk cache prune --sweep}).
 */
@Deprecated
public final class JkMavenLocalRepo {

    /** No-op repo for call sites that don't participate in offline resolve. */
    public static final JkMavenLocalRepo NONE = new JkMavenLocalRepo();

    /** Root of the m2 tree, or {@code null} for {@link #NONE}. */
    private final Path root;

    private JkMavenLocalRepo() {
        this.root = null;
    }

    /**
     * @param cacheRoot the jk cache directory (e.g. {@code ~/.jk/cache}).
     */
    public JkMavenLocalRepo(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.root = cacheRoot.resolve("repo");
    }

    /** The m2 tree root ({@code <cache>/repo}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    /** A cached module and the versions present locally. */
    public record Module(String group, String artifact, List<String> versions) {
        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    /**
     * Mirror the CAS blob at {@code casBlob} into the repo under the given Maven-layout relative path
     * (as produced by {@link MavenLayout}). The link shares the CAS inode; if the repo entry already
     * exists it is left untouched. Never throws — a mirror failure must not fail a fetch.
     */
    public void materialize(String relativePath, Path casBlob) {
        if (root == null) return;
        try {
            Path target = root.resolve(relativePath);
            if (Files.exists(target)) return; // idempotent — same content
            Linking.linkOrCopy(casBlob, target);
        } catch (IOException | RuntimeException ignored) {
            // best-effort mirror; a fetch must not fail because mirroring did
        }
    }

    /** The materialised file for {@code relativePath}, if present. */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path f = root.resolve(relativePath);
        return Files.isRegularFile(f) ? Optional.of(f) : Optional.empty();
    }

    /**
     * Versions of {@code group:artifact} present locally — the names of the version directories that
     * hold at least one artifact file. Empty (not an error) when nothing is mirrored. Order is
     * unspecified; callers that need newest-first should sort.
     */
    public List<String> versions(String group, String artifact) {
        if (root == null) return List.of();
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(JkMavenLocalRepo::hasArtifactFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every {@code group:artifact} present in the repo, with the versions held locally for each.
     * Powers local cache search ({@code jk cache search}). Empty for {@link #NONE} or a cold cache.
     * Versions are in directory order — callers that want newest-first should sort.
     */
    public List<Module> modules() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        // Group artifact files by their version directory (the file's parent).
        Map<Path, Boolean> versionDirs = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(p -> versionDirs.put(p.getParent(), Boolean.TRUE));
        } catch (IOException e) {
            return List.of();
        }
        Map<String, List<String>> versionsByModule = new java.util.TreeMap<>();
        Map<String, String[]> ga = new HashMap<>();
        for (Path versionDir : versionDirs.keySet()) {
            Path rel = root.relativize(versionDir);
            int n = rel.getNameCount();
            if (n < 3) continue; // need at least one group segment + artifact + version
            String version = rel.getName(n - 1).toString();
            String artifact = rel.getName(n - 2).toString();
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < n - 2; i++) {
                if (i > 0) group.append('.');
                group.append(rel.getName(i));
            }
            String key = group + ":" + artifact;
            ga.putIfAbsent(key, new String[] {group.toString(), artifact});
            versionsByModule.computeIfAbsent(key, k -> new ArrayList<>()).add(version);
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = ga.get(e.getKey());
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Remove every repo entry whose content hashes to one of {@code shas}, pruning directories left
     * empty. Used by the cache GC / sweep / LRU evictor to keep the mirror in lock-step with the CAS
     * — a hard link left behind would keep the inode (and its bytes) alive after the CAS blob is
     * deleted. Returns the number of repo files removed. Never throws.
     */
    public int removeShas(Set<String> shas, boolean dryRun) {
        if (root == null || shas.isEmpty() || !Files.isDirectory(root)) return 0;
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
        if (root == null || !Files.isDirectory(root)) return out;
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

    private static boolean hasArtifactFile(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return false;
        try (Stream<Path> entries = Files.list(versionDir)) {
            return entries.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }
}
