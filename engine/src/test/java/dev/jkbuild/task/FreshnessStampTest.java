// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreshnessStampTest {

    @Test
    void absent_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        assertThat(FreshnessStamp.isFresh(classes, List.of(), List.of())).isFalse();
    }

    @Test
    void unchanged_inputs_are_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");

        FreshnessStamp.write(classes, "compile-main", "key123", List.of(src), List.of(jar));
        // Backdate the inputs by a second to make sure the mtime comparison
        // sees them as <= the stamp's millis (filesystem timestamp resolution
        // varies; same-millisecond can flake either way).
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        assertThat(FreshnessStamp.isFresh(classes, List.of(src), List.of(jar))).isTrue();
    }

    @Test
    void source_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(src), List.of());
        // Bump mtime forward; the stat will now exceed the stamp time.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, List.of(src), List.of())).isFalse();
    }

    @Test
    void added_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(a), List.of());

        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(b, FileTime.fromMillis(System.currentTimeMillis() - 1000));

        // Source set composition changed → not fresh, even though both files
        // are older than the stamp.
        assertThat(FreshnessStamp.isFresh(classes, List.of(a, b), List.of())).isFalse();
    }

    @Test
    void removed_source_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path a = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path b = writeFile(tempDir.resolve("B.java"), "class B {}");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(a, b), List.of());

        // Caller passes a smaller source list — stamp said it covered two.
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        assertThat(FreshnessStamp.isFresh(classes, List.of(a), List.of())).isFalse();
    }

    @Test
    void missing_input_file_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(src), List.of());

        Files.delete(src);
        assertThat(FreshnessStamp.isFresh(classes, List.of(src), List.of())).isFalse();
    }

    @Test
    void wiping_output_dir_invalidates_stamp(@TempDir Path tempDir) throws IOException {
        // The stamp lives inside the output dir, so removing the dir takes
        // the stamp with it — next build sees no stamp and falls through.
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(src), List.of());

        Files.delete(classes.resolve(".jk-stamp"));
        Files.delete(classes);

        assertThat(FreshnessStamp.isFresh(classes, List.of(src), List.of())).isFalse();
    }

    @Test
    void classpath_touched_after_stamp_is_not_fresh(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Path src = writeFile(tempDir.resolve("A.java"), "class A {}");
        Path jar = writeFile(tempDir.resolve("dep.jar"), "stub");
        FreshnessStamp.write(classes, "compile-main", "key123", List.of(src), List.of(jar));

        // A dep got rebuilt — its mtime is now newer than our stamp.
        Files.setLastModifiedTime(src, FileTime.fromMillis(System.currentTimeMillis() - 1000));
        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertThat(FreshnessStamp.isFresh(classes, List.of(src), List.of(jar))).isFalse();
    }

    private static Path writeFile(Path file, String body) throws IOException {
        Files.writeString(file, body);
        return file;
    }
}
