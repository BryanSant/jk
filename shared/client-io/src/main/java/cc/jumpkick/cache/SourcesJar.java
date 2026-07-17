// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

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
 * Builds a Maven-style {@code <artifact>-<version>-sources.jar} by zipping the project's source
 * directories. Entries are stored relative to each root ({@code src/main/java/com/foo/Bar.java}
 * becomes {@code com/foo/Bar.java} in the archive). Deterministic: entries are sorted and mtime is
 * pinned to zero.
 */
public final class SourcesJar {

    private SourcesJar() {}

    /**
     * Build a sources jar in memory from the given source roots. Empty or absent roots are skipped.
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
        Collections.sort(entries);
        for (Path file : entries) {
            String name = root.relativize(file).toString().replace('\\', '/');
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(0L); // stable mtime → reproducible archive
            jos.putNextEntry(entry);
            Files.copy(file, jos);
            jos.closeEntry();
        }
    }
}
