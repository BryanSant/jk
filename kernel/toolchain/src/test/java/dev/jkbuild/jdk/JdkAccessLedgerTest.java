// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkAccessLedgerTest {

    @Test
    void touch_appends_a_journal_line(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".access.log");
        JdkAccessLedger ledger = new JdkAccessLedger(file);

        ledger.touch("temurin-21.0.5", "resolve");
        ledger.touch("temurin-21.0.5", "resolve");

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(body.split("\n")).hasSize(2);
        assertThat(body).contains("\tresolve\ttemurin-21.0.5");
    }

    @Test
    void latest_by_identifier_aggregates_events(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".access.log");
        // Hand-stitch the journal so we can control the timestamps.
        Files.writeString(file, """
                100\tinstall\ttemurin-21.0.5
                200\tresolve\ttemurin-21.0.5
                300\tresolve\ttemurin-21.0.5
                150\tinstall\tcorretto-25.0.3
                """);
        JdkAccessLedger ledger = new JdkAccessLedger(file);

        var map = ledger.latestByIdentifier();

        assertThat(map.get("temurin-21.0.5").millis()).isEqualTo(300L);
        assertThat(map.get("temurin-21.0.5").event()).isEqualTo("resolve");
        assertThat(map.get("temurin-21.0.5").count()).isEqualTo(3);
        assertThat(map.get("corretto-25.0.3").millis()).isEqualTo(150L);
        assertThat(map.get("corretto-25.0.3").count()).isEqualTo(1);
    }

    @Test
    void most_recent_first_orders_by_latest_event(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".access.log");
        Files.writeString(file, """
                100\tresolve\told
                500\tresolve\tnew
                300\tresolve\tmedium
                """);
        JdkAccessLedger ledger = new JdkAccessLedger(file);

        var ordered = ledger.mostRecentFirst();
        assertThat(ordered).extracting(JdkAccessLedger.Entry::identifier)
                .containsExactly("new", "medium", "old");
    }

    @Test
    void touch_is_silent_on_io_failure(@TempDir Path tempDir) {
        // Point at a path inside a regular file → mkdir fails → touch
        // silently swallows the error. Test asserts the call returns
        // without throwing.
        Path bogus = tempDir.resolve("notADir").resolve("nested").resolve("file");
        try {
            Files.writeString(tempDir.resolve("notADir"), "blocker");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        new JdkAccessLedger(bogus).touch("foo", "resolve");
        // No assertion — just "didn't throw."
    }

    @Test
    void compact_rewrites_above_threshold(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve(".access.log");
        JdkAccessLedger ledger = new JdkAccessLedger(file);
        // Generate > 1 MiB of duplicate touches against a handful of ids.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 30_000; i++) {
            big.append(i).append("\tresolve\ttemurin-21.0.5\n");
            big.append(i).append("\tresolve\tcorretto-25.0.3\n");
        }
        Files.writeString(file, big.toString());
        assertThat(Files.size(file)).isGreaterThan(1L * 1024 * 1024);

        long after = ledger.compactIfLarge();

        assertThat(after).isLessThan(1L * 1024 * 1024);
        // Compacted body has exactly one line per identifier.
        assertThat(Files.readString(file).split("\n"))
                .filteredOn(line -> !line.isEmpty()).hasSize(2);
    }
}
