// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.repo;

import build.jumpkick.cache.Linking;
import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Per-named-repository artifact index and store.
 *
 * <h3>Full store, every repo</h3>
 * Both the artifact and its {@code .sha256} sidecar live under {@code <cache>/repos/<name>/} —
 * jk's own tree, never {@code ~/.m2}. Fetched artifacts are hard-linked in from the CAS via {@link
 * #materialize}; nothing outside jk writes here, so there's no external mutation to guard against.
 * (Separately, a project may opt into also mirroring artifacts to {@code ~/.m2} for Maven/Gradle
 * interop — see {@code project.m2install} — but that mirror is not this store.)
 *
 * <h3>Sidecar invariant</h3>
 * The sidecar is written <em>last</em>, after the artifact is fully on disk. Its existence is the
 * single O(1) "fully stored" signal — a partial download never leaves a sidecar behind. Its
 * content is the 64-char SHA-256 hex string.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore((Path) null);

    private final Path root; // <cache>/repos/<name>/

    private RepoArtifactStore(Path root) {
        this.root = root;
    }

    /** Artifact and sidecar both live under {@code repos/<repoName>/}. */
    public RepoArtifactStore(Path cacheRoot, String repoName) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(repoName, "repoName");
        this.root = cacheRoot.resolve("repos").resolve(repoName);
    }

    /** Factory: the full store for {@code repoName} under {@code cacheRoot}. */
    public static RepoArtifactStore forRepoName(Path cacheRoot, String repoName) {
        return new RepoArtifactStore(cacheRoot, repoName);
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
     * Verification state of a stored artifact against an expected (lockfile-pinned) hash — see
     * {@link #verify}.
     */
    public enum IndexState {
        /** Sidecar or artifact file missing — never (fully) stored. */
        ABSENT,
        /** Present, and the stored hash equals the expected hash. */
        VERIFIED,
        /** Present, but the stored hash differs — the artifact was overwritten or corrupted. */
        MISMATCH
    }

    /**
     * Verify the artifact at {@code relativePath} against {@code expectedSha256} (the hash the
     * lockfile pinned). {@code repos/<name>/} is exclusively jk-owned, so drift here would mean
     * local corruption or manual tampering rather than an external tool's rewrite — still worth
     * catching cheaply, hence this check stays, but it's a defensive backstop now rather than the
     * primary integrity mechanism it was when the artifact lived in a tool-shared {@code ~/.m2}.
     */
    public IndexState verify(String relativePath, String expectedSha256) {
        if (locate(relativePath).isEmpty()) return IndexState.ABSENT;
        try {
            String stored = Files.readString(sidecarPath(relativePath)).strip();
            return stored.equals(expectedSha256) ? IndexState.VERIFIED : IndexState.MISMATCH;
        } catch (IOException unreadable) {
            return IndexState.MISMATCH;
        }
    }

    /**
     * As {@link #locate(String)} but hash-verified: resolves only when {@link #verify} says the
     * stored hash matches {@code expectedSha256}. A mismatching artifact is treated as absent so
     * callers fall back to the CAS blob (whose path <em>is</em> its content) or re-fetch, rather
     * than compile against bytes the lockfile never pinned.
     */
    public Optional<Path> locate(String relativePath, String expectedSha256) {
        return verify(relativePath, expectedSha256) == IndexState.VERIFIED
                ? locate(relativePath)
                : Optional.empty();
    }

    /**
     * The stored artifact path if fully materialised (sidecar and artifact file both present),
     * else empty.
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        Path sidecar = sidecarPath(relativePath);
        if (!Files.isRegularFile(sidecar)) return Optional.empty();
        Path artifact = artifactPath(relativePath);
        if (!Files.isRegularFile(artifact)) return Optional.empty();
        return Optional.of(artifact);
    }

    // -------------------------------------------------------------------------
    // Write paths
    // -------------------------------------------------------------------------

    // Note: there is deliberately no sidecar-only write here. Every repo is a full store now — a
    // sidecar without a backing artifact file is never a state this store intentionally creates.
    // See materialize() below.

    /**
     * Materialise a fetched artifact: copy {@code casBlob} into {@code repos/<name>/} and write
     * its {@code .sha256} sidecar. Used for every repo now (not just {@code local}). Idempotent;
     * best-effort (the CAS blob remains the source of truth), but never torn: the artifact lands
     * via temp + atomic move and the sidecar is written only after the artifact is complete, so
     * "sidecar present" always implies "whole artifact" — a crash mid-copy leaves at most a
     * {@code .part} file that the next call replaces.
     */
    public void materialize(String relativePath, Path casBlob, String sha256) {
        if (root == null) return;
        Path artifact = root.resolve(relativePath);
        Path tmp = artifact.resolveSibling(artifact.getFileName() + ".part");
        try {
            Path sidecar = sidecarPath(relativePath);
            // Complete only when both halves exist (a sidecar alone — e.g. after a pre-atomicity
            // crash — is repaired by re-materializing, not trusted).
            if (Files.exists(sidecar) && Files.isRegularFile(artifact)) return;
            // COPY, never link: installLocal/maven overwrite repo files in place; a link
            // would let that overwrite mutate the CAS blob (see Cas.putFile).
            Files.createDirectories(artifact.getParent());
            Files.copy(casBlob, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(
                    tmp,
                    artifact,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, sha256);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
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
                    .filter(p -> !p.getFileName().toString().endsWith(".sha256"))
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
     * Remove entries whose sidecar hash matches one of {@code shas} — both the sidecar and the
     * artifact file. Never touches an opt-in {@code ~/.m2} mirror (jk doesn't GC Maven's store; see
     * {@code project.m2install}). Returns count removed. Never throws.
     */
    /**
     * Remove entries hashing to {@code shas} from EVERY named repo store under
     * {@code <cacheRoot>/repos/} — the shared tail of cache GC / sweep / LRU eviction, keeping
     * the repo trees in lock-step with the CAS. Best-effort; returns entries removed.
     */
    public static int removeShasFromAll(Path cacheRoot, Set<String> shas, boolean dryRun) {
        if (shas.isEmpty()) return 0;
        Path reposDir = cacheRoot.resolve("repos");
        if (!Files.isDirectory(reposDir)) return 0;
        int removed = 0;
        try (Stream<Path> named = Files.list(reposDir)) {
            for (Path nameDir : (Iterable<Path>) named::iterator) {
                if (!Files.isDirectory(nameDir)) continue;
                removed += new RepoArtifactStore(cacheRoot, nameDir.getFileName().toString())
                        .removeShas(shas, dryRun);
            }
        } catch (IOException ignored) {
            // best-effort
        }
        return removed;
    }

    public int removeShas(Set<String> shas, boolean dryRun) {
        if (root == null || shas.isEmpty() || !Files.isDirectory(root)) return 0;
        // Collect BEFORE deleting: pruning directories under a still-lazy Files.walk iterator
        // throws NoSuchFileException from the stream.
        java.util.List<Path> sidecars;
        try (Stream<Path> walk = Files.walk(root)) {
            sidecars = walk.filter(p -> p.toString().endsWith(".sha256")).toList();
        } catch (IOException e) {
            return 0;
        }
        int removed = 0;
        for (Path sidecar : sidecars) {
            try {
                String hash = Files.readString(sidecar).strip();
                if (!shas.contains(hash)) continue;
                if (!dryRun) {
                    Files.deleteIfExists(sidecar);
                    // Every store is a full store now: also delete the artifact file.
                    Files.deleteIfExists(sidecar.resolveSibling(
                            sidecar.getFileName().toString().replaceFirst("\\.sha256$", "")));
                    pruneEmptyParents(sidecar.getParent());
                }
                removed++;
            } catch (IOException ignored) {
            }
        }
        return removed;
    }

    /** The root directory ({@code <cache>/repos/<name>}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    // -------------------------------------------------------------------------

    private Path artifactPath(String relativePath) {
        return root.resolve(relativePath);
    }

    private Path sidecarPath(String relativePath) {
        return root.resolve(relativePath + ".sha256");
    }

    private boolean hasTrackedFile(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return false;
        try (Stream<Path> entries = Files.list(versionDir)) {
            return entries.anyMatch(
                    p -> Files.isRegularFile(p) && !p.getFileName().toString().endsWith(".sha256"));
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
    /**
     * Write a file directly into {@code repos/local/} as a full-store entry (actual JAR on disk) —
     * the local-install write path shared by the engine's install pipeline and the client's
     * {@code jk install <file.jar>} mode (a local, content-addressed write, like {@code
     * Cas.putByLink} — no network).
     */
    public static void writeToLocalStore(Path cacheDir, String relativePath, Path source) throws IOException {
        Path target = cacheDir.resolve("repos/local/" + relativePath);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(source, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(
                tmp,
                target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(Path.of(target + ".sha256"), Hashing.sha256Hex(target));
    }
}
