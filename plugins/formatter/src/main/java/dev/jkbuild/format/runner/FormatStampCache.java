// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.format.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal file-based stamp store for per-file format results. Stores a tiny marker file at a path
 * derived from a 64-hex-char SHA-256 key, using the same two-level directory sharding as the CAS
 * ({@code <root>/AB/CD/<rest>}) so the store degrades gracefully at scale.
 *
 * <p>A stamp means: "the file whose content produced this key is already clean under the formatter
 * config embedded in the key." On a hit the formatter can skip the file entirely — no OpenRewrite
 * parse, no Spotless diff.
 *
 * <p>The store lives under {@code <cache>/format-stamps/}, which is not touched by {@code jk clean}
 * (clean only wipes {@code target/}). Stamps are invalidated automatically when file content or
 * formatter config changes (different key).
 *
 * <p>All methods are fail-open: I/O errors return false / are silently swallowed so a corrupt or
 * unavailable cache never breaks formatting.
 */
final class FormatStampCache {

    private final Path root;

    FormatStampCache(Path root) {
        this.root = root;
    }

    /** True when a valid stamp exists for {@code key}; false on any I/O error. */
    boolean contains(String key) {
        try {
            return Files.exists(stampPath(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Record a stamp for {@code key}. Creates parent dirs as needed. Silently ignores I/O errors —
     * advisory cache, never critical.
     */
    void record(String key) {
        try {
            Path p = stampPath(key);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) Files.writeString(p, "");
        } catch (IOException ignored) {
        }
    }

    private Path stampPath(String hex64) {
        return root.resolve(hex64.substring(0, 2))
                .resolve(hex64.substring(2, 4))
                .resolve(hex64.substring(4));
    }
}
