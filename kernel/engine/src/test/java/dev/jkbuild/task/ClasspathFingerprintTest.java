// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Files.writeString(f, "V1"); // same bytes
        assertThat(ClasspathFingerprint.entry(f)).isEqualTo(v1);
        Files.writeString(f, "V2"); // changed bytes
        assertThat(ClasspathFingerprint.entry(f)).isNotEqualTo(v1);
    }

    @Test
    void directory_is_keyed_by_its_tree_content(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        String before = ClasspathFingerprint.entry(classes);
        write(classes.resolve("a/B.class"), "BBBB"); // a new class file
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void jar_is_keyed_by_logical_content_not_packaging(@TempDir Path dir) throws IOException {
        // jk re-jars the same classes in a different order with fresh timestamps
        // every build; the fingerprint must see through that to the contents.
        Path j1 = writeJar(dir.resolve("a.jar"), new String[][] {{"A.class", "AA"}, {"B.class", "BB"}}, 1000);
        Path j2 = writeJar(dir.resolve("b.jar"), new String[][] {{"B.class", "BB"}, {"A.class", "AA"}}, 9_999_000);
        assertThat(ClasspathFingerprint.entry(j1))
                .as("same entries, different order + timestamps → same fingerprint")
                .isEqualTo(ClasspathFingerprint.entry(j2));

        Path j3 = writeJar(dir.resolve("c.jar"), new String[][] {{"A.class", "AA"}, {"B.class", "CHANGED"}}, 1000);
        assertThat(ClasspathFingerprint.entry(j3))
                .as("an entry's content change → different fingerprint")
                .isNotEqualTo(ClasspathFingerprint.entry(j1));
    }

    private static Path writeJar(Path jar, String[][] entries, long time) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String[] e : entries) {
                var ze = new ZipEntry(e[0]);
                ze.setTime(time);
                zos.putNextEntry(ze);
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void directory_fingerprint_ignores_freshness_stamps(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve(".jstamp"), "stamp-1");
        String before = ClasspathFingerprint.entry(classes);
        // jk rewrites the stamps every build — build-host metadata, not code, so
        // the fingerprint must ignore them.
        write(classes.resolve(".jstamp"), "stamp-2-different");
        write(classes.resolve(".kstamp"), "k");
        write(classes.resolve(".test-stamp"), "t");
        assertThat(ClasspathFingerprint.entry(classes)).isEqualTo(before);
        // A real class change still busts it.
        write(classes.resolve("a/A.class"), "BBBB");
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void directory_fingerprint_tracks_embed_sha_resources(@TempDir Path dir) throws IOException {
        // [build.embed-sha] writes META-INF/jk-<worker>-sha256.txt holding a
        // worker jar's SHA. Now that worker jars are byte-reproducible the value
        // is stable across no-op rebuilds, and a genuine worker change must
        // ripple in — so unlike the stamps, this resource counts.
        Path classes = Files.createDirectories(dir.resolve("classes"));
        write(classes.resolve("a/A.class"), "AAAA");
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-1");
        String before = ClasspathFingerprint.entry(classes);
        write(classes.resolve("META-INF/jk-git-client-sha256.txt"), "sha-2-worker-changed");
        assertThat(ClasspathFingerprint.entry(classes)).isNotEqualTo(before);
    }

    @Test
    void missing_entry_has_a_distinct_token(@TempDir Path dir) throws IOException {
        assertThat(ClasspathFingerprint.entry(dir.resolve("gone.jar"))).startsWith("missing:");
    }

    @Test
    void settled_jar_fingerprint_is_memoized_and_a_content_change_invalidates(@TempDir Path dir) throws Exception {
        // A jar whose mtime has settled takes the FileHashMemo stat fast-path on the
        // second read; a content change (new size/mtime) must still re-fingerprint.
        Path jar = writeJar(dir.resolve("dep.jar"), new String[][] {{"A.class", "AA"}}, 1000);
        java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(jar, old);
        Path cache = dir.resolve("cache");
        dev.jkbuild.config.SessionContext.where(
                dev.jkbuild.config.Session.defaults().withCacheDir(cache),
                () -> {
                    String fp1 = ClasspathFingerprint.entry(jar);
                    assertThat(Files.isDirectory(cache.resolve("hash-memo")))
                            .as("settled fingerprint recorded on disk")
                            .isTrue();
                    assertThat(ClasspathFingerprint.entry(jar))
                            .as("memoized read agrees with the computed fingerprint")
                            .isEqualTo(fp1);
                    writeJar(jar, new String[][] {{"A.class", "CHANGED-CONTENT"}}, 2000);
                    Files.setLastModifiedTime(
                            jar, java.nio.file.attribute.FileTime.fromMillis(old.toMillis() + 5_000));
                    assertThat(ClasspathFingerprint.entry(jar))
                            .as("content change re-fingerprints despite the memo")
                            .isNotEqualTo(fp1);
                    return null;
                });
    }

    @Test
    void of_is_order_independent(@TempDir Path dir) throws IOException {
        Path a = write(dir.resolve("a.jar"), "A");
        Path b = write(dir.resolve("b.jar"), "B");
        assertThat(ClasspathFingerprint.of(List.of(a, b))).isEqualTo(ClasspathFingerprint.of(List.of(b, a)));
    }
}
