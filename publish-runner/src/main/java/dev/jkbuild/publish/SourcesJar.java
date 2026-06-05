// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Builds a Maven-style {@code <artifact>-<version>-sources.jar} by zipping
 * the project's source directories. PRD §21.2 requires the sources jar in
 * every Central upload.
 */
public final class SourcesJar {

    private SourcesJar() {}

    /**
     * Build a sources jar from the given source roots. Entries are stored
     * with paths relative to each root (so {@code src/main/java/com/foo/Bar.java}
     * becomes {@code com/foo/Bar.java} in the jar). Empty roots are skipped
     * without error.
     */
    public static byte[] build(List<Path> sourceRoots) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Created-By", "jk");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(baos, mf)) {
            for (Path root : sourceRoots) {
                if (!Files.isDirectory(root)) continue;
                appendTree(jos, root);
            }
        }
        return baos.toByteArray();
    }

    private static void appendTree(JarOutputStream jos, Path root) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(entries::add);
        }
        // Sort so the archive is deterministic across filesystems.
        Collections.sort(entries);
        for (Path file : entries) {
            String name = root.relativize(file).toString().replace('\\', '/');
            ZipEntry entry = new ZipEntry(name);
            // Stable mtime — reproducible builds discipline (PRD §23 spirit).
            entry.setTime(0L);
            jos.putNextEntry(entry);
            Files.copy(file, jos);
            jos.closeEntry();
        }
    }
}
