// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import dev.jkbuild.model.Coordinate;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Coordinate→content-hash index over what's stored in the {@link Cas}.
 *
 * <p>The CAS is keyed by SHA-256 only, so given a Maven coordinate there's no
 * way to find the blob we hold for it — which makes offline resolution
 * impossible. The journal closes that gap: every artifact fetched into the
 * CAS is recorded here, keyed by coordinate, so we can answer "what versions
 * of {@code g:a} do I have?" and "what's the POM blob for {@code g:a:v}?"
 * without touching the network.
 *
 * <p>Layout: a pointer tree under {@code <cache>/journal/maven/}, one TOML
 * file per GAV at {@code <group-as-dirs>/<artifact>/<version>.toml}:
 *
 * <pre>
 * schema = 1
 * [[blob]]
 * kind = "pom"
 * sha256 = "abc..."
 * size = 1234
 * repo = "central"
 * url = "https://repo1.maven.org/.../foo-1.2.3.pom"
 * fetched-at = "2026-05-30T12:00:00Z"
 * </pre>
 *
 * <p>Per-GAV files give lock-free version enumeration (a directory scan) and
 * confine write contention to a single coordinate. Writes are atomic
 * (temp + {@code ATOMIC_MOVE}, mirroring {@link Cas#put}); the {@code kind}
 * carries the artifact type/classifier so {@code pom}, {@code jar}, and
 * {@code jar:sources} coexist in one file. {@code maven-metadata.xml} is
 * deliberately not journaled — it has no version key and is stale offline;
 * version enumeration comes from the directory listing instead.
 *
 * <p>Journaling is best-effort: it must never fail a successful fetch, so
 * write failures are swallowed and read failures degrade to "not present".
 * The journal lives under the cache so a cache wipe invalidates it together —
 * it is a derived index, rebuilt by fetching online.
 */
public final class Journal {

    /** No-op journal for call sites that don't participate in offline resolve. */
    public static final Journal NONE = new Journal();

    private static final int SCHEMA = 1;

    /** Root of the pointer tree, or {@code null} for {@link #NONE}. */
    private final Path root;

    /**
     * Per-GAV-file locks. {@link java.nio.channels.FileChannel#lock} is a
     * process-wide OS lock and throws {@code OverlappingFileLockException}
     * when the same JVM already holds it on a file; the speculative parallel
     * prefetch in the resolver can race two writers onto the same GAV, so we
     * serialise in-process first. (The mutation is brief and a plain
     * in-JVM lock is sufficient — jk is the sole writer of its cache tree.)
     */
    private final ConcurrentHashMap<Path, ReentrantLock> locks;

    private Journal() {
        this.root = null;
        this.locks = null;
    }

    /** @param cacheRoot the jk cache directory (e.g. {@code ~/.jk/cache}). */
    public Journal(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.root = cacheRoot.resolve("journal").resolve("maven");
        this.locks = new ConcurrentHashMap<>();
    }

    /** One recorded artifact blob for a coordinate. */
    public record Blob(String kind, String sha256, long size, String repo, String url, String fetchedAt) {}

    /** A cached module and the versions present locally. */
    public record Module(String group, String artifact, List<String> versions) {
        public String moduleKey() { return group + ":" + artifact; }
    }

    /**
     * Record that {@code coord}'s {@code kind} blob (POM, jar, …) is in the
     * CAS under {@code sha256}. Idempotent: a matching {@code kind}+{@code sha}
     * is left untouched; a differing sha for the same kind is overwritten
     * (last-writer-wins). Never throws — failures are swallowed.
     */
    public void record(Coordinate coord, String kind, String sha256, long size, String repo, String url) {
        if (root == null) return;
        Path file = gavFile(coord);
        ReentrantLock lock = locks.computeIfAbsent(file, k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Blob> blobs = readBlobs(file);
            Blob existing = blobs.get(kind);
            if (existing != null && existing.sha256().equals(sha256)) {
                return; // already recorded — no rewrite
            }
            blobs.put(kind, new Blob(kind, sha256, size, repo, url, Instant.now().toString()));
            writeAtomic(file, blobs.values());
        } catch (IOException | RuntimeException ignored) {
            // best-effort index; a fetch must not fail because journaling did
        } finally {
            lock.unlock();
        }
    }

    /** The recorded blob for {@code coord}'s {@code kind}, if any. */
    public Optional<Blob> lookup(Coordinate coord, String kind) {
        if (root == null) return Optional.empty();
        try {
            return Optional.ofNullable(readBlobs(gavFile(coord)).get(kind));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Versions of {@code group:artifact} present locally — the names of the
     * {@code <version>.toml} files in the artifact directory. Empty (not an
     * error) when nothing is journaled. Order is unspecified; callers that
     * need newest-first should sort.
     */
    public List<String> versions(String group, String artifact) {
        if (root == null) return List.of();
        Path dir = root.resolve(group.replace('.', '/')).resolve(artifact);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".toml"))
                    .map(Journal::stripToml)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Every {@code group:artifact} present in the journal, with the versions
     * held locally for each. Powers local cache search ({@code jk cache
     * search}). Empty for {@link #NONE} or a cold cache. Versions are in
     * directory order — callers that want newest-first should sort.
     */
    public List<Module> modules() {
        if (root == null || !Files.isDirectory(root)) return List.of();
        java.util.Map<Path, List<String>> versionsByArtifactDir = new java.util.TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".toml"))
                    .forEach(p -> versionsByArtifactDir
                            .computeIfAbsent(p.getParent(), k -> new ArrayList<>())
                            .add(stripToml(p.getFileName().toString())));
        } catch (IOException e) {
            return List.of();
        }
        List<Module> out = new ArrayList<>();
        for (var e : versionsByArtifactDir.entrySet()) {
            Path rel = root.relativize(e.getKey());
            int n = rel.getNameCount();
            if (n < 2) continue; // need at least one group segment + artifact
            String artifact = rel.getName(n - 1).toString();
            StringBuilder group = new StringBuilder();
            for (int i = 0; i < n - 1; i++) {
                if (i > 0) group.append('.');
                group.append(rel.getName(i));
            }
            out.add(new Module(group.toString(), artifact, List.copyOf(e.getValue())));
        }
        return out;
    }

    private static String stripToml(String fileName) {
        return fileName.substring(0, fileName.length() - ".toml".length());
    }

    private Path gavFile(Coordinate coord) {
        return root.resolve(coord.group().replace('.', '/'))
                .resolve(coord.artifact())
                .resolve(coord.version() + ".toml");
    }

    /** Parse a GAV file into a kind→blob map; empty if the file is absent. */
    private Map<String, Blob> readBlobs(Path file) throws IOException {
        Map<String, Blob> blobs = new LinkedHashMap<>();
        if (!Files.exists(file)) return blobs;
        TomlParseResult toml = Toml.parse(file);
        if (toml.hasErrors()) return blobs; // corrupt entry — treat as empty
        TomlArray array = toml.getArray("blob");
        if (array == null) return blobs;
        for (int i = 0; i < array.size(); i++) {
            TomlTable t = array.getTable(i);
            String kind = t.getString("kind");
            String sha = t.getString("sha256");
            if (kind == null || sha == null) continue;
            Long size = t.getLong("size");
            blobs.put(kind, new Blob(
                    kind, sha, size != null ? size : 0L,
                    t.getString("repo"), t.getString("url"), t.getString("fetched-at")));
        }
        return blobs;
    }

    private void writeAtomic(Path file, Iterable<Blob> blobs) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), ".journal-", ".tmp");
        try {
            Files.writeString(tmp, render(blobs), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Deterministic TOML, blobs sorted by kind (hand-rolled, per LockfileWriter). */
    private static String render(Iterable<Blob> blobs) {
        List<Blob> sorted = new ArrayList<>();
        blobs.forEach(sorted::add);
        sorted.sort((a, b) -> a.kind().compareTo(b.kind()));
        StringBuilder out = new StringBuilder(256);
        out.append("schema = ").append(SCHEMA).append('\n');
        for (Blob b : sorted) {
            out.append("\n[[blob]]\n");
            out.append("kind = ").append(quote(b.kind())).append('\n');
            out.append("sha256 = ").append(quote(b.sha256())).append('\n');
            out.append("size = ").append(b.size()).append('\n');
            if (b.repo() != null) out.append("repo = ").append(quote(b.repo())).append('\n');
            if (b.url() != null) out.append("url = ").append(quote(b.url())).append('\n');
            if (b.fetchedAt() != null) out.append("fetched-at = ").append(quote(b.fetchedAt())).append('\n');
        }
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
