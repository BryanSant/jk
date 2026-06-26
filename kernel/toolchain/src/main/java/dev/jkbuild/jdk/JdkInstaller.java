// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.http.Http;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkThreads;
import dev.jkbuild.util.PathUtil;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a {@link JdkPackage} and extracts it under the IntelliJ JDK directory (see {@link
 * IntellijJdkDir}).
 *
 * <ul>
 *   <li>{@code .tar.gz} / {@code .tgz} — {@link TarArchiveInputStream} over {@link
 *       GZIPInputStream}. POSIX permissions and symlinks preserved.
 *   <li>{@code .tar} — {@link TarArchiveInputStream} directly.
 *   <li>{@code .zip} — {@link ZipFile} (random-access; entries extract in parallel).
 *   <li>SHA-256 verified against {@link JdkPackage#sha256()} when present; mismatch aborts with no
 *       install dir left behind.
 * </ul>
 *
 * <p>The {@link JdkCatalog.Entry} path is split into {@link #download} and {@link
 * #extractInstalled} so callers can wrap each phase with their own UI (e.g. a progress bar during
 * the download, a spinner during extract). {@link #install(JdkCatalog.Entry)} stitches them
 * together for the no-progress case.
 */
public final class JdkInstaller {

    /** Local archive file produced by {@link #download}. */
    public record DownloadedArchive(Path path, long bytes) {}

    private static final int DOWNLOAD_BUFFER = 64 * 1024;

    /** Scratch dir for in-flight downloads, under the jdks root (dot-prefixed → JkProbe skips it). */
    private static final String DOWNLOAD_DIR = ".downloads";

    /** Prefix for the streamed archive temp file; recognised by the stale-download sweep. */
    private static final String DOWNLOAD_PREFIX = "jk-jdk-";

    /**
     * A partial archive older than this is treated as orphaned — left by a Ctrl-C'd download, which
     * calls {@code Runtime.halt} and so skips the finally-block cleanup in {@link #download} — and
     * removed by {@link #sweepStaleDownloads}. Comfortably longer than any realistic download so an
     * in-flight one from a concurrent run is never deleted.
     */
    private static final long STALE_DOWNLOAD_AGE_MILLIS = Duration.ofHours(6).toMillis();

    private final Http http;
    private final JdkRegistry registry;

    public JdkInstaller(Http http, JdkRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledJdk install(JdkPackage pkg) throws IOException, InterruptedException {
        String identifier = pkg.installIdentifier();
        Path target = registry.jdksRoot().resolve(identifier);
        if (Files.exists(target)) {
            return new InstalledJdk(identifier, target);
        }
        downloadAndExtractBuffered(pkg.downloadUri(), pkg.sha256(), pkg.filename(), pkg.archiveType(), target);
        return new InstalledJdk(identifier, target);
    }

    /**
     * Install a {@link JdkCatalog.Entry} from the JetBrains feed. Uses the feed's {@code
     * install_folder_name} for the directory and resolves JAVA_HOME via {@code
     * package_to_java_home_prefix} (e.g. {@code Contents/Home} on macOS) so callers always get a
     * working {@link InstalledJdk#home()}.
     */
    public InstalledJdk install(JdkCatalog.Entry entry) throws IOException, InterruptedException {
        return install(entry, b -> {});
    }

    /**
     * Same as {@link #install(JdkCatalog.Entry)} but emits cumulative bytes-read to {@code
     * onBytesRead} as the download streams. Total size is available on the entry itself ({@link
     * JdkCatalog.Entry#archiveSize()}).
     */
    public InstalledJdk install(JdkCatalog.Entry entry, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        // Clean up patches superseded by an earlier upgrade (Windows defers
        // deletion of in-use JDKs; POSIX drains immediately).
        new JdkGarbage(registry.jdksRoot()).drain();
        InstalledJdk already = alreadyInstalled(entry);
        if (already != null) return already;
        DownloadedArchive dl = download(entry, onBytesRead);
        return extractInstalled(entry, dl);
    }

    /**
     * Fast path: if the target directory already exists, return the existing install descriptor
     * without touching the network or disk. Returns {@code null} when nothing's installed yet.
     */
    public InstalledJdk alreadyInstalled(JdkCatalog.Entry entry) {
        String installName = installName(entry);
        Path target = registry.jdksRoot().resolve(installName);
        if (!Files.exists(target)) return null;
        return new InstalledJdk(installName, javaHomeFor(entry, target));
    }

    /**
     * Stream the JDK archive to a temp file, verifying SHA-256 incrementally. {@code onBytesRead}
     * receives the cumulative byte count after every chunk; the total is on {@link
     * JdkCatalog.Entry#archiveSize()}.
     */
    public DownloadedArchive download(JdkCatalog.Entry entry, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        Path downloads = prepareDownloadDir();
        Path archive = Files.createTempFile(downloads, DOWNLOAD_PREFIX, "-" + extensionFor(entry.packageType()));
        boolean keep = false;
        try {
            long bytes =
                    streamingDownload(entry.url(), entry.sha256(), entry.installFolderName(), archive, onBytesRead);
            keep = true;
            return new DownloadedArchive(archive, bytes);
        } finally {
            if (!keep) Files.deleteIfExists(archive);
        }
    }

    /**
     * Extract a {@link DownloadedArchive} into the JDK root and return the resulting {@link
     * InstalledJdk}. The temp archive is deleted on success or failure — the caller does not need to
     * clean it up.
     */
    public InstalledJdk extractInstalled(JdkCatalog.Entry entry, DownloadedArchive dl) throws IOException {
        String installName = installName(entry);
        Path target = registry.jdksRoot().resolve(installName);
        Path javaHome = javaHomeFor(entry, target);
        try {
            Path stagingDir = Files.createTempDirectory(registry.jdksRoot(), ".stage-");
            try {
                extract(dl.path(), stagingDir, entry.packageType());
                Path effectiveRoot = flattenedRoot(stagingDir);
                Files.move(effectiveRoot, target);
            } catch (IOException | RuntimeException e) {
                deleteRecursively(stagingDir);
                throw e;
            }
            // Drop the (now-empty) staging wrapper when flattenedRoot hoisted
            // a child out. If it returned stagingDir itself, the move
            // consumed the dir and this is a no-op.
            deleteRecursively(stagingDir);
        } finally {
            Files.deleteIfExists(dl.path());
        }
        // Refresh the stable <vendor>-<major> pointer so IntelliJ (and anything
        // pinning a path) survives point-release upgrades. Best-effort — a
        // failure here must not fail the install itself.
        try {
            new StableJdkPointer(registry.jdksRoot()).ensure(pointerName(entry), target);
        } catch (IOException ignored) {
            // Pointer is a convenience; the install is already complete.
        }
        return new InstalledJdk(installName, javaHome);
    }

    private static Path javaHomeFor(JdkCatalog.Entry entry, Path target) {
        return entry.javaHomeSubpath().isEmpty() ? target : target.resolve(entry.javaHomeSubpath());
    }

    // jk owns the on-disk names: the durable install dir is
    // <vendor>-<version> and the stable pointer is <vendor>-<major>, both keyed
    // off the vendor's jbPrefix rather than the feed's (inconsistent)
    // install_folder_name / suggested_sdk_name. This guarantees the pointer and
    // the install never collide (unless a feed reports version == major, where
    // the install dir harmlessly doubles as its own pointer).

    /** Durable install dir name, e.g. {@code temurin-25.0.3} / {@code graalvm-25.0.4}. */
    private static String installName(JdkCatalog.Entry e) {
        return vendorToken(e) + "-" + e.version();
    }

    /** Stable pointer name, e.g. {@code temurin-25} / {@code graalvm-25}. */
    private static String pointerName(JdkCatalog.Entry e) {
        return vendorToken(e) + "-" + e.majorVersion();
    }

    private static String vendorToken(JdkCatalog.Entry e) {
        return JdkVendor.fromFeed(e.vendor(), e.product())
                .jbPrefix()
                .orElseGet(() -> stripTrailingMajor(e.suggestedSdkName()));
    }

    /**
     * {@code "graalvm-jdk-25"} → {@code "graalvm-jdk"}; leaves names without a trailing major intact.
     */
    private static String stripTrailingMajor(String suggested) {
        int dash = suggested.lastIndexOf('-');
        if (dash > 0) {
            String tail = suggested.substring(dash + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                return suggested.substring(0, dash);
            }
        }
        return suggested;
    }

    /**
     * Buffered download path used by the {@link JdkPackage} flow. Smaller scope (no progress, no
     * streaming) — kept for the test fixtures that go through the {@link JdkPackage} API.
     */
    private void downloadAndExtractBuffered(URI uri, String sha256, String displayName, String archiveType, Path target)
            throws IOException, InterruptedException {
        Path downloads = prepareDownloadDir();
        Path archive = Files.createTempFile(downloads, DOWNLOAD_PREFIX, "-" + extensionFor(archiveType));
        try {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException("JDK download " + uri + " returned " + response.statusCode());
            }
            byte[] body = response.body();
            if (sha256 != null && !sha256.isEmpty()) {
                String actual = Hashing.sha256Hex(body);
                if (!actual.equalsIgnoreCase(sha256)) {
                    throw new IOException(
                            "sha256 mismatch for " + displayName + " — expected " + sha256 + ", got " + actual);
                }
            }
            Files.write(archive, body);

            // Stage under the jdks root so the final rename is on the same
            // filesystem as the target. Otherwise (/tmp on tmpfs vs. $HOME on
            // ext4/btrfs) Files.move falls into the cross-device branch and
            // fails with DirectoryNotEmptyException on the first non-empty
            // subdir of the JDK.
            Path stagingDir = Files.createTempDirectory(registry.jdksRoot(), ".stage-");
            try {
                extract(archive, stagingDir, archiveType);
                Path effectiveRoot = flattenedRoot(stagingDir);
                Files.move(effectiveRoot, target);
            } catch (IOException | RuntimeException e) {
                deleteRecursively(stagingDir);
                throw e;
            }
            deleteRecursively(stagingDir);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /**
     * Ensure the download scratch dir exists and sweep any orphaned partials from previously-canceled
     * downloads, returning the dir to stream into.
     *
     * <p>The dir is {@code <jdksRoot>/.downloads}: under the JDK root so the archive shares a
     * filesystem with the install target, and dot-prefixed so {@link dev.jkbuild.discovery.JkProbe}
     * (which skips dot dirs) never mistakes it for an installed JDK.
     */
    private Path prepareDownloadDir() throws IOException {
        Path dir = registry.jdksRoot().resolve(DOWNLOAD_DIR);
        Files.createDirectories(dir);
        sweepDir(dir);
        return dir;
    }

    /**
     * Delete partial archives orphaned by a canceled download under {@code <jdksRoot>/.downloads}.
     * The download path runs this automatically, but it's also the public entry point for {@code jk
     * jdk} commands that don't download (uninstall) or may early-return before downloading (install
     * /update when the target is already present) — call it once per command so a leftover partial
     * never outlives the user's next {@code jk jdk} action. No-op when the scratch dir is absent.
     */
    public static void sweepStaleDownloads(Path jdksRoot) {
        Path dir = jdksRoot.resolve(DOWNLOAD_DIR);
        if (Files.isDirectory(dir)) sweepDir(dir);
    }

    /**
     * Delete partial archives orphaned by a canceled download. Ctrl-C triggers {@code Runtime.halt},
     * which terminates the JVM without running the finally-block cleanup in {@link #download}, so the
     * partial archive is left behind; the next {@code jk jdk} command sweeps it. Only files older
     * than {@link #STALE_DOWNLOAD_AGE_MILLIS} are removed, so a download in flight from a concurrent
     * {@code jk jdk install} is never yanked out from under it. Best-effort: unreadable or vanished
     * entries are left for the next sweep.
     */
    private static void sweepDir(Path downloadDir) {
        long cutoff = System.currentTimeMillis() - STALE_DOWNLOAD_AGE_MILLIS;
        try (Stream<Path> entries = Files.list(downloadDir)) {
            entries.filter(p -> p.getFileName().toString().startsWith(DOWNLOAD_PREFIX))
                    .forEach(p -> {
                        try {
                            if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                                Files.deleteIfExists(p);
                            }
                        } catch (IOException ignored) {
                            // vanished or unreadable — the next sweep catches it
                        }
                    });
        } catch (IOException ignored) {
            // can't list the dir — nothing here is load-bearing
        }
    }

    /**
     * Stream {@code uri} into {@code archive} while updating a SHA-256 digest and forwarding
     * cumulative byte counts to {@code onBytesRead}. Verifies the digest against {@code
     * expectedSha256} on completion (when set).
     */
    private long streamingDownload(
            URI uri, String expectedSha256, String displayName, Path archive, LongConsumer onBytesRead)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.getStream(uri);
        if (response.statusCode() != 200) {
            try (var body = response.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("JDK download " + uri + " returned " + response.statusCode());
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required on every JVM", e);
        }
        long total = 0;
        try (InputStream body = response.body();
                OutputStream sink = Files.newOutputStream(archive)) {
            byte[] buf = new byte[DOWNLOAD_BUFFER];
            int n;
            while ((n = body.read(buf)) > 0) {
                sha.update(buf, 0, n);
                sink.write(buf, 0, n);
                total += n;
                onBytesRead.accept(total);
            }
        }
        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "sha256 mismatch for " + displayName + " — expected " + expectedSha256 + ", got " + actual);
            }
        }
        return total;
    }

    private static String extensionFor(String archiveType) {
        if (archiveType == null) return "tar.gz";
        return switch (archiveType) {
            case "targz" -> "tar.gz";
            default -> archiveType;
        };
    }

    private static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir);
            case "tar.gz", "tgz", "targz" -> extractTar(archive, destDir, true);
            case "tar" -> extractTar(archive, destDir, false);
            default -> throw new IOException("unsupported archive type: " + archiveType);
        }
    }

    private static void extractTar(Path archive, Path destDir, boolean gzipped) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archive));
                InputStream inflated = gzipped ? new GZIPInputStream(fis) : fis) {
            MinimalTar.stream(inflated, (name, linkName, mode, isDir, isLink, data, size) -> {
                // Drop macOS AppleDouble sidecars (`._foo`) anywhere in the tree.
                if (isAppleDoubleSidecar(name)) return;
                Path out = destDir.resolve(name).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("tar entry escapes destination: " + name);
                }
                if (isDir) {
                    Files.createDirectories(out);
                } else if (isLink) {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.deleteIfExists(out);
                    Files.createSymbolicLink(out, Path.of(linkName));
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(data, out);
                    MinimalTar.applyMode(out, mode);
                }
            });
        }
    }

    private static boolean isAppleDoubleSidecar(String entryName) {
        int end = entryName.endsWith("/") ? entryName.length() - 1 : entryName.length();
        int slash = entryName.lastIndexOf('/', end - 1);
        String basename = entryName.substring(slash + 1, end);
        return basename.startsWith("._");
    }

    /**
     * Parallel ZIP extraction. ZIP's Central Directory lets us seek to any entry's deflate stream
     * independently, so we fan out across {@link JkThreads#cpu()} workers — meaningful on JDK zips
     * with several thousand entries (Windows JDK builds typically ship as zip).
     *
     * <p>Tar.gz can't be parallelized this way: gunzip is a single sequential stream and the tar
     * metadata is interleaved with the file data.
     */
    private static void unzip(Path archive, Path destDir) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            // Pre-create directories in the main thread so workers don't race
            // on createDirectories for the same parent path.
            for (ZipEntry e : entries) {
                if (e.isDirectory()) {
                    Path out = safeResolve(destDir, e.getName());
                    Files.createDirectories(out);
                }
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>(entries.size());
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            try {
                                Path out = safeResolve(destDir, entry.getName());
                                if (out.getParent() != null) Files.createDirectories(out.getParent());
                                try (InputStream in = zip.getInputStream(entry)) {
                                    Files.copy(in, out);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        },
                        JkThreads.cpu()));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();
            } catch (java.util.concurrent.CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof UncheckedIOException uio) throw uio.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException("zip extraction failed: " + cause.getMessage(), cause);
            }
        }
    }

    /** Resolve a zip/tar entry path against {@code destDir} and reject zip-slip escapes. */
    private static Path safeResolve(Path destDir, String entryName) throws IOException {
        Path out = destDir.resolve(entryName).normalize();
        if (!out.startsWith(destDir)) {
            throw new IOException("zip entry escapes destination: " + entryName);
        }
        return out;
    }

    /**
     * JDK archives usually unpack to a single top-level directory ({@code jdk-21.0.5+11}) — if so,
     * return that. Otherwise return the staging dir.
     */
    private static Path flattenedRoot(Path stagingDir) throws IOException {
        List<Path> children = new ArrayList<>();
        try (var stream = Files.list(stagingDir)) {
            stream.forEach(children::add);
        }
        if (children.size() == 1 && Files.isDirectory(children.getFirst())) {
            return children.getFirst();
        }
        return stagingDir;
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
