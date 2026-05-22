// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.jdk;

import dev.buildjk.http.Http;
import dev.buildjk.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a {@link JdkPackage} and extracts it under {@code ~/.jk/jdks/}.
 *
 * <p>v0.4 first iteration:
 * <ul>
 *   <li>{@code .tar.gz} archives are extracted by shelling out to the
 *       system {@code tar}. Works on Linux/macOS; native zip-streaming
 *       tar reader will replace it later.</li>
 *   <li>{@code .zip} archives use {@link ZipInputStream}.</li>
 *   <li>SHA-256 is verified against {@link JdkPackage#sha256()} when the
 *       package carries one. Mismatches abort the install with the
 *       partial output cleaned up.</li>
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
        Files.createDirectories(registry.jdksRoot());

        Path archive = Files.createTempFile("jk-jdk-", "-" + extensionFor(pkg.archiveType()));
        try {
            HttpResponse<byte[]> response = http.get(pkg.downloadUri());
            if (response.statusCode() != 200) {
                throw new IOException("JDK download " + pkg.downloadUri()
                        + " returned " + response.statusCode());
            }
            byte[] body = response.body();
            if (pkg.sha256() != null && !pkg.sha256().isEmpty()) {
                String actual = Hashing.sha256Hex(body);
                if (!actual.equalsIgnoreCase(pkg.sha256())) {
                    throw new IOException("sha256 mismatch for " + pkg.filename()
                            + " — expected " + pkg.sha256() + ", got " + actual);
                }
            }
            Files.write(archive, body);

            Path stagingDir = Files.createTempDirectory("jk-jdk-stage-");
            try {
                extract(archive, stagingDir, pkg.archiveType());
                Path effectiveRoot = flattenedRoot(stagingDir);
                moveInto(effectiveRoot, target);
            } catch (IOException | RuntimeException e) {
                deleteRecursively(stagingDir);
                throw e;
            }
        } finally {
            Files.deleteIfExists(archive);
        }
        return new InstalledJdk(identifier, target);
    }

    private static String installIdentifier(JdkPackage pkg) {
        return pkg.sdkmanIdentifier() + "-" + pkg.architecture() + "-" + pkg.operatingSystem();
    }

    private static String extensionFor(String archiveType) {
        return archiveType == null ? "tar.gz" : archiveType;
    }

    private static void extract(Path archive, Path destDir, String archiveType)
            throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        if (archiveType.equals("zip")) {
            unzip(archive, destDir);
            return;
        }
        // tar.gz / tar — shell out for now.
        ProcessBuilder pb = new ProcessBuilder("tar", "xf", archive.toString(),
                "-C", destDir.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("tar extraction failed (" + exit + "): "
                    + new String(stdout));
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
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
