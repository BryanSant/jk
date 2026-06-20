// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li><b>Own main output</b> — content of the module's {@code MAIN_CLASSES}
 *       tree.  The tests exercise this code, so a main-only change must retest
 *       even when no test source changed.</li>
 *   <li><b>{@code jk.lock}</b> — content hash (resolved dep set + JDK).</li>
 *   <li><b>Runtime classpath</b> — by <em>content</em> via
 *       {@link ClasspathFingerprint}: CAS jars by their hash-path, workspace-local
 *       outputs (sibling members) by their bytes.  Content (not mtime) is what
 *       makes a sibling member's change ripple into every dependent's key, and
 *       what stops a byte-identical rebuild — jk re-jars every build with a new
 *       file mtime but stable bytes — from needlessly busting the stamp.</li>
 *   <li><b>Toolchain / runner / forked-worker identity</b> — caller-supplied
 *       tokens (jk version, {@code jk-test-runner} sha, the content of any worker
 *       jars handed to the test JVM) so a tool or worker change retests.</li>
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
    private static final String FORMAT_VERSION = "test-stamp-v2";

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
            Path stamp = testClassesDir.resolve(FILE);
            // Delete first so we never truncate-in-place a file that an earlier
            // build may have hard-linked into the CAS — truncating would mutate
            // the shared blob. A fresh write always gets its own inode.
            Files.deleteIfExists(stamp);
            Files.writeString(stamp, key + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    /**
     * Compute the combined key for all test inputs.  Returns {@code null} if
     * any required input is unreadable — callers must treat {@code null} as
     * "not fresh" and run the tests.
     *
     * @param testSources    all {@code .java}/{@code .kt} test source files
     * @param mainClasses    the module's own compiled main output dir (may be null)
     * @param lockFile       the project's {@code jk.lock}
     * @param runtimeCp      the full test runtime classpath (external jars +
     *                       workspace sibling jars, already assembled)
     * @param extraInputs    toolchain / runner / forked-worker identity tokens
     */
    public static String computeKey(
            List<Path> testSources,
            Path mainClasses,
            Path lockFile,
            List<Path> runtimeCp,
            List<String> extraInputs) {
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

            // The module's own compiled main output — a main-only change busts the
            // stamp even when no test source changed (the tests exercise this code).
            if (mainClasses != null) {
                feed(md, "main:" + ClasspathFingerprint.entry(mainClasses));
            }

            // Lock file: content hash — catches any dep version / JDK change.
            if (Files.isRegularFile(lockFile)) {
                feed(md, "lock:" + Hashing.sha256Hex(Files.readAllBytes(lockFile)));
            }

            // Runtime classpath by CONTENT: a sibling member's change ripples in,
            // and a byte-identical rebuild (new mtime, same bytes) does not.
            feed(md, "cp:" + ClasspathFingerprint.of(runtimeCp));

            // Toolchain / runner / forked-worker identity, sorted for stability.
            if (extraInputs != null) {
                List<String> extras = new ArrayList<>(extraInputs);
                extras.sort(Comparator.naturalOrder());
                for (String e : extras) feed(md, "x:" + e);
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
}
