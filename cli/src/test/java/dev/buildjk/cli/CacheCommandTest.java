// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CacheCommandTest {

    @Test
    void dir_prints_cache_root(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "dir", "--cache-dir", cache.toString()));
        assertThat(stdout.trim()).isEqualTo(cache.toString());
    }

    @Test
    void info_summarizes_an_empty_cache_without_creating_it(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "info", "--cache-dir", cache.toString()));
        assertThat(stdout).contains("not yet created");
        assertThat(Files.exists(cache)).isFalse();
    }

    @Test
    void info_reports_blob_counts_and_sizes(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), "hello".getBytes(StandardCharsets.UTF_8));
        writeBlob(cache.resolve("actions/by-key/some-task"), new byte[2048]);

        String stdout = capture(() -> run("cache", "info", "--cache-dir", cache.toString()));
        assertThat(stdout).contains("CAS blobs:     1 files, 5 B");
        assertThat(stdout).contains("Action cache:  1 files, 2.0 KiB");
        assertThat(stdout).contains("Total:         2 files,");
    }

    @Test
    void prune_removes_stale_action_entries_and_tmp_files(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/by-key/old"), new byte[256]);
        Path fresh = writeBlob(cache.resolve("actions/by-key/new"), new byte[256]);
        Path leftoverTmp = writeBlob(cache.resolve("sha256/ab/cd/.put-abc.tmp"), new byte[128]);
        Path keptBlob = writeBlob(cache.resolve("sha256/ab/cd/realblob"), new byte[128]);

        // Backdate the stale entry by 60 days.
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "prune",
                "--cache-dir", cache.toString(), "--older-than", "30"));

        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(leftoverTmp)).isFalse();
        assertThat(Files.exists(keptBlob)).isTrue();
        assertThat(stdout).contains("Pruned 2 entries");
    }

    @Test
    void prune_dry_run_does_not_delete(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/by-key/old"), new byte[1024]);
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "prune",
                "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(Files.exists(stale)).isTrue();
        assertThat(stdout).contains("Would prune 1 entries");
    }

    @Test
    void clean_wipes_contents_but_keeps_root(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);
        writeBlob(cache.resolve("actions/by-key/task1"), new byte[1024]);

        String stdout = capture(() -> run("cache", "clean", "--cache-dir", cache.toString()));

        assertThat(stdout).contains("Removed 2 files");
        assertThat(Files.exists(cache)).isTrue();
        try (var stream = Files.list(cache)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void clean_dry_run_reports_without_deleting(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = capture(() -> run("cache", "clean",
                "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(stdout).contains("Would remove 1 files");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isTrue();
    }

    @Test
    void clean_missing_cache_dir_is_a_noop(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "clean", "--cache-dir", cache.toString()));
        assertThat(stdout).contains("nothing to clean");
    }

    // --- helpers -----------------------------------------------------------

    private static Path writeBlob(Path file, byte[] body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        return file;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
