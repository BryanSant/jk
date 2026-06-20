// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;

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
        writeBlob(cache.resolve("actions/keys/some-task"), new byte[2048]);

        String stdout = capture(() -> run("cache", "info", "--cache-dir", cache.toString()));
        // Boxed table: title, the two metric rows with compact sizes, a total, and
        // the utilization bar. (Sizes are compact: 5 bytes → "5B", 2048 → "2.0K".)
        assertThat(stdout).contains("Cache Directory Information");
        assertThat(stdout).contains("CAS Blobs").contains("5B");
        assertThat(stdout).contains("Action Cache").contains("2.0K");
        assertThat(stdout).contains("Total");
        assertThat(stdout).contains("Utilization");
    }

    @Test
    void prune_removes_stale_action_entries_and_tmp_files(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/keys/old"), new byte[256]);
        Path fresh = writeBlob(cache.resolve("actions/keys/new"), new byte[256]);
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
        // New summary format breaks the count out by phase.
        assertThat(stdout)
                .contains("records expired 1")
                .contains("temps 1");
    }

    @Test
    void prune_dry_run_does_not_delete(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/keys/old"), new byte[1024]);
        Files.setLastModifiedTime(stale,
                FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "prune",
                "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(Files.exists(stale)).isTrue();
        assertThat(stdout)
                .startsWith("Would prune")
                .contains("records expired 1");
    }

    @Test
    void purge_with_yes_wipes_contents_but_keeps_root(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);
        writeBlob(cache.resolve("actions/keys/task1"), new byte[1024]);

        String stdout = capture(() -> run("cache", "purge", "--cache-dir", cache.toString(), "--yes"));

        assertThat(stdout).contains("Removed 2 files");
        assertThat(Files.exists(cache)).isTrue();
        try (var stream = Files.list(cache)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void purge_aborts_when_not_confirmed(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = withStdin("n\n", () ->
                capture(() -> run("cache", "purge", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("Aborted");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isTrue();
    }

    @Test
    void purge_proceeds_on_yes_at_the_prompt(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = withStdin("y\n", () ->
                capture(() -> run("cache", "purge", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("Removed 1 files");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isFalse();
    }

    @Test
    void purge_dry_run_reports_without_deleting_or_prompting(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = capture(() -> run("cache", "purge",
                "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(stdout).contains("Would remove 1 files");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isTrue();
    }

    @Test
    void purge_missing_cache_dir_is_a_noop(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "purge", "--cache-dir", cache.toString(), "--yes"));
        assertThat(stdout).contains("nothing to purge");
    }

    @Test
    void search_lists_cached_coordinates_with_versions(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.17.1");
        seedRepo(cache, "com.google.guava", "guava", "33.0.0-jre");

        // Coordinates print in color; strip ANSI to assert on the visible text.
        String stdout = stripAnsi(capture(() -> run("cache", "search", "jackson",
                "--cache-dir", cache.toString())));

        assertThat(stdout).contains("com.fasterxml.jackson.core:jackson-databind");
        // newest-first version ordering
        assertThat(stdout).contains("2.18.2, 2.17.1");
        assertThat(stdout).doesNotContain("guava");
        assertThat(stdout).contains("1 coordinate, 2 versions cached");
    }

    @Test
    void search_with_no_matches_returns_nonzero(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        int exit = run("cache", "search", "nonexistent", "--cache-dir", cache.toString());
        assertThat(exit).isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------

    /** Materialise a jar for {@code group:artifact:version} into the m2 local repo. */
    private static void seedRepo(Path cache, String group, String artifact, String version) {
        try {
            dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(cache);
            Path blob = cas.put((group + ":" + artifact + ":" + version).getBytes(StandardCharsets.UTF_8));
            var coord = dev.jkbuild.model.Coordinate.of(group, artifact, version);
            new dev.jkbuild.repo.JkMavenLocalRepo(cache)
                    .materialize(dev.jkbuild.repo.MavenLayout.artifactPath(coord), blob);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\\033\\[[0-9;?]*[a-zA-Z]", "");
    }

    private static Path writeBlob(Path file, byte[] body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        return file;
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }

    /** Run {@code body} with {@code System.in} fed from {@code input}. */
    private static String withStdin(String input, java.util.function.Supplier<String> body) {
        var original = System.in;
        System.setIn(new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        try {
            return body.get();
        } finally {
            System.setIn(original);
        }
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
