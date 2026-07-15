// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LruEvictorTest {

    @Test
    void under_budget_is_a_no_op(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        cas.put("a".repeat(100).getBytes());
        cas.put("b".repeat(100).getBytes());
        AccessLedger ledger = new AccessLedger(tempDir.resolve(".access.log"));

        var report = LruEvictor.evictDownTo(cas, 10_000, Set.of(), ledger, false);

        assertThat(report.deleted()).isZero();
        assertThat(report.finalSize()).isEqualTo(200L);
    }

    @Test
    void over_budget_evicts_oldest_first(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path oldest = cas.put("oldest".getBytes());
        Path middle = cas.put("middle-write".getBytes());
        Path newest = cas.put("newest!".getBytes());
        // Stamp distinct mtimes (no ledger → mtime is the fallback signal).
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(now - 30_000));
        Files.setLastModifiedTime(middle, FileTime.fromMillis(now - 20_000));
        Files.setLastModifiedTime(newest, FileTime.fromMillis(now - 10_000));

        AccessLedger ledger = new AccessLedger(tempDir.resolve(".access.log"));
        // Budget = newest's size only — should evict oldest + middle.
        long budget = Files.size(newest);
        var report = LruEvictor.evictDownTo(cas, budget, Set.of(), ledger, false);

        assertThat(Files.exists(oldest)).isFalse();
        assertThat(Files.exists(middle)).isFalse();
        assertThat(Files.exists(newest)).isTrue();
        assertThat(report.deleted()).isEqualTo(2);
    }

    @Test
    void ledger_atime_wins_over_mtime(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path olderMtime = cas.put("older-mtime".getBytes());
        Path newerMtime = cas.put("newer-mtime".getBytes());
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(olderMtime, FileTime.fromMillis(now - 60_000));
        Files.setLastModifiedTime(newerMtime, FileTime.fromMillis(now - 30_000));

        // Ledger flips the order: olderMtime was touched recently;
        // newerMtime hasn't been touched at all.
        AccessLedger ledger = new AccessLedger(tempDir.resolve(".access.log"));
        ledger.touch(cas.hashFromPath(olderMtime).orElseThrow());

        long budget = Files.size(olderMtime);
        LruEvictor.evictDownTo(cas, budget, Set.of(), ledger, false);

        assertThat(Files.exists(olderMtime))
                .as("recently-touched should survive")
                .isTrue();
        assertThat(Files.exists(newerMtime))
                .as("never-touched should be evicted")
                .isFalse();
    }

    @Test
    void reachable_eviction_is_counted_and_reported(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path obj = cas.put("payload".getBytes());
        String hex = cas.hashFromPath(obj).orElseThrow();
        Files.setLastModifiedTime(obj, FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        AccessLedger ledger = new AccessLedger(tempDir.resolve(".access.log"));
        // Budget = 0 forces eviction; declare the object reachable.
        var report = LruEvictor.evictDownTo(cas, 0L, Set.of(hex), ledger, false);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.reachableEvicted()).isEqualTo(1);
    }

    @Test
    void dry_run_reports_without_deleting(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path obj = cas.put("payload".getBytes());
        Files.setLastModifiedTime(obj, FileTime.fromMillis(System.currentTimeMillis() - 60_000));

        var report = LruEvictor.evictDownTo(cas, 0L, Set.of(), new AccessLedger(tempDir.resolve(".access.log")), true);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(Files.exists(obj)).as("dry-run should not delete").isTrue();
    }

    @Test
    void parses_unit_sizes() {
        assertThat(LruEvictor.parseSize("500")).isEqualTo(500L);
        assertThat(LruEvictor.parseSize("500B")).isEqualTo(500L);
        assertThat(LruEvictor.parseSize("1K")).isEqualTo(1024L);
        assertThat(LruEvictor.parseSize("1KB")).isEqualTo(1024L);
        assertThat(LruEvictor.parseSize("1M")).isEqualTo(1024L * 1024);
        assertThat(LruEvictor.parseSize("20G")).isEqualTo(20L * 1024 * 1024 * 1024);
        assertThat(LruEvictor.parseSize("2.5G")).isEqualTo((long) (2.5 * 1024 * 1024 * 1024));
    }

    @Test
    void rejects_unparseable_sizes() {
        assertThatThrownBy(() -> LruEvictor.parseSize("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LruEvictor.parseSize("10XYZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LruEvictor.parseSize("")).isInstanceOf(IllegalArgumentException.class);
    }
}
