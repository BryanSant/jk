// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildJournalTest {

    @TempDir
    Path dir;

    private static BuildRecord record(long finishedAt, boolean success, String coord) {
        return new BuildRecord(
                null, BuildRecord.SCHEMA, "build", "/proj", coord,
                finishedAt - 100, finishedAt, 100, success, false, success ? 0 : 1, "9.9-test",
                null, List.of(), List.of(), List.of());
    }

    @Test
    void append_then_get_and_list_roundtrip() {
        BuildJournal j = new BuildJournal(dir);
        String id = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        assertThat(id).isNotNull();
        assertThat(j.get(id)).isPresent();
        assertThat(j.get(id).get().success()).isTrue();
        assertThat(j.get(id).get().coord()).isEqualTo("g:a");
        assertThat(j.list()).hasSize(1);
        assertThat(j.list().get(0).id()).isEqualTo(id);
    }

    @Test
    void list_is_newest_first() {
        BuildJournal j = new BuildJournal(dir);
        String older = j.append(record(1_000_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        String newer = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        List<BuildRecord> list = j.list();
        assertThat(list.get(0).id()).isEqualTo(newer);
        assertThat(list.get(1).id()).isEqualTo(older);
    }

    @Test
    void snapshot_files_are_copied() throws Exception {
        Path md = dir.resolve("src-test-results.md");
        Files.writeString(md, "# Test Results\nall good");
        Path lock = dir.resolve("src-jk.lock");
        Files.writeString(lock, "version = 1");
        BuildJournal j = new BuildJournal(dir);
        String id = j.append(record(1_700_000_000_000L, true, "g:a"),
                new BuildJournal.Snapshot(md, lock, "boom\n"));
        assertThat(j.artifact(id, BuildJournal.TEST_RESULTS_MD)).isPresent();
        assertThat(j.artifact(id, BuildJournal.LOCKFILE)).isPresent();
        assertThat(j.artifact(id, BuildJournal.DIAGNOSTICS_TXT)).isPresent();
        assertThat(Files.readString(j.artifact(id, BuildJournal.TEST_RESULTS_MD).get())).contains("all good");
    }

    @Test
    void delete_removes_the_entry() {
        BuildJournal j = new BuildJournal(dir);
        String id = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
        assertThat(j.delete(id)).isTrue();
        assertThat(j.get(id)).isEmpty();
        assertThat(j.list()).isEmpty();
        assertThat(j.delete(id)).isFalse(); // already gone
    }

    @Test
    void hostile_ids_cannot_escape_the_journal_dir() {
        BuildJournal j = new BuildJournal(dir);
        assertThat(j.get("../secret")).isEmpty();
        assertThat(j.delete("../secret")).isFalse();
        assertThat(j.get("a/b")).isEmpty();
        assertThat(j.artifact("..", BuildJournal.LOCKFILE)).isEmpty();
    }

    @Test
    void concurrent_appends_get_distinct_ids() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        int n = 24;
        CountDownLatch go = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                String id = j.append(record(1_700_000_000_000L, true, "g:a"), BuildJournal.Snapshot.NONE);
                if (id != null) ids.add(id);
            });
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) t.join();
        assertThat(ids).hasSize(n); // same finished-timestamp, still no collisions
        assertThat(j.list()).hasSize(n);
    }

    @Test
    void prune_drops_entries_past_the_age_limit() {
        BuildJournal j = new BuildJournal(dir);
        long now = 1_700_000_000_000L;
        long day = 86_400_000L;
        j.append(record(now - 10 * day, true, "old"), BuildJournal.Snapshot.NONE);
        String fresh = j.append(record(now - 1, true, "new"), BuildJournal.Snapshot.NONE);
        BuildJournal.PruneResult r = j.prune(2 * day, 0, now);
        assertThat(r.removedEntries()).isEqualTo(1);
        assertThat(j.list()).hasSize(1);
        assertThat(j.list().get(0).id()).isEqualTo(fresh);
    }

    @Test
    void prune_enforces_the_disk_budget_oldest_first() throws Exception {
        BuildJournal j = new BuildJournal(dir);
        long now = 1_700_000_000_000L;
        String big = "x".repeat(4096);
        String oldest = j.append(record(now - 3000, true, "a"), new BuildJournal.Snapshot(null, null, big));
        j.append(record(now - 2000, true, "b"), new BuildJournal.Snapshot(null, null, big));
        String newest = j.append(record(now - 1000, true, "c"), new BuildJournal.Snapshot(null, null, big));
        // Budget below the 3-entry total forces at least the oldest out.
        BuildJournal.PruneResult r = j.prune(0, 6000, now);
        assertThat(r.removedEntries()).isGreaterThanOrEqualTo(1);
        List<String> ids = j.list().stream().map(BuildRecord::id).toList();
        assertThat(ids).contains(newest).doesNotContain(oldest);
    }
}
