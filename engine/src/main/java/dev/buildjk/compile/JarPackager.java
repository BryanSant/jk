// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Packages a directory of compiled classes + resources into a reproducible
 * jar. PRD §23.7 hermeticity defaults:
 *
 * <ul>
 *   <li>Entries written in sorted order so the byte layout is stable.</li>
 *   <li>Every entry's timestamp set to {@code SOURCE_DATE_EPOCH} (or
 *       the Unix epoch when unset).</li>
 *   <li>Manifest contains only what we set — no
 *       {@code Created-By} / {@code Build-Jdk} pollution.</li>
 *   <li>File modes left to the platform default; explicit modes (0644
 *       for data, 0755 for scripts) come once we ship anything beyond
 *       class files inside the jar.</li>
 * </ul>
 */
public final class JarPackager {

    public Path packageJar(JarRequest request) throws IOException {
        Files.createDirectories(request.outputJar().getParent());
        Manifest manifest = buildManifest(request);

        try (OutputStream out = Files.newOutputStream(request.outputJar());
             JarOutputStream jos = new JarOutputStream(out, manifest)) {

            List<Path> files = collectFiles(request.inputDir());
            // Sort by relative path for deterministic entry order.
            files.sort(Comparator.comparing(p -> normalize(request.inputDir(), p)));

            for (Path file : files) {
                String name = normalize(request.inputDir(), file);
                if (name.equals("META-INF/MANIFEST.MF")) continue; // already written
                JarEntry entry = new JarEntry(name);
                entry.setTimeLocal(LocalDateTime.ofEpochSecond(
                        request.timestampEpochSeconds(), 0,
                        ZoneOffset.UTC));
                jos.putNextEntry(entry);
                Files.copy(file, jos);
                jos.closeEntry();
            }
        }
        return request.outputJar();
    }

    private static Manifest buildManifest(JarRequest request) {
        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (request.mainClass() != null && !request.mainClass().isBlank()) {
            attrs.put(Attributes.Name.MAIN_CLASS, request.mainClass());
        }
        return manifest;
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        }
        return result;
    }

    private static String normalize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /** Input to {@link JarPackager#packageJar(JarRequest)}. */
    public record JarRequest(
            Path inputDir,
            Path outputJar,
            String mainClass,
            long timestampEpochSeconds) {

        public JarRequest {
            Objects.requireNonNull(inputDir, "inputDir");
            Objects.requireNonNull(outputJar, "outputJar");
        }

        public static JarRequest of(Path inputDir, Path outputJar) {
            return new JarRequest(inputDir, outputJar, null, 0L);
        }

        public JarRequest withMainClass(String mainClass) {
            return new JarRequest(inputDir, outputJar, mainClass, timestampEpochSeconds);
        }
    }
}
