// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactMapTest {

    @Test
    void put_appends_a_line(@TempDir Path tempDir) throws IOException {
        ArtifactMap map = new ArtifactMap(tempDir.resolve(".artifact.map"));

        map.put("abc123", "com.example:widget:1.0");

        String body = Files.readString(tempDir.resolve(".artifact.map"), StandardCharsets.UTF_8);
        assertThat(body.trim()).isEqualTo("abc123\tcom.example:widget:1.0");
    }

    @Test
    void to_map_deduplicates_on_read(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".artifact.map");
        Files.writeString(file, """
                abc123\tcom.example:widget:1.0
                def456\tcom.example:gadget:2.0
                abc123\tcom.example:widget:1.0
                """);
        ArtifactMap map = new ArtifactMap(file);

        var result = map.toMap();

        assertThat(result).hasSize(2);
        assertThat(result.get("abc123")).isEqualTo("com.example:widget:1.0");
        assertThat(result.get("def456")).isEqualTo("com.example:gadget:2.0");
    }

    @Test
    void to_map_returns_empty_when_file_absent(@TempDir Path tempDir) throws IOException {
        ArtifactMap map = new ArtifactMap(tempDir.resolve(".artifact.map"));

        assertThat(map.toMap()).isEmpty();
    }

    @Test
    void put_is_silent_on_io_failure(@TempDir Path tempDir) throws IOException {
        Path bogus = tempDir.resolve("notADir").resolve("nested").resolve("file");
        Files.writeString(tempDir.resolve("notADir"), "blocker");

        new ArtifactMap(bogus).put("abc123", "com.example:widget:1.0");
        // No assertion — just "didn't throw."
    }

    @Test
    void compact_rewrites_above_threshold(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".artifact.map");
        ArtifactMap map = new ArtifactMap(file);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 15_000; i++) {
            big.append("abc123\tcom.example:widget:1.0\n");
            big.append("def456\tcom.example:gadget:2.0\n");
        }
        Files.writeString(file, big.toString());
        assertThat(Files.size(file)).isGreaterThan(1L * 1024 * 1024);

        long after = map.compactIfLarge();

        assertThat(after).isLessThan(1L * 1024 * 1024);
        assertThat(Files.readString(file).split("\n"))
                .filteredOn(line -> !line.isEmpty()).hasSize(2);
    }
}
