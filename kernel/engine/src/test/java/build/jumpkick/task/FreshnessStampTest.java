// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FreshnessStampTest {

    private static final int RELEASE = 21;

    @Test
    void removed_sources_are_detected_and_absent_stamp_is_not(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path kept = writeFile(tempDir.resolve("Kept.java"), "class Kept {}");
        Path dropped = writeFile(tempDir.resolve("Dropped.java"), "class Dropped {}");
        // No stamp yet: nothing recorded, nothing removed.
        assertThat(FreshnessStamp.hasRemovedSources(classes, FreshnessStamp.JAVA_STAMP, List.of(kept)))
                .isFalse();
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123",
                List.of(kept, dropped), List.of(), RELEASE);
        // Same set: no removals. Grown set: no removals. Shrunk set: removal detected —
        // the variant-switch case (an extra-src root left the selection).
        assertThat(FreshnessStamp.hasRemovedSources(classes, FreshnessStamp.JAVA_STAMP, List.of(kept, dropped)))
                .isFalse();
        assertThat(FreshnessStamp.hasRemovedSources(
                        classes, FreshnessStamp.JAVA_STAMP, List.of(kept, dropped, tempDir.resolve("New.java"))))
                .isFalse();
        assertThat(FreshnessStamp.hasRemovedSources(classes, FreshnessStamp.JAVA_STAMP, List.of(kept)))
                .isTrue();
    }

    @Test
    void absent_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void unchanged_inputs_are_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");

        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(jar), RELEASE);
        // Backdate the inputs by a second to make sure the mtime comparison
        // sees them as <= the stamp's millis (filesystem timestamp resolution
        // varies; same-millisecond can flake either way).
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(jar), RELEASE))
                .isTrue();
    }

    @Test
    void different_release_is_not_fresh(@TempDir Path tempDir) throws IOException {
        // A stamp written for a different --release (e.g. a JDK/toolchain switch) must
        // not be trusted even when every file mtime still looks unchanged.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), 17);
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), 21))
                .isFalse();
    }

    @Test
    void source_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), RELEASE);
        // Bump mtime forward; the stat will now exceed the stamp time.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void input_modified_in_the_same_millisecond_as_the_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        // Regression: a build that finishes writing its stamp in the same
        // millisecond a source is edited must NOT be treated as fresh — it has
        // to fall through to the content-hashing action cache. (This is the
        // race BuildCacheTest.editing_a_source_invalidates_cache hit on fast disks.)
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), RELEASE);

        long stampMillis = FreshnessStamp.read(classes, FreshnessStamp.JAVA_STAMP)
                .orElseThrow()
                .stampMillis();
        Files.setLastModifiedTime(src, FileTime.fromMillis(stampMillis));

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void added_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(a), List.of(), RELEASE);

        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        // Source set composition changed → not fresh, even though both files
        // are older than the stamp.
        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(a, b), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void removed_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(a, b), List.of(), RELEASE);

        // Caller passes a smaller source list — stamp said it covered two.
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(a), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void missing_input_file_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), RELEASE);

        Files.delete(src);
        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void wiping_output_dir_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        // The stamp lives inside the output dir, so removing the dir takes
        // the stamp with it — next build sees no stamp and falls through.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), RELEASE);

        Files.delete(classes.resolve(FreshnessStamp.JAVA_STAMP));
        Files.delete(classes);

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), RELEASE))
                .isFalse();
    }

    @Test
    void classpath_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(jar), RELEASE);

        // A dep got rebuilt — its mtime is now newer than our stamp.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(jar), RELEASE))
                .isFalse();
    }

    @Test
    void different_release_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(
                classes, FreshnessStamp.JAVA_STAMP, "compile-main", "key123", List.of(src), List.of(), RELEASE);

        assertThat(FreshnessStamp.isFresh(classes, FreshnessStamp.JAVA_STAMP, List.of(src), List.of(), 17))
                .isFalse();
    }

    private static Path writeFile(Path file, String body) throws IOException {
        Files.writeString(file, body);
        return file;
    }
}
