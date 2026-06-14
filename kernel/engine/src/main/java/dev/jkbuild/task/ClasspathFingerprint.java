// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Content fingerprint of build artifacts, for cache keys that must invalidate
 * when a dependency's <em>content</em> changes — not its path and not its mtime.
 *
 * <p>Two-tier rule:
 * <ul>
 *   <li>A CAS blob (path under {@code .../sha256/AA/BB/<rest>}) already encodes
 *       its content hash in the path, so the path is the fingerprint.</li>
 *   <li>A workspace-local output is hashed by its <em>logical</em> content — a
 *       jar by the sorted {@code (entry name + entry SHA)} of its members, a
 *       directory by the sorted {@code (relpath + file SHA)} of its files. We
 *       hash logical content, not the raw jar bytes, because jk does not build
 *       byte-reproducible jars: a no-op rebuild re-jars the same classes in a
 *       different entry order / with fresh timestamps, so the raw bytes (and
 *       mtime) change every build while the contents do not. Hashing the bytes
 *       would bust every dependent's key on every build; hashing the contents
 *       ripples a real code change in and ignores the packaging churn.</li>
 * </ul>
 *
 * <p>A missing entry yields a distinct {@code "missing:"} token, so a vanished
 * dependency changes the key (forcing a rebuild/retest) rather than being
 * silently ignored.
 */
public final class ClasspathFingerprint {

    private ClasspathFingerprint() {}

    /** Order-independent content fingerprint of a list of classpath entries. */
    public static String of(List<Path> entries) throws IOException {
        List<String> parts = new ArrayList<>(entries.size());
        for (Path p : entries) parts.add(entry(p));
        parts.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", parts));
    }

    /** Content identity of a single entry (CAS blob, jar, classes dir, or missing). */
    public static String entry(Path p) throws IOException {
        String abs = p.toAbsolutePath().normalize().toString();
        if (isCasPath(abs)) return "cas:" + abs;                 // path encodes content hash
        if (Files.isDirectory(p)) return "dir:" + hashTree(p);
        if (Files.isRegularFile(p)) {
            if (isArchive(abs)) {
                String logical = hashArchive(p);
                if (logical != null) return "jar:" + logical;    // logical content, not raw bytes
            }
            return "file:" + Hashing.sha256Hex(p);
        }
        return "missing:" + abs;
    }

    private static boolean isArchive(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    /**
     * Logical content hash of a zip/jar: sorted {@code (entry name + entry SHA)},
     * ignoring entry order, timestamps, and compression — the packaging that jk
     * does not produce reproducibly. Returns {@code null} if {@code jar} isn't a
     * valid archive, so the caller falls back to a raw-byte hash.
     */
    private static String hashArchive(Path jar) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || isBuildMetadata(baseName(e.getName()))) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    entries.add(e.getName() + ":" + Hashing.sha256Hex(in.readAllBytes()));
                }
            }
        } catch (ZipException ze) {
            return null; // not a valid zip — caller hashes raw bytes
        }
        entries.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", entries));
    }

    /** Stable hash of a directory tree: each regular file's relpath + content SHA. */
    private static String hashTree(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f)) continue;
                if (isBuildMetadata(f.getFileName().toString())) continue;
                files.add(dir.relativize(f).toString().replace('\\', '/')
                        + ":" + Hashing.sha256Hex(f));
            }
        }
        files.sort(Comparator.naturalOrder());
        return Hashing.sha256Hex(String.join("\n", files));
    }

    /**
     * jk's own build-host metadata that lives inside the output tree but is not
     * code, and whose content changes every build, so it must be excluded from a
     * content fingerprint:
     * <ul>
     *   <li>the freshness/skip stamps ({@code .jstamp}, {@code .kstamp},
     *       {@code .test-stamp}) — mirrors {@code JarPackager} dropping them;</li>
     *   <li>{@code [build.embed-sha]} outputs ({@code META-INF/jk-<worker>-sha256.txt})
     *       — these hold the raw-byte SHA of worker jars, which churn because jk's
     *       jars aren't byte-reproducible. The worker's actual identity is captured
     *       elsewhere (the {@code test-worker-jars} extras hash the worker's logical
     *       content), so dropping the embedded value here loses no real signal.</li>
     * </ul>
     */
    private static boolean isBuildMetadata(String name) {
        return name.equals(FreshnessStamp.JAVA_STAMP)
                || name.equals(FreshnessStamp.KOTLIN_STAMP)
                || name.equals(TestStamp.FILE)
                || (name.startsWith("jk-") && name.endsWith("-sha256.txt"));
    }

    private static String baseName(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    /** A path under {@code .../sha256/AA/BB/<rest>} is a CAS blob (path = content). */
    private static boolean isCasPath(String path) {
        return path.contains("/sha256/") || path.contains("\\sha256\\");
    }
}
