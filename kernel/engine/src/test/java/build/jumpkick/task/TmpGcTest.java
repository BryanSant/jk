// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TmpGcTest {

    @Test
    void absent_dir_is_no_op(@TempDir Path tempDir) throws IOException {
        var r = TmpGc.sweep(tempDir.resolve("nope"), TmpGc.DEFAULT_TTL, false);
        assertThat(r.deleted()).isZero();
    }

    @Test
    void files_older_than_7_days_are_swept_recursively(@TempDir Path tmp) throws IOException {
        Path stale = Files.writeString(tmp.resolve("a-b-1.0.0-1-pom.xml-import.md"), "x");
        Path fresh = Files.writeString(tmp.resolve("a-b-2.0.0-1-pom.xml-import.md"), "x");
        Path nestedStale = tmp.resolve("sub/old.md");
        Files.createDirectories(nestedStale.getParent());
        Files.writeString(nestedStale, "x");
        backdate(stale, 8);
        backdate(nestedStale, 30);

        var r = TmpGc.sweep(tmp, TmpGc.DEFAULT_TTL, false);

        assertThat(r.deleted()).isEqualTo(2);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(nestedStale)).isFalse();
        assertThat(Files.exists(fresh)).isTrue(); // under 7 days survives
    }

    @Test
    void dry_run_reports_but_keeps_files(@TempDir Path tmp) throws IOException {
        Path stale = Files.writeString(tmp.resolve("old-import.md"), "x");
        backdate(stale, 10);

        var r = TmpGc.sweep(tmp, TmpGc.DEFAULT_TTL, true);

        assertThat(r.deleted()).isEqualTo(1);
        assertThat(Files.exists(stale)).isTrue();
    }

    private static void backdate(Path file, int days) throws IOException {
        Files.setLastModifiedTime(
                file,
                FileTime.fromMillis(
                        System.currentTimeMillis() - Duration.ofDays(days).toMillis()));
    }
}
