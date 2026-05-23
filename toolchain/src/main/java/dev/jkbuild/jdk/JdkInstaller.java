// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.http.Http;
import dev.jkbuild.util.Hashing;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a {@link JdkPackage} and extracts it under the IntelliJ JDK
 * directory (see {@link IntellijJdkDir}).
 *
 * <ul>
 *   <li>{@code .tar.gz} / {@code .tgz} — {@link TarArchiveInputStream}
 *       over {@link GZIPInputStream}. POSIX permissions and symlinks
 *       preserved.</li>
 *   <li>{@code .tar} — {@link TarArchiveInputStream} directly.</li>
 *   <li>{@code .zip} — {@link ZipInputStream}.</li>
 *   <li>SHA-256 verified against {@link JdkPackage#sha256()} when present;
 *       mismatch aborts with no install dir left behind.</li>
 * </ul>
 */
public final class JdkInstaller {

    private final Http http;
    private final JdkRegistry registry;

    public JdkInstaller(Http http, JdkRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledJdk install(JdkPackage pkg) throws IOException, InterruptedException {
        String identifier = installIdentifier(pkg);
        Path target = registry.jdksRoot().resolve(identifier);
        if (Files.exists(target)) {
            return new InstalledJdk(identifier, target);
        }
        downloadAndExtract(
                pkg.downloadUri(),
                pkg.sha256(),
                pkg.filename(),
                pkg.archiveType(),
                target);
        return new InstalledJdk(identifier, target);
    }

    /**
     * Install a {@link JdkCatalog.Entry} from the JetBrains feed. Uses the
     * feed's {@code install_folder_name} for the directory and resolves
     * JAVA_HOME via {@code package_to_java_home_prefix} (e.g.
     * {@code Contents/Home} on macOS) so callers always get a working
     * {@link InstalledJdk#home()}.
     */
    public InstalledJdk install(JdkCatalog.Entry entry) throws IOException, InterruptedException {
        Path target = registry.jdksRoot().resolve(entry.installFolderName());
        Path javaHome = entry.javaHomeSubpath().isEmpty()
                ? target
                : target.resolve(entry.javaHomeSubpath());
        if (Files.exists(target)) {
            return new InstalledJdk(entry.installFolderName(), javaHome);
        }
        downloadAndExtract(
                entry.url(),
                entry.sha256(),
                entry.installFolderName() + "." + extensionFor(entry.packageType()),
                entry.packageType(),
                target);
        return new InstalledJdk(entry.installFolderName(), javaHome);
    }

    private void downloadAndExtract(
            java.net.URI uri,
            String sha256,
            String displayName,
            String archiveType,
            Path target) throws IOException, InterruptedException {
        Files.createDirectories(registry.jdksRoot());
        Path archive = Files.createTempFile("jk-jdk-", "-" + extensionFor(archiveType));
        try {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException("JDK download " + uri
                        + " returned " + response.statusCode());
            }
            byte[] body = response.body();
            if (sha256 != null && !sha256.isEmpty()) {
                String actual = Hashing.sha256Hex(body);
                if (!actual.equalsIgnoreCase(sha256)) {
                    throw new IOException("sha256 mismatch for " + displayName
                            + " — expected " + sha256 + ", got " + actual);
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
            // Drop the (now-empty) staging wrapper when flattenedRoot hoisted
            // a child out. If it returned stagingDir itself, the move
            // consumed the dir and this is a no-op.
            deleteRecursively(stagingDir);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static String installIdentifier(JdkPackage pkg) {
        return pkg.installIdentifier();
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
             InputStream inflated = gzipped ? new GZIPInputStream(fis) : fis;
             TarArchiveInputStream tar = new TarArchiveInputStream(inflated)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                // Drop macOS AppleDouble sidecars (`._foo`) anywhere in the tree.
                // They leak in when a tarball is built on macOS with xattrs on
                // the source files, and a single ._<topdir> entry at the root
                // breaks flattenedRoot()'s single-top-level detection.
                if (isAppleDoubleSidecar(entry.getName())) continue;
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("tar entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else if (entry.isSymbolicLink()) {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.deleteIfExists(out);
                    Files.createSymbolicLink(out, Path.of(entry.getLinkName()));
                } else if (entry.isFile()) {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(tar, out);
                    applyMode(out, entry.getMode());
                }
                // Hard links, device files, etc. — skip silently. JDK archives
                // don't use them.
            }
        }
    }

    private static boolean isAppleDoubleSidecar(String entryName) {
        int end = entryName.endsWith("/") ? entryName.length() - 1 : entryName.length();
        int slash = entryName.lastIndexOf('/', end - 1);
        String basename = entryName.substring(slash + 1, end);
        return basename.startsWith("._");
    }

    /** Apply tar entry's POSIX mode bits where the filesystem supports it. */
    private static void applyMode(Path file, int mode) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            if (perms.isEmpty()) return; // tar entry didn't carry a mode
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX filesystem — silently skip.
        }
    }

    private static void unzip(Path archive, Path destDir) throws IOException {
        try (InputStream in = Files.newInputStream(archive);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = destDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(destDir)) {
                    throw new IOException("zip entry escapes destination: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    Files.copy(zis, out);
                }
            }
        }
    }

    /**
     * JDK archives usually unpack to a single top-level directory
     * ({@code jdk-21.0.5+11}) — if so, return that. Otherwise return
     * the staging dir.
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
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
