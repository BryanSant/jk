// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Linking;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Per-named-repository artifact index and (for {@code local}) artifact store.
 *
 * <h3>Two modes</h3>
 * <dl>
 *   <dt>Index-only (all named repos except {@code local})</dt>
 *   <dd>The {@code .sha256} sidecar lives at {@code <cache>/repos/<name>/<m2-path>.sha256}; the
 *       actual artifact bytes live in {@code ~/.m2/repository} (see {@link M2Dirs}). {@link
 *       #locate} checks the sidecar and returns the {@code ~/.m2} path. An mtime guard
 *       re-validates the sidecar hash when the {@code ~/.m2} file is newer than the sidecar.</dd>
 *   <dt>Full store ({@code local} — worker JARs)</dt>
 *   <dd>Both the artifact and the {@code .sha256} sidecar live under
 *       {@code <cache>/repos/local/}. Written via {@link #materialize}.</dd>
 * </dl>
 *
 * <h3>Sidecar invariant</h3>
 * The sidecar is written <em>last</em>, after the artifact is fully on disk. Its existence is the
 * single O(1) "fully stored" signal — a partial download never leaves a sidecar behind. Its
 * content is the 64-char SHA-256 hex string.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore((Path) null, (Path) null);

    private final Path root; // <cache>/repos/<name>/
    private final Path m2Root; // ~/.m2/repository if index-only, null if full store

    private RepoArtifactStore(Path root, Path m2Root) {
        this.root = root;
        this.m2Root = m2Root;
    }

    /** Full store — artifact and sidecar both live under {@code repos/<repoName>/}. */
    public RepoArtifactStore(Path cacheRoot, String repoName) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(repoName, "repoName");
        this.root = cacheRoot.resolve("repos").resolve(repoName);
        this.m2Root = null;
    }

    /**
     * Factory: choose the right mode for {@code repoName}. {@code local} gets a full store;
     * everything else gets an index-only store pointing to {@link M2Dirs#localRepository()}.
     */
    public static RepoArtifactStore forRepoName(Path cacheRoot, String repoName) {
        if ("local".equals(repoName)) {
            return new RepoArtifactStore(cacheRoot, repoName);
        }
        Path root = cacheRoot.resolve("repos").resolve(repoName);
        return new RepoArtifactStore(root, M2Dirs.localRepository());
    }

    /** True when this store only holds hash sidecars; actual JARs live in {@code ~/.m2}. */
    public boolean isIndexOnly() {
        return m2Root != null;
    }

    // -------------------------------------------------------------------------
    // Presence and location
    // -------------------------------------------------------------------------

    /**
     * True when the artifact at {@code relativePath} has been fully stored — sidecar exists AND the
     * actual artifact file is present. Two O(1) stat calls; no content read.
     */
    public boolean contains(String relativePath) {
        if (root == null) return false;
        if (!Files.isRegularFile(sidecarPath(relativePath))) return false;
        return Files.isRegularFile(artifactPath(relativePath));
    }

    /**
     * The stored artifact path if fully materialised, else empty.
     *
     * <p>For index-only repos: applies an <em>mtime guard</em>. If the {@code ~/.m2} artifact is
     * newer than the sidecar (e.g. Maven updated the file), the sidecar is re-hashed and
     * overwritten. A lockfile checksum mismatch surfaces on the next {@code jk build}; run
     * {@code jk lock --force} to re-pin.
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path sidecar = sidecarPath(relativePath);
        if (!Files.isRegularFile(sidecar)) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return Optional.empty();
        if (m2Root != null) {
            try {
                FileTime artifactMtime = Files.getLastModifiedTime(artifact);
                FileTime sidecarMtime = Files.getLastModifiedTime(sidecar);
                if (artifactMtime.compareTo(sidecarMtime) > 0) {
                    String newHash = Hashing.sha256Hex(artifact);
                    Files.writeString(sidecar, newHash);
                }
            } catch (IOException ignored) {
            }
        }
        return Optional.of(artifact);
    }

    // -------------------------------------------------------------------------
    // Write paths
    // -------------------------------------------------------------------------

    /**
     * Index-only write: record that the artifact at {@code relativePath} was stored in
     * {@code ~/.m2} with hash {@code sha256}. Writes only the sidecar. Idempotent; best-effort.
     */
    public void recordIndex(String relativePath, String sha256) {
        if (root == null) return;
        try {
            Path sidecar = sidecarPath(relativePath);
            if (Files.exists(sidecar)) return;
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, sha256);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /**
     * Full-store write: hard-link {@code casBlob} to {@code repos/<name>/} and write sidecar.
     * Used only for the {@code local} repo (worker JARs). Idempotent; best-effort.
     */
    public void materialize(String relativePath, Path casBlob, String sha256) {
        if (root == null) return;
        try {
            Path sidecar = sidecarPath(relativePath);
            if (Files.exists(sidecar)) return;
            Path artifact = root.resolve(relativePath);
            Linking.linkOrCopy(casBlob, artifact);
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, sha256);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Offline helpers
    // -------------------------------------------------------------------------

    /**
     * Version directories present in this index — directories under
     * {@code repos/<name>/<group>/<artifact>/} that hold at least one tracked sidecar.
     */
    public List<String> versions(String group, String artifact) {
        if (root == null) return List.of();
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(this::hasTrackedFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A cached module and the versions held locally for it. */
    public record Module(String group, String artifact, List<String> versions) {
        public String moduleKey() {
            return group + ":" + artifact;
        }
    }

    /**
     * Every {@code group:artifact} tracked by this store, with the versions held for each. Powers
     * offline cache search ({@code jk cache search}, {@code jk library search --offline}). Empty
     * for {@link #NONE} or a cold store. Versions are in directory-listing order — callers wanting
     * newest-first should sort.
     */
    public List<Module> modules() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        // Group tracked files by their version directory (the file's parent).
        Map<Path, Boolean> versionDirs = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> (m2Root != null) == p.getFileName().toString().endsWith(".sha256"))
                    .forEach(p -> versionDirs.put(p.getParent(), Boolean.TRUE));
        } catch (IOException e) {
            return List.of();
        }
        Map<String, List<String>> versionsByModule = new TreeMap<>();
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
     * Names of every per-repo store under {@code <cacheRoot>/repos/} — the repos jk has fetched
     * through so far. Empty for a cold cache.
     */
    public static List<String> repoNames(Path cacheRoot) {
        Path reposDir = cacheRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return List.of();
        try (Stream<Path> entries = Files.list(reposDir)) {
            return entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every {@code group:artifact} cached under any named repo in {@code cacheRoot}, merged by
     * module key — the repo-agnostic view {@code jk cache search} and {@code jk library search
     * --offline} want, since neither is scoped to one particular declared repository.
     */
    public static List<Module> allModules(Path cacheRoot) {
        Map<String, String[]> ga = new LinkedHashMap<>();
        Map<String, Set<String>> versionsByModule = new LinkedHashMap<>();
        for (String repoName : repoNames(cacheRoot)) {
            for (Module m : forRepoName(cacheRoot, repoName).modules()) {
                ga.putIfAbsent(m.moduleKey(), new String[] {m.group(), m.artifact()});
                versionsByModule
                        .computeIfAbsent(m.moduleKey(), k -> new LinkedHashSet<>())
                        .addAll(m.versions());
            }
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByModule.entrySet()) {
            String[] parts = ga.get(e.getKey());
            out.add(new Module(parts[0], parts[1], List.copyOf(e.getValue())));
        }
        return out;
    }

    /**
     * Versions of {@code group:artifact} cached under any named repo in {@code cacheRoot}, merged
     * and deduplicated — the repo-agnostic counterpart to {@link #versions(String, String)}.
     */
    public static List<String> allVersions(Path cacheRoot, String group, String artifact) {
        Set<String> out = new LinkedHashSet<>();
        for (String repoName : repoNames(cacheRoot)) {
            out.addAll(forRepoName(cacheRoot, repoName).versions(group, artifact));
        }
        return List.copyOf(out);
    }

    /** All relative m2 paths stored (non-sidecar files for full store). Empty for NONE. */
    public List<String> allRelativePaths() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<String> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".sha256"))
                    .forEach(p -> result.add(root.relativize(p).toString()));
        } catch (IOException ignored) {
        }
        return result;
    }

    /**
     * Remove entries whose sidecar hash matches one of {@code shas}. For index-only stores only
     * the sidecar is removed (the {@code ~/.m2} JAR is left intact — jk doesn't GC Maven's store).
     * For full stores, both the sidecar and the artifact file are removed. Returns count removed.
     * Never throws.
     */
    public int removeShas(Set<String> shas, boolean dryRun) {
        if (root == null || shas.isEmpty() || !Files.isDirectory(root)) return 0;
        int removed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path sidecar : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".sha256"))::iterator) {
                try {
                    String hash = Files.readString(sidecar).strip();
                    if (!shas.contains(hash)) continue;
                    if (!dryRun) {
                        Files.deleteIfExists(sidecar);
                        if (m2Root == null) {
                            // Full store: also delete the artifact file
                            String sp = sidecar.toString();
                            Files.deleteIfExists(Path.of(sp.substring(0, sp.length() - ".sha256".length())));
                        }
                        pruneEmptyParents(sidecar.getParent());
                    }
                    removed++;
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return removed;
    }

    /** The root directory ({@code <cache>/repos/<name>}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    // -------------------------------------------------------------------------

    private Path artifactPath(String relativePath) {
        return m2Root != null ? m2Root.resolve(relativePath) : root.resolve(relativePath);
    }

    private Path sidecarPath(String relativePath) {
        return root.resolve(relativePath + ".sha256");
    }

    private boolean hasTrackedFile(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return false;
        try (Stream<Path> entries = Files.list(versionDir)) {
            // Index-only: a .sha256 sidecar represents a tracked artifact
            // Full store: look for non-sidecar files
            return entries.anyMatch(p -> Files.isRegularFile(p)
                    && (m2Root != null) == p.getFileName().toString().endsWith(".sha256"));
        } catch (IOException e) {
            return false;
        }
    }

    private void pruneEmptyParents(Path dir) {
        Path cur = dir;
        while (cur != null && root != null && cur.startsWith(root) && !cur.equals(root)) {
            try {
                Files.delete(cur);
            } catch (IOException stop) {
                return;
            }
            cur = cur.getParent();
        }
    }
}
