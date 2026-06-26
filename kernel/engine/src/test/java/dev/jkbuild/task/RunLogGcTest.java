// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLogGcTest {

    @Test
    void absent_runs_dir_is_no_op(@TempDir Path tempDir) throws IOException {
        var r = RunLogGc.sweep(tempDir, RunLogGc.DEFAULT_TTL, false);
        assertThat(r.deleted()).isZero();
    }

    @Test
    void old_logs_get_swept(@TempDir Path tempDir) throws IOException {
        Path runs = tempDir.resolve("runs");
        Files.createDirectories(runs);
        Path stale = Files.writeString(runs.resolve("ancient.ndjson"), "...");
        Path fresh = Files.writeString(runs.resolve("today.ndjson"), "...");
        // Backdate the stale log to 10 days ago.
        Files.setLastModifiedTime(
                stale,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(10).toMillis()));

        var r = RunLogGc.sweep(tempDir, RunLogGc.DEFAULT_TTL, false);

        assertThat(r.deleted()).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void dry_run_reports_but_doesnt_delete(@TempDir Path tempDir) throws IOException {
        Path runs = tempDir.resolve("runs");
        Files.createDirectories(runs);
        Path stale = Files.writeString(runs.resolve("old.ndjson"), "...");
        Files.setLastModifiedTime(
                stale,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(10).toMillis()));

        var r = RunLogGc.sweep(tempDir, RunLogGc.DEFAULT_TTL, true);

        assertThat(r.deleted()).isEqualTo(1);
        assertThat(Files.exists(stale)).isTrue();
    }
}
