// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import dev.jkbuild.model.Coordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JournalTest {

    private static final Coordinate WIDGET = Coordinate.of("com.example", "widget", "1.0");

    @Test
    void records_and_looks_up_a_blob(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(WIDGET, "pom", "abc123", 42, "central", "https://repo/widget-1.0.pom");

        Optional<Journal.Blob> found = journal.lookup(WIDGET, "pom");
        assertThat(found).isPresent();
        assertThat(found.get().sha256()).isEqualTo("abc123");
        assertThat(found.get().size()).isEqualTo(42);
        assertThat(found.get().repo()).isEqualTo("central");
        assertThat(found.get().url()).isEqualTo("https://repo/widget-1.0.pom");
    }

    @Test
    void distinct_kinds_coexist_in_one_gav(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(WIDGET, "pom", "pomsha", 1, "central", "u1");
        journal.record(WIDGET, "jar", "jarsha", 2, "central", "u2");

        assertThat(journal.lookup(WIDGET, "pom")).map(Journal.Blob::sha256).contains("pomsha");
        assertThat(journal.lookup(WIDGET, "jar")).map(Journal.Blob::sha256).contains("jarsha");
    }

    @Test
    void versions_lists_locally_present_versions(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(Coordinate.of("com.example", "widget", "1.0"), "pom", "a", 1, "c", "u");
        journal.record(Coordinate.of("com.example", "widget", "2.0"), "pom", "b", 1, "c", "u");
        journal.record(Coordinate.of("com.example", "widget", "1.5"), "jar", "d", 1, "c", "u");

        assertThat(journal.versions("com.example", "widget"))
                .containsExactlyInAnyOrder("1.0", "1.5", "2.0");
    }

    @Test
    void versions_of_unknown_artifact_is_empty(@TempDir Path cache) {
        assertThat(new Journal(cache).versions("no.such", "thing")).isEmpty();
    }

    @Test
    void lookup_of_unknown_kind_is_empty(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(WIDGET, "pom", "abc", 1, "c", "u");
        assertThat(journal.lookup(WIDGET, "jar")).isEmpty();
    }

    @Test
    void differing_sha_for_same_kind_is_overwritten(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(WIDGET, "pom", "old", 1, "c", "u");
        journal.record(WIDGET, "pom", "new", 2, "c2", "u2");

        assertThat(journal.lookup(WIDGET, "pom")).map(Journal.Blob::sha256).contains("new");
        assertThat(journal.lookup(WIDGET, "pom")).map(Journal.Blob::size).contains(2L);
    }

    @Test
    void modules_lists_every_cached_coordinate_with_versions(@TempDir Path cache) {
        Journal journal = new Journal(cache);
        journal.record(Coordinate.of("com.example", "widget", "1.0"), "pom", "a", 1, "c", "u");
        journal.record(Coordinate.of("com.example", "widget", "2.0"), "jar", "b", 1, "c", "u");
        journal.record(Coordinate.of("org.acme.tools", "gadget", "3.1"), "pom", "d", 1, "c", "u");

        List<Journal.Module> modules = journal.modules();
        assertThat(modules).extracting(Journal.Module::moduleKey)
                .containsExactlyInAnyOrder("com.example:widget", "org.acme.tools:gadget");
        Journal.Module widget = modules.stream()
                .filter(m -> m.moduleKey().equals("com.example:widget")).findFirst().orElseThrow();
        assertThat(widget.group()).isEqualTo("com.example");
        assertThat(widget.artifact()).isEqualTo("widget");
        assertThat(widget.versions()).containsExactlyInAnyOrder("1.0", "2.0");
    }

    @Test
    void modules_of_empty_journal_is_empty(@TempDir Path cache) {
        assertThat(new Journal(cache).modules()).isEmpty();
    }

    @Test
    void none_is_a_no_op(@TempDir Path cache) {
        Journal.NONE.record(WIDGET, "pom", "abc", 1, "c", "u");
        assertThat(Journal.NONE.lookup(WIDGET, "pom")).isEmpty();
        assertThat(Journal.NONE.versions("com.example", "widget")).isEmpty();
        assertThat(Journal.NONE.modules()).isEmpty();
    }

    @Test
    void concurrent_writes_to_same_gav_are_safe(@TempDir Path cache) throws Exception {
        Journal journal = new Journal(cache);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                String kind = "kind" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        journal.record(WIDGET, kind, "sha" + kind, 1, "c", "u");
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // Every concurrent writer's blob survived the read-modify-write merges.
        for (int i = 0; i < threads; i++) {
            assertThat(journal.lookup(WIDGET, "kind" + i)).isPresent();
        }
    }

    @Test
    void survives_a_corrupt_gav_file(@TempDir Path cache) throws Exception {
        Journal journal = new Journal(cache);
        journal.record(WIDGET, "pom", "abc", 1, "c", "u");
        // Corrupt the on-disk entry; lookup should degrade to empty, not throw.
        Path gav = cache.resolve("journal").resolve("maven")
                .resolve("com/example").resolve("widget").resolve("1.0.toml");
        java.nio.file.Files.writeString(gav, "this is { not ] valid toml");

        assertThat(journal.lookup(WIDGET, "pom")).isEmpty();
        assertThat(List.of()).isEqualTo(journal.versions("com.example", "missing"));
    }
}
