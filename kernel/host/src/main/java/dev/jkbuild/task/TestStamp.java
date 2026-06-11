// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Incremental test-skipping via a content-addressed stamp file.
 *
 * <p>After a clean test run, {@link #write} records a hash of all test
 * inputs. Before the next run, {@link #isFresh} recomputes the hash and
 * compares — if unchanged, the test runner is skipped entirely.
 *
 * <h3>Inputs to the key</h3>
 * <ul>
 *   <li><b>Test sources</b> — path + content hash.  Editing or adding a
 *       test file busts the stamp.</li>
 *   <li><b>{@code jk.lock}</b> — content hash.  Any change to the
 *       resolved dependency set busts the stamp even if the JARs on disk
 *       are momentarily the same (e.g. a version was yanked and re-uploaded
 *       with different bytes).</li>
 *   <li><b>Runtime classpath</b> — for CAS-stored JARs the path is
 *       sufficient (the SHA-256 is encoded in the directory layout).  For
 *       workspace-local JARs (sibling members, non-CAS paths) the file
 *       size + mtime provide a fast staleness signal.  This deliberately
 *       matches the two-tier strategy used by {@link FreshnessStamp} for
 *       compilation: cheap stat-based checks for the common case, with the
 *       caveat that a mtime-preserving swap could fool this layer.  The
 *       workspace build ensures sibling JARs are rebuilt with new mtimes
 *       whenever their sources change, so this is not a practical concern.</li>
 * </ul>
 *
 * <h3>Safety invariant</h3>
 * Any exception (unreadable source, missing lock, I/O error) causes
 * {@link #computeKey} to return {@code null}, which makes {@link #isFresh}
 * return {@code false} — the test runner always runs when the key cannot be
 * verified.
 */
public final class TestStamp {

    /** Name of the stamp file written into the test-classes directory. */
    public static final String FILE = ".test-stamp";

    /** Prefix embedded in every stamp so future format changes can be detected. */
    private static final String FORMAT_VERSION = "test-stamp-v1";

    private TestStamp() {}

    // -----------------------------------------------------------------------
    // Public API

    /**
     * Return {@code true} when the tests produced by a previous run are still
     * valid for the current inputs — i.e. none of the test sources, the lock
     * file, or any runtime-classpath entry have changed since the stamp was
     * written.
     *
     * <p>Returns {@code false} conservatively whenever the stamp is missing,
     * unreadable, or the key cannot be computed.
     */
    public static boolean isFresh(Path testClassesDir, String currentKey) {
        if (currentKey == null) return false;
        Path stamp = testClassesDir.resolve(FILE);
        if (!Files.isRegularFile(stamp)) return false;
        try {
            return currentKey.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Write the stamp for a successful test run.  Silently does nothing on
     * I/O failure — the worst outcome is that the next build re-runs the tests.
     */
    public static void write(Path testClassesDir, String key) {
        if (key == null) return;
        try {
            Files.createDirectories(testClassesDir);
            Files.writeString(testClassesDir.resolve(FILE),
                    key + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    /**
     * Compute the combined key for all test inputs.  Returns {@code null} if
     * any required input is unreadable — callers must treat {@code null} as
     * "not fresh" and run the tests.
     *
     * @param testSources    all {@code .java}/{@code .kt} test source files
     * @param lockFile       the project's {@code jk.lock}
     * @param runtimeCp      the full test runtime classpath (external jars +
     *                       workspace sibling jars, already assembled)
     */
    public static String computeKey(
            List<Path> testSources,
            Path lockFile,
            List<Path> runtimeCp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, FORMAT_VERSION);

            // Test sources: path + content hash, sorted for stability.
            List<Path> sortedSources = new ArrayList<>(testSources);
            sortedSources.sort(Comparator.comparing(Path::toString));
            for (Path src : sortedSources) {
                if (!Files.isRegularFile(src)) continue; // generated / deleted
                feed(md, "src:" + src.toAbsolutePath().normalize()
                        + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
            }

            // Lock file: content hash — catches any dep version change.
            if (Files.isRegularFile(lockFile)) {
                feed(md, "lock:" + Hashing.sha256Hex(Files.readAllBytes(lockFile)));
            }

            // Runtime classpath entries, sorted for stability.
            List<Path> sortedCp = new ArrayList<>(runtimeCp);
            sortedCp.sort(Comparator.comparing(Path::toString));
            for (Path entry : sortedCp) {
                if (!Files.exists(entry)) return null; // missing dep → retest
                String abs = entry.toAbsolutePath().normalize().toString();
                if (isCasPath(abs)) {
                    // CAS layout: sha256/AA/BB/<rest> — the path IS the content hash.
                    feed(md, "cp-cas:" + abs);
                } else {
                    // Workspace sibling / local build output: use size + mtime.
                    // Cheap O(1) check; accurate because the workspace build always
                    // touches the output JAR when sources or deps change.
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    feed(md, "cp-local:" + abs
                            + ":" + attrs.size()
                            + ":" + attrs.lastModifiedTime().toMillis());
                }
            }

            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null; // fail open — retest
        }
    }

    // -----------------------------------------------------------------------
    // Helpers

    private static void feed(MessageDigest md, String value) {
        byte[] bytes = (value + "\n").getBytes(StandardCharsets.UTF_8);
        md.update(bytes);
    }

    /**
     * Heuristic: a path under {@code sha256/XX/YY/<rest>} is a CAS entry
     * whose content hash is encoded in the directory structure.
     */
    private static boolean isCasPath(String path) {
        return path.contains("/sha256/") || path.contains("\\sha256\\");
    }
}
