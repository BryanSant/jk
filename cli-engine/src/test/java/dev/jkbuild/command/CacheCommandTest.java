// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.Jk;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "prune", "--cache-dir", cache.toString(), "--older-than", "30"));

        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(leftoverTmp)).isFalse();
        assertThat(Files.exists(keptBlob)).isTrue();
        // New summary format breaks the count out by phase.
        assertThat(stdout).contains("Finished pruning cache").contains("removed");
    }

    @Test
    void prune_dry_run_does_not_delete(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path stale = writeBlob(cache.resolve("actions/keys/old"), new byte[1024]);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS)));

        String stdout = capture(() -> run("cache", "prune", "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(Files.exists(stale)).isTrue();
        assertThat(stdout).contains("Dry run: would remove");
    }

    @Test
    void purge_with_yes_wipes_contents_but_keeps_root(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);
        writeBlob(cache.resolve("actions/keys/task1"), new byte[1024]);

        String stdout = capture(() -> run("cache", "purge", "--cache-dir", cache.toString(), "--yes"));

        assertThat(stdout).contains("Purged 2 files");
        assertThat(Files.exists(cache)).isTrue();
        try (var stream = Files.list(cache)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void purge_aborts_when_not_confirmed(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = withStdin("n\n", () -> capture(() -> run("cache", "purge", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("aborted");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isTrue();
    }

    @Test
    void purge_proceeds_on_yes_at_the_prompt(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = withStdin("y\n", () -> capture(() -> run("cache", "purge", "--cache-dir", cache.toString())));

        assertThat(stdout).contains("Purged 1 files");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isFalse();
    }

    @Test
    void purge_dry_run_reports_without_deleting_or_prompting(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        writeBlob(cache.resolve("sha256/ab/cd/deadbeef"), new byte[4096]);

        String stdout = capture(() -> run("cache", "purge", "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(stdout).contains("Dry run: would remove");
        assertThat(Files.exists(cache.resolve("sha256/ab/cd/deadbeef"))).isTrue();
    }

    @Test
    void purge_missing_cache_dir_is_a_noop(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        String stdout = capture(() -> run("cache", "purge", "--cache-dir", cache.toString(), "--yes"));
        assertThat(stdout).contains("Nothing to purge");
    }

    @Test
    void search_lists_cached_coordinates_with_versions(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.18.2");
        seedRepo(cache, "com.fasterxml.jackson.core", "jackson-databind", "2.17.1");
        seedRepo(cache, "com.google.guava", "guava", "33.0.0-jre");

        // Coordinates print in color; strip ANSI to assert on the visible text.
        String stdout = stripAnsi(capture(() -> run("cache", "search", "jackson", "--cache-dir", cache.toString())));

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

    @Test
    void clear_requires_a_jk_toml(@TempDir Path tempDir) {
        Path cache = tempDir.resolve("cache");
        int exit = run("cache", "clear", "-C", tempDir.toString(), "--cache-dir", cache.toString(), "--yes");
        // Exit.CONFIG — no jk.toml in the working dir.
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void clear_with_yes_invalidates_this_projects_entries(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("proj");
        writeProject(proj, "com.example", "proj", "0.1.0");
        Path cache = tempDir.resolve("cache");
        String tag = classesTag(proj);
        seedRecord(cache, "keyProject", "compile-main@" + tag, null);
        seedRecord(cache, "keyTests", "run-tests@" + tag, null); // path-less: matched by tag
        seedRecord(cache, "keyOther", "compile-main@ffffffffffff", null); // different project

        String stdout = capture(() ->
                run("cache", "clear", "-C", proj.toString(), "--cache-dir", cache.toString(), "--yes"));

        assertThat(Files.exists(cache.resolve("actions/keys/keyProject"))).isFalse();
        assertThat(Files.exists(cache.resolve("actions/keys/keyTests"))).isFalse();
        assertThat(Files.exists(cache.resolve("actions/tasks/compile-main@" + tag))).isFalse();
        assertThat(Files.exists(cache.resolve("actions/keys/keyOther"))).isTrue();
        // Two records matched (compile + test), each with its tasks/ pointer → 4 files removed.
        assertThat(stdout).contains("Invalidated").contains("4 cache entries");
    }

    @Test
    void clear_matches_records_by_input_path(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("proj");
        writeProject(proj, "com.example", "proj", "0.1.0");
        Path cache = tempDir.resolve("cache");
        // Unknown tag, but an INPUT source path under the project → still this project's.
        String src = proj.toAbsolutePath().normalize().resolve("src/main/java/A.java").toString();
        seedRecord(cache, "keyPath", "compile-main@ffffffffffff", "INPUT abc123 " + src);

        run("cache", "clear", "-C", proj.toString(), "--cache-dir", cache.toString(), "--yes");

        assertThat(Files.exists(cache.resolve("actions/keys/keyPath"))).isFalse();
        assertThat(Files.exists(cache.resolve("actions/tasks/compile-main@ffffffffffff")))
                .isFalse();
    }

    @Test
    void clear_cascades_to_workspace_modules(@TempDir Path tempDir) throws Exception {
        Files.writeString(
                tempDir.resolve("jk.toml"),
                """
                [project]
                group = "com.example"
                name  = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["mod"]
                """,
                StandardCharsets.UTF_8);
        Path mod = tempDir.resolve("mod");
        writeProject(mod, "com.example", "mod", "1.0.0");
        Path cache = tempDir.resolve("cache");
        seedRecord(cache, "keyMod", "compile-main@" + classesTag(mod), null);

        // Clear from the workspace root — the module's entry must go too (the cascade).
        run("cache", "clear", "-C", tempDir.toString(), "--cache-dir", cache.toString(), "--yes");

        assertThat(Files.exists(cache.resolve("actions/keys/keyMod"))).isFalse();
    }

    @Test
    void clear_dry_run_reports_without_deleting(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("proj");
        writeProject(proj, "com.example", "proj", "0.1.0");
        Path cache = tempDir.resolve("cache");
        seedRecord(cache, "keyProject", "compile-main@" + classesTag(proj), null);

        String stdout = capture(() ->
                run("cache", "clear", "-C", proj.toString(), "--cache-dir", cache.toString(), "--dry-run"));

        assertThat(Files.exists(cache.resolve("actions/keys/keyProject"))).isTrue();
        assertThat(stdout).contains("Dry run: would invalidate");
    }

    @Test
    void clear_aborts_when_not_confirmed(@TempDir Path tempDir) throws Exception {
        Path proj = tempDir.resolve("proj");
        writeProject(proj, "com.example", "proj", "0.1.0");
        Path cache = tempDir.resolve("cache");
        seedRecord(cache, "keyProject", "compile-main@" + classesTag(proj), null);

        String stdout = withStdin(
                "n\n",
                () -> capture(() -> run("cache", "clear", "-C", proj.toString(), "--cache-dir", cache.toString())));

        assertThat(stdout).contains("aborted");
        assertThat(Files.exists(cache.resolve("actions/keys/keyProject"))).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    /** Minimal buildable project manifest. */
    private static void writeProject(Path dir, String group, String name, String version) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group = "%s"
                name  = "%s"
                version = "%s"
                java = 25
                """
                        .formatted(group, name, version),
                StandardCharsets.UTF_8);
    }

    /** The qualified-task tag the build would use for {@code projectDir}'s main classes dir. */
    private static String classesTag(Path projectDir) throws Exception {
        Path norm = projectDir.toAbsolutePath().normalize();
        dev.jkbuild.model.JkBuild jb = dev.jkbuild.config.JkBuildParser.parse(norm.resolve("jk.toml"));
        return dev.jkbuild.task.ActionKey.taskTag(
                dev.jkbuild.layout.BuildLayout.of(norm, jb).classesDir());
    }

    /** Write an action record ({@code keys/<key>}) plus its {@code tasks/<taskId>} pointer. */
    private static void seedRecord(Path cache, String key, String taskId, String extraInputLine) throws Exception {
        Path keyFile = cache.resolve("actions/keys").resolve(key);
        Files.createDirectories(keyFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("TASK ").append(taskId).append('\n');
        sb.append("KEY ").append(key).append('\n');
        if (extraInputLine != null) sb.append(extraInputLine).append('\n');
        sb.append("OUTPUT deadbeef foo.class\n");
        Files.writeString(keyFile, sb.toString());
        Path ptr = cache.resolve("actions/tasks").resolve(taskId);
        Files.createDirectories(ptr.getParent());
        Files.writeString(ptr, key);
    }

    /** Materialise a jar for {@code group:artifact:version} into the "central" named-repo store. */
    private static void seedRepo(Path cache, String group, String artifact, String version) {
        try {
            byte[] bytes = (group + ":" + artifact + ":" + version).getBytes(StandardCharsets.UTF_8);
            dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(cache);
            Path blob = cas.put(bytes);
            var coord = dev.jkbuild.model.Coordinate.of(group, artifact, version);
            dev.jkbuild.repo.RepoArtifactStore.forRepoName(cache, "central")
                    .materialize(
                            dev.jkbuild.repo.MavenLayout.artifactPath(coord),
                            blob,
                            dev.jkbuild.util.Hashing.sha256Hex(bytes));
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
    private static String withStdin(String input, Supplier<String> body) {
        var original = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
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
