// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Append-only side-table mapping SHA-256 hex → Maven coordinate
 * ({@code group:artifact:version}). Lives at
 * {@code ~/.jk/cache/.artifact.map}.
 *
 * <p>Written by {@link dev.jkbuild.compile.ClasspathResolver} whenever
 * it resolves a checksummed dep from the lockfile. Read by reporting
 * and eviction tooling to surface human-readable names alongside bare
 * hashes.
 *
 * <p>Format (text, append-only):
 * <pre>{@code
 *   <sha256-hex>\t<group:artifact:version>
 * }</pre>
 *
 * <p>Entries are immutable: a given SHA-256 always maps to the same
 * coordinate. Duplicate lines accumulate across builds; the compaction
 * step collapses them to one line per hex.
 *
 * <p>Best-effort: every write method swallows IO errors.
 */
public final class ArtifactMap {

    static final String FILE_NAME = ".artifact.map";
    private static final long COMPACT_THRESHOLD_BYTES = 1L * 1024 * 1024; // 1 MiB

    private final Path file;

    /** Default-path constructor — writes to {@code ~/.jk/cache/.artifact.map}. */
    public static ArtifactMap atDefaultPath() {
        return new ArtifactMap(JkDirs.cache().resolve(FILE_NAME));
    }

    public ArtifactMap(Path file) {
        this.file = file;
    }

    /**
     * Record that {@code hex} corresponds to {@code coordinate}
     * ({@code group:artifact:version}). Appends a line; no exception leaks.
     */
    public void put(String hex, String coordinate) {
        if (hex == null || hex.isBlank()) return;
        if (coordinate == null || coordinate.isBlank()) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, hex + "\t" + coordinate + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Best-effort.
        }
    }

    /** Load the entire map into memory. Duplicate lines are silently deduplicated. */
    public Map<String, String> toMap() throws IOException {
        Map<String, String> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            String hex = line.substring(0, tab).trim();
            String coord = line.substring(tab + 1).trim();
            if (!hex.isEmpty() && !coord.isEmpty()) out.put(hex, coord);
        }
        return out;
    }

    /**
     * Rewrite as one line per hex. No-op below {@link #COMPACT_THRESHOLD_BYTES}.
     * Returns the new byte size.
     */
    public long compactIfLarge() throws IOException {
        if (!Files.isRegularFile(file)) return 0;
        if (Files.size(file) < COMPACT_THRESHOLD_BYTES) return Files.size(file);
        Map<String, String> map = toMap();
        StringBuilder sb = new StringBuilder();
        map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n'));
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return Files.size(file);
    }
}
