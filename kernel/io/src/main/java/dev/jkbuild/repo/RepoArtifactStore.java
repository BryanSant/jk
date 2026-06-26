// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.cache.Linking;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Per-named-repository artifact store under {@code <cache>/repos/<name>/}.
 *
 * <p>Stores Maven artifacts in the standard m2 layout ({@code
 * <group-as-dirs>/<artifact>/<version>/<artifact>-<version>.<ext>}) and a tiny {@code .sha256}
 * sidecar alongside each artifact. The sidecar contains the 64-char hex SHA-256 of the artifact
 * and serves as both a completion marker and an integrity token:
 *
 * <ul>
 *   <li>Its <em>existence</em> is the single O(1) test for "do we have this artifact?" — a stat
 *       call, no content read.
 *   <li>Its <em>content</em> is the hash, enabling verification without re-hashing the JAR.
 *   <li>It is written <em>last</em>, after the artifact is fully on disk, so a partial download
 *       (crash mid-stream) never leaves a sidecar behind.
 * </ul>
 *
 * <p>Unlike the CAS ({@code sha256/AB/CD/…}), every path here is human-readable and m2-compatible,
 * making the entire {@code repos/} tree a valid exportable Maven repository. JVM classpath entries
 * produced from this store show real artifact names rather than opaque hashes.
 *
 * <p>Artifacts are stored as hard links to the CAS blob (same inode, two paths — no extra disk
 * usage), with the {@link Linking} fallback to a byte copy on Windows / cross-filesystem builds.
 *
 * <p>All write methods are best-effort and swallow IO errors — a materialisation failure must never
 * fail a successful network fetch.
 */
public final class RepoArtifactStore {

    /** No-op store for callers that don't participate in per-repo storage. */
    public static final RepoArtifactStore NONE = new RepoArtifactStore();

    /** Root of this repo's tree, or {@code null} for {@link #NONE}. */
    private final Path root;

    private RepoArtifactStore() {
        this.root = null;
    }

    /**
     * @param cacheRoot the jk cache directory (e.g. {@code ~/.jk/cache})
     * @param repoName  the repository short name (e.g. {@code "central"}, {@code "google"})
     */
    public RepoArtifactStore(Path cacheRoot, String repoName) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(repoName, "repoName");
        this.root = cacheRoot.resolve("repos").resolve(repoName);
    }

    /**
     * True when the artifact at {@code relativePath} (e.g.
     * {@code org/apache/commons/commons-io/2.2/commons-io-2.2.jar}) has been fully stored — i.e.
     * its {@code .sha256} sidecar exists. This is an O(1) stat; no content is read.
     */
    public boolean contains(String relativePath) {
        if (root == null) return false;
        return Files.exists(sidecarPath(relativePath));
    }

    /**
     * The stored artifact path if it has been fully materialised (sidecar present), else empty.
     * The returned path is the JAR/POM itself, not the sidecar.
     */
    public Optional<Path> locate(String relativePath) {
        if (root == null) return Optional.empty();
        if (!Files.isRegularFile(sidecarPath(relativePath))) return Optional.empty();
        Path artifact = root.resolve(relativePath);
        return Files.isRegularFile(artifact) ? Optional.of(artifact) : Optional.empty();
    }

    /**
     * Hard-link {@code casBlob} to the m2 path for {@code relativePath} and write the {@code .sha256}
     * sidecar. The sidecar is written last so its existence is a reliable "fully stored" signal.
     * Idempotent — if the sidecar is already present the call is a no-op. Never throws.
     */
    public void materialize(String relativePath, Path casBlob, String sha256) {
        if (root == null) return;
        try {
            Path sidecar = sidecarPath(relativePath);
            if (Files.exists(sidecar)) return; // already stored
            Path artifact = root.resolve(relativePath);
            Linking.linkOrCopy(casBlob, artifact);
            Files.createDirectories(sidecar.getParent());
            // Write sidecar LAST — existence is the completion marker.
            Files.writeString(sidecar, sha256);
        } catch (IOException | RuntimeException ignored) {
            // best-effort; a fetch must not fail because mirroring did
        }
    }

    /**
     * Versions of {@code group:artifact} present in this store — the names of the version
     * directories that hold at least one artifact file. Empty (not an error) when none are stored.
     * Order is unspecified; callers that need newest-first should sort.
     */
    public List<String> versions(String group, String artifact) {
        if (root == null) return List.of();
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(RepoArtifactStore::hasArtifactFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every stored artifact in this repo as a flat list of relative m2 paths (e.g.
     * {@code org/apache/commons/commons-io/2.2/commons-io-2.2.jar}). Powers {@code jk cache info}
     * and other introspection commands. Empty for {@link #NONE} or a cold store.
     */
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

    /** The root directory of this store ({@code <cache>/repos/<name>}), or {@code null} for {@link #NONE}. */
    public Path root() {
        return root;
    }

    // -------------------------------------------------------------------------

    private Path sidecarPath(String relativePath) {
        return root.resolve(relativePath + ".sha256");
    }

    private static boolean hasArtifactFile(Path versionDir) {
        if (!Files.isDirectory(versionDir)) return false;
        try (Stream<Path> entries = Files.list(versionDir)) {
            return entries.anyMatch(
                    p -> Files.isRegularFile(p) && !p.getFileName().toString().endsWith(".sha256"));
        } catch (IOException e) {
            return false;
        }
    }
}
