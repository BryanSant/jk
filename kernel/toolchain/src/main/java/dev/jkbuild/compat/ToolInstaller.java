// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.MinimalTar;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.PathUtil;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a {@link ToolDistribution} and extracts it under
 * {@code $JK_CACHE_DIR/tools/<slug>/<version>/}.
 *
 * <ul>
 *   <li>{@code zip} — {@link ZipInputStream}; the launcher under
 *       {@code bin/} is chmod +x'd on POSIX since zip carries no mode bits
 *       in the JDK stdlib.</li>
 *   <li>{@code tar.gz} — {@link TarArchiveInputStream} over
 *       {@link GZIPInputStream}; POSIX permissions preserved.</li>
 *   <li>SHA-256 verified against {@link ToolDistribution#sha256()} when
 *       present; mismatch aborts with no install dir left behind.</li>
 * </ul>
 */
public final class ToolInstaller {

    private final Http http;
    private final ToolRegistry registry;

    public ToolInstaller(Http http, ToolRegistry registry) {
        this.http = Objects.requireNonNull(http, "http");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public InstalledTool install(ToolDistribution dist) throws IOException, InterruptedException {
        Path target = registry.installDir(dist.tool(), dist.version());
        if (Files.isDirectory(target)) {
            return new InstalledTool(dist.tool(), dist.version(), target);
        }
        Files.createDirectories(target.getParent());

        Path archive = Files.createTempFile("jk-tool-", "-" + dist.archiveType());
        try {
            HttpResponse<byte[]> response = http.get(dist.downloadUri());
            if (response.statusCode() != 200) {
                throw new IOException(
                        dist.tool().slug() + " download " + dist.downloadUri() + " returned " + response.statusCode());
            }
            byte[] body = response.body();
            if (dist.sha256() != null && !dist.sha256().isEmpty()) {
                String actual = Hashing.sha256Hex(body);
                if (!actual.equalsIgnoreCase(dist.sha256())) {
                    throw new IOException("sha256 mismatch for " + dist.downloadUri() + " — expected " + dist.sha256()
                            + ", got " + actual);
                }
            }
            Files.write(archive, body);

            Path stagingDir = Files.createTempDirectory("jk-tool-stage-");
            try {
                extract(archive, stagingDir, dist.archiveType());
                Path effectiveRoot = flattenedRoot(stagingDir);
                moveInto(effectiveRoot, target);
                ensureBinaryExecutable(target, dist.tool());
            } catch (IOException | RuntimeException e) {
                deleteRecursively(stagingDir);
                deleteRecursively(target);
                throw e;
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        return new InstalledTool(dist.tool(), dist.version(), target);
    }

    private static void extract(Path archive, Path destDir, String archiveType) throws IOException {
        Files.createDirectories(destDir);
        switch (archiveType) {
            case "zip" -> unzip(archive, destDir);
            case "tar.gz" -> extractTarGz(archive, destDir);
            default -> throw new IOException("unsupported archive type: " + archiveType);
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

    private static void extractTarGz(Path archive, Path destDir) throws IOException {
        try (InputStream fis = new BufferedInputStream(Files.newInputStream(archive));
                GZIPInputStream gz = new GZIPInputStream(fis)) {
            MinimalTar.stream(gz, (name, linkName, mode, isDir, isLink, data, size) -> {
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

    /**
     * Zip archives carry no Unix mode bits, so {@code bin/mvn} or
     * {@code bin/gradle} arrives without the +x bit. Set it explicitly so
     * {@code ProcessBuilder} can exec the launcher.
     */
    private static void ensureBinaryExecutable(Path home, BuildTool tool) {
        Path bin = home.resolve("bin").resolve(tool.binaryName());
        if (!Files.exists(bin)) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(bin));
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(bin, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX filesystem — .cmd/.bat needs no +x bit.
        }
    }

    /**
     * Maven and Gradle archives unpack into a single top-level directory
     * (e.g. {@code apache-maven-3.9.9/}, {@code gradle-9.5.1/}). Strip it
     * so {@code home/bin/} is reachable.
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

    private static void moveInto(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.list(source)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Files.move(child, target.resolve(child.getFileName()));
            }
        }
        Files.deleteIfExists(source);
    }

    private static void deleteRecursively(Path root) {
        PathUtil.deleteRecursively(root);
    }
}
