// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.task;

import dev.buildjk.cache.Cas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionCacheTest {

    @Test
    void store_then_lookup_round_trip(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs.resolve("nested"));
        Files.writeString(outputs.resolve("a.class"), "alpha");
        Files.writeString(outputs.resolve("nested/b.class"), "beta");

        Map<String, String> inputs = Map.of("src/Foo.java", "abc123");
        cache.store("compile-main", "key1", inputs, outputs);

        var record = cache.lookup("key1").orElseThrow();
        assertThat(record.taskId()).isEqualTo("compile-main");
        assertThat(record.actionKey()).isEqualTo("key1");
        assertThat(record.outputs()).containsOnlyKeys("a.class", "nested/b.class");
        assertThat(record.inputs()).containsEntry("src/Foo.java", "abc123");
    }

    @Test
    void restore_recreates_outputs_from_cas(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "alpha-content");

        cache.store("compile-main", "key1", Map.of(), outputs);

        // Wipe outputs, restore from cache.
        Files.delete(outputs.resolve("a.class"));
        Files.delete(outputs);

        cache.restore(cache.lookup("key1").orElseThrow(), outputs);
        assertThat(outputs.resolve("a.class")).exists();
        assertThat(Files.readString(outputs.resolve("a.class"))).isEqualTo("alpha-content");
    }

    @Test
    void restore_cleans_stale_files(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("kept.class"), "k");
        cache.store("compile-main", "key1", Map.of(), outputs);

        // Add a stale file before restoring.
        Files.writeString(outputs.resolve("stale.class"), "s");
        cache.restore(cache.lookup("key1").orElseThrow(), outputs);

        assertThat(outputs.resolve("stale.class")).doesNotExist();
        assertThat(outputs.resolve("kept.class")).exists();
    }

    @Test
    void last_for_task_returns_most_recent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "v1");
        cache.store("compile-main", "key-v1", Map.of("input", "h1"), outputs);

        Files.writeString(outputs.resolve("a.class"), "v2");
        cache.store("compile-main", "key-v2", Map.of("input", "h2"), outputs);

        assertThat(cache.lastFor("compile-main").orElseThrow().actionKey()).isEqualTo("key-v2");
        // The v1 record is still independently retrievable by key.
        assertThat(cache.lookup("key-v1").orElseThrow().inputs()).containsEntry("input", "h1");
    }

    @Test
    void lookup_returns_empty_for_unknown_key(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        assertThat(cache.lookup("nonexistent")).isEmpty();
        assertThat(cache.lastFor("compile-main")).isEmpty();
    }
}
