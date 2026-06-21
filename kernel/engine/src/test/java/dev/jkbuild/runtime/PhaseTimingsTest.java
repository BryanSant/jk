// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The learned per-phase timing ledger: cold fallback, EWMA, TOML round-trip. */
class PhaseTimingsTest {

    @Test
    void cold_ledger_has_no_entries(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        PhaseTimings t = PhaseTimings.load(cache);
        assertThat(t.perUnit("/m/engine", "run-tests")).isEmpty();   // cold → caller falls back to static
    }

    @Test
    void a_new_phase_seeds_at_its_observed_rate(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/engine", "run-tests", 40.0)), 0.4);
        assertThat(PhaseTimings.load(cache).perUnit("/m/engine", "run-tests")).hasValue(40.0);
    }

    @Test
    void repeated_records_ewma_toward_the_latest(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/io", "compile-java", 100.0)), 0.4);
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/io", "compile-java", 200.0)), 0.4);
        // 0.4*200 + 0.6*100 = 140 — moved toward the latest but smoothed.
        var v = PhaseTimings.load(cache).perUnit("/m/io", "compile-java");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(140.0, within(1e-6));
    }

    @Test
    void entries_are_per_module_and_per_phase(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(
                new PhaseTimings.Sample("/m/a", "run-tests", 10.0),
                new PhaseTimings.Sample("/m/b", "run-tests", 20.0),
                new PhaseTimings.Sample("/m/a", "compile-java", 30.0)), 0.5);
        PhaseTimings t = PhaseTimings.load(cache);
        assertThat(t.perUnit("/m/a", "run-tests")).hasValue(10.0);
        assertThat(t.perUnit("/m/b", "run-tests")).hasValue(20.0);
        assertThat(t.perUnit("/m/a", "compile-java")).hasValue(30.0);
        assertThat(t.perUnit("/m/b", "compile-java")).isEmpty();
    }

    @Test
    void survives_a_toml_round_trip_with_pathy_keys(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        String dir = "/home/me/src/oss/jk/kernel/engine";   // ':'/'/'-laden, the reason we use [[timing]] tables
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample(dir, "run-tests", 31.4)), 0.4);
        PhaseTimings.clearMemo();   // force a real re-read from disk
        var v = PhaseTimings.load(cache).perUnit(dir, "run-tests");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(31.4, within(1e-3));
    }

    @Test
    void negative_observations_are_ignored(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/x", "run-tests", -5.0)), 0.4);
        assertThat(PhaseTimings.load(cache).perUnit("/m/x", "run-tests")).isEmpty();
    }
}
