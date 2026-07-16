// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasPrewriterTest {

    @Test
    void finish_picks_up_files_added_during_watching(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        try {
            Files.writeString(classes.resolve("A.class"), "AAAAAA");
            Files.writeString(classes.resolve("B.class"), "BBBBBB");
            // Give the poller a couple of cycles to discover + settle them.
            Thread.sleep(350);
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsKeys("A.class", "B.class");
            assertThat(outputs.get("A.class")).isEqualTo(Hashing.sha256Hex("AAAAAA".getBytes()));
            assertThat(outputs.get("B.class")).isEqualTo(Hashing.sha256Hex("BBBBBB".getBytes()));
            // Both shas should be hard-linked into the CAS.
            assertThat(Files.exists(cas.pathFor(outputs.get("A.class")))).isTrue();
            assertThat(Files.exists(cas.pathFor(outputs.get("B.class")))).isTrue();
        }
    }

    @Test
    void finish_catches_files_missed_by_the_poller(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        // Write the file just before finish — poller won't have had time
        // to see it twice, so the final pass should handle it.
        Files.writeString(classes.resolve("Late.class"), "late-content");

        Map<String, String> outputs = prewriter.finish();

        assertThat(outputs).containsEntry("Late.class", Hashing.sha256Hex("late-content".getBytes()));
        assertThat(Files.exists(cas.pathFor(outputs.get("Late.class")))).isTrue();
    }

    @Test
    void finish_uses_latest_content_when_file_is_modified_after_pre_processing(@TempDir Path tempDir) throws Exception {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        try {
            Path file = classes.resolve("Mut.class");
            Files.writeString(file, "first-version");
            // Let pre-processing happen.
            Thread.sleep(350);
            // Now mutate the file — the recorded snapshot will mismatch
            // current state, so finish() should re-hash.
            Files.writeString(file, "second-version-longer");
        } finally {
            Map<String, String> outputs = prewriter.finish();
            assertThat(outputs).containsEntry("Mut.class", Hashing.sha256Hex("second-version-longer".getBytes()));
        }
    }

    @Test
    void freshness_stamp_is_not_treated_as_an_output(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));
        // Simulate left-over freshness stamps from a previous build.
        Files.writeString(classes.resolve(FreshnessStamp.JAVA_STAMP), "stamp body");
        Files.writeString(classes.resolve(FreshnessStamp.KOTLIN_STAMP), "stamp body");

        CasPrewriter prewriter = CasPrewriter.watching(cas, classes);
        Map<String, String> outputs = prewriter.finish();

        assertThat(outputs).doesNotContainKey(FreshnessStamp.JAVA_STAMP);
        assertThat(outputs).doesNotContainKey(FreshnessStamp.KOTLIN_STAMP);
    }

    @Test
    void empty_output_dir_yields_empty_map(@TempDir Path tempDir) throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Cas cas = new Cas(tempDir.resolve("cas"));

        Map<String, String> outputs = CasPrewriter.watching(cas, classes).finish();

        assertThat(outputs).isEmpty();
    }
}
