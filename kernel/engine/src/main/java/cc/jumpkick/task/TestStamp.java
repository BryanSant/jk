// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Content key for incremental test-skipping. {@link #computeKey} hashes every input that affects
 * the test outcome; the build stores a CAS action-cache marker under that key on a green run and
 * skips the runner when a later build recomputes the same key. The "tests passed" signal therefore
 * lives in the CAS — surviving {@code jk clean} (which only wipes {@code target/}) the same way the
 * compile cache does — rather than in a file under {@code target/}.
 *
 * <h3>Inputs to the key</h3>
 *
 * <ul>
 *   <li><b>Test sources</b> — path + content hash. Editing or adding a test file busts the key.
 *   <li><b>Own main output</b> — content of the module's {@code MAIN_CLASSES} tree. The tests
 *       exercise this code, so a main-only change must retest even when no test source changed.
 *   <li><b>{@code jk.lock}</b> — content hash (resolved dep set + JDK).
 *   <li><b>Runtime classpath</b> — by <em>content</em> via {@link ClasspathFingerprint}: CAS jars
 *       by their hash-path, workspace-local outputs (sibling modules) by their bytes. Content (not
 *       mtime) is what makes a sibling module's change ripple into every dependent's key, and what
 *       stops a byte-identical rebuild — jk re-jars every build with a new file mtime but stable
 *       bytes — from needlessly busting the key.
 *   <li><b>Toolchain / runner / forked-worker identity</b> — caller-supplied tokens (jk version,
 *       {@code jk-test-runner} sha, the content of any worker jars handed to the test JVM) so a
 *       tool or worker change retests.
 * </ul>
 *
 * <h3>Safety invariant</h3>
 *
 * Any exception (unreadable source, missing lock, I/O error) causes {@link #computeKey} to return
 * {@code null}; callers treat {@code null} as "not cached" and run the tests.
 */
public final class TestStamp {

    /** Prefix embedded in the key so a future format change invalidates it. */
    private static final String FORMAT_VERSION = "test-stamp-v2";

    private TestStamp() {}

    /**
     * Compute the combined key for all test inputs. Returns {@code null} if any required input is
     * unreadable — callers must treat {@code null} as "not fresh" and run the tests.
     *
     * @param testSources all {@code .java}/{@code .kt} test source files
     * @param mainClasses the module's own compiled main output dir (may be null)
     * @param lockFile the project's {@code jk.lock}
     * @param runtimeCp the full test runtime classpath (external jars + workspace sibling jars,
     *     already assembled)
     * @param extraInputs toolchain / runner / forked-worker identity tokens
     */
    public static String computeKey(
            List<Path> testSources, Path mainClasses, Path lockFile, List<Path> runtimeCp, List<String> extraInputs) {
        try {
            MessageDigest md = Hashing.newSha256();
            feed(md, FORMAT_VERSION);

            // Test sources: path + content hash, sorted for stability.
            List<Path> sortedSources = new ArrayList<>(testSources);
            sortedSources.sort(Comparator.comparing(Path::toString));
            for (Path src : sortedSources) {
                if (!Files.isRegularFile(src)) continue; // generated / deleted
                feed(md, "src:" + src.toAbsolutePath().normalize() + ":" + Hashing.sha256Hex(Files.readAllBytes(src)));
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

            // Runtime classpath by CONTENT: a sibling module's change ripples in,
            // and a byte-identical rebuild (new mtime, same bytes) does not.
            feed(md, "cp:" + ClasspathFingerprint.of(runtimeCp));

            // Toolchain / runner / forked-worker identity, sorted for stability.
            if (extraInputs != null) {
                List<String> extras = new ArrayList<>(extraInputs);
                extras.sort(Comparator.naturalOrder());
                for (String e : extras) feed(md, "x:" + e);
            }

            return Hashing.hex(md.digest());
        } catch (IOException e) {
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
