// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathFingerprintTest {

    private static Path write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void cas_entry_is_keyed_by_its_hash_path_not_its_bytes(@TempDir Path dir) throws IOException {
        // A CAS blob's path already encodes its content; the path is the fingerprint.
        Path cas = write(dir.resolve("cache/sha256/ab/cd/rest"), "anything");
        String fp = ClasspathFingerprint.entry(cas);
        assertThat(fp).startsWith("cas:").contains("/sha256/");
    }

    @Test
    void local_non_archive_file_is_keyed_by_raw_content(@TempDir Path dir) throws IOException {
        Path f = write(dir.resolve("target/blob.bin"), "V1");
        String v1 = ClasspathFingerprint.entry(f);
        Files.writeString(f, "V1");                          // same bytes
        assertThat(ClasspathFingerprint.entry(f)).isEqualTo(v1);
        Files.writeString(f, "V2");                          // changed bytes
        assertThat(ClasspathFingerprint.entry(f)).isNotEqualTo(v1);
    }

    @Test
    void directory_is_keyed_by_its_tree_content(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        String before = ClasspathFingerprint.entry(classes);
        write(classes.resolve("a/B.class"), "BBBB");        // a new class file
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void jar_is_keyed_by_logical_content_not_packaging(@TempDir Path dir) throws IOException {
        // jk re-jars the same classes in a different order with fresh timestamps
        // every build; the fingerprint must see through that to the contents.
        Path j1 = writeJar(dir.resolve("a.jar"), new String[][]{{"A.class", "AA"}, {"B.class", "BB"}}, 1000);
        Path j2 = writeJar(dir.resolve("b.jar"), new String[][]{{"B.class", "BB"}, {"A.class", "AA"}}, 9_999_000);
        assertThat(ClasspathFingerprint.entry(j1))
                .as("same entries, different order + timestamps → same fingerprint")
                .isEqualTo(ClasspathFingerprint.entry(j2));

        Path j3 = writeJar(dir.resolve("c.jar"), new String[][]{{"A.class", "AA"}, {"B.class", "CHANGED"}}, 1000);
        assertThat(ClasspathFingerprint.entry(j3))
                .as("an entry's content change → different fingerprint")
                .isNotEqualTo(ClasspathFingerprint.entry(j1));
    }

    private static Path writeJar(Path jar, String[][] entries, long time) throws IOException {
        try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (String[] e : entries) {
                var ze = new java.util.zip.ZipEntry(e[0]);
                ze.setTime(time);
                zos.putNextEntry(ze);
                zos.write(e[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void directory_fingerprint_ignores_jk_build_stamps(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve(".jstamp"), "stamp-1");
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-1");
        String before = ClasspathFingerprint.entry(classes);
        // jk rewrites the stamps and the embed-sha resources every build — they're
        // build-host metadata, not code, so the fingerprint must ignore them.
        write(classes.resolve(".jstamp"), "stamp-2-different");
        write(classes.resolve(".kstamp"), "k");
        write(classes.resolve(".test-stamp"), "t");
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-2-different");
        assertThat(ClasspathFingerprint.entry(classes)).isEqualTo(before);
        // A real class change still busts it.
        write(classes.resolve("a/A.class"), "BBBB");
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void missing_entry_has_a_distinct_token(@TempDir Path dir) throws IOException {
        assertThat(ClasspathFingerprint.entry(dir.resolve("gone.jar"))).startsWith("missing:");
    }

    @Test
    void of_is_order_independent(@TempDir Path dir) throws IOException {
        Path a = write(dir.resolve("a.jar"), "A");
        Path b = write(dir.resolve("b.jar"), "B");
        assertThat(ClasspathFingerprint.of(java.util.List.of(a, b)))
                .isEqualTo(ClasspathFingerprint.of(java.util.List.of(b, a)));
    }
}
