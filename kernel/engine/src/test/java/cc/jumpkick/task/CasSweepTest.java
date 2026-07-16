// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasSweepTest {

    @Test
    void unreferenced_object_gets_swept(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path live = cas.put("alive".getBytes());
        Path dead = cas.put("dead".getBytes());
        backdate(dead);
        backdate(live);

        String liveHex = cas.hashFromPath(live).orElseThrow();
        var report = CasSweep.sweep(cas, Set.of(liveHex), false);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.kept()).isEqualTo(1);
        assertThat(Files.exists(dead)).isFalse();
        assertThat(Files.exists(live)).isTrue();
    }

    @Test
    void min_age_protects_freshly_written_objects(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        // Just-written → mtime is "now" → inside MIN_AGE_FOR_SWEEP window.
        Path freshOrphan = cas.put("just-written".getBytes());

        var report = CasSweep.sweep(cas, Set.of(), false);

        assertThat(Files.exists(freshOrphan))
                .as("MIN_AGE_FOR_SWEEP should protect newly-written objects")
                .isTrue();
        assertThat(report.deleted()).isEqualTo(0);
    }

    @Test
    void concurrent_write_watermark_is_observed(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path obj = cas.put("payload".getBytes());
        // Stamp the file with a future mtime — simulates a writer that wrote
        // AFTER the sweep started.
        Files.setLastModifiedTime(obj, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        var report = CasSweep.sweep(cas, Set.of(), false);

        assertThat(Files.exists(obj)).isTrue();
        assertThat(report.deleted()).isEqualTo(0);
    }

    @Test
    void dry_run_reports_without_deleting(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir);
        Path dead = cas.put("dead".getBytes());
        backdate(dead);

        var report = CasSweep.sweep(cas, Set.of(), true);

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(Files.exists(dead)).as("dry run shouldn't actually delete").isTrue();
    }

    @Test
    void tmp_files_are_skipped(@TempDir Path tempDir) throws IOException {
        // .put-<rand>.tmp leftovers belong to the janitor step, not the sweep.
        Cas cas = new Cas(tempDir);
        Path shaDir = tempDir.resolve("sha256/aa/bb");
        Files.createDirectories(shaDir);
        Path tmp = Files.writeString(shaDir.resolve(".put-abc.tmp"), "incomplete");
        backdate(tmp);

        var report = CasSweep.sweep(cas, Set.of(), false);

        assertThat(Files.exists(tmp)).isTrue();
        assertThat(report.deleted()).isEqualTo(0);
    }

    /** Bump mtime far enough into the past to clear MIN_AGE_FOR_SWEEP. */
    private static void backdate(Path file) throws IOException {
        long past = System.currentTimeMillis() - Sweep.MIN_AGE_FOR_SWEEP.toMillis() - 60_000L;
        Files.setLastModifiedTime(file, FileTime.fromMillis(past));
    }
}
