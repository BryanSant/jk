// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A jk cache directory shared across the integration-test suite (and persisted between runs under
 * {@code build/}). The handful of real dependencies these end-to-end tests resolve — the Kotlin
 * compiler, JUnit, … — are then fetched from Maven Central <em>once</em> instead of being
 * re-downloaded into a throwaway {@code @TempDir} cache per test, which is what was rate-limiting
 * the suite (HTTP 429). Combined with {@code MavenMetadataCache}, repeat runs read the metadata
 * index from disk too, so a warm cache makes no network requests at all.
 *
 * <p><b>Use only for project-local assertions</b> (build succeeds, output jar exists, run forwards
 * args, …). Tests that assert on cache <em>contents</em> or on incremental-build state — {@code
 * BuildCacheTest}, {@code CacheCommandTest}, the install-cache tests, the kstamp recompile tests —
 * must keep an isolated per-test {@code @TempDir} cache so a sibling test's entries can't perturb
 * them. The shared cache deliberately carries the (content/coordinate-addressed, concurrency-safe)
 * download layer; sharing it across project-local builds is safe because their inputs differ, and
 * where they don't, a restored output is still correct.
 *
 * <p>Location: {@code -Djk.test.cache.dir} (set by the Gradle test task to {@code
 * <module>/build/test-shared-cache}); falls back to {@code build/...} under the working directory
 * when run outside Gradle.
 */
public final class SharedTestCache {

    private SharedTestCache() {}

    private static final Path DIR = resolve();

    /** The shared cache directory (created on first access). */
    public static Path dir() {
        return DIR;
    }

    /** The shared cache directory as the string {@code --cache-dir} expects. */
    public static String arg() {
        return DIR.toString();
    }

    private static Path resolve() {
        String override = System.getProperty("jk.test.cache.dir");
        Path dir = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(System.getProperty("user.dir"), "build", "test-shared-cache");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("creating shared test cache " + dir, e);
        }
        return dir;
    }
}
