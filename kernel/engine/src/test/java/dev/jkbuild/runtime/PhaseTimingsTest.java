// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The learned per-phase timing ledger: cold fallback, EWMA, TOML round-trip, GC. */
class PhaseTimingsTest {

    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_700_000_000_000L;   // a fixed "now" for determinism

    private static void record(Path cache, long now, PhaseTimings.Sample... s) {
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(s), 0.4, now);
    }

    @Test
    void cold_ledger_has_no_entries(@TempDir Path cache) {
        PhaseTimings.clearMemo();
        assertThat(PhaseTimings.load(cache).perUnit("/m/engine", "run-tests")).isEmpty();
    }

    @Test
    void a_new_phase_seeds_at_its_observed_rate(@TempDir Path cache) {
        record(cache, NOW, new PhaseTimings.Sample("/m/engine", "run-tests", 40.0));
        assertThat(PhaseTimings.load(cache).perUnit("/m/engine", "run-tests")).hasValue(40.0);
    }

    @Test
    void repeated_records_ewma_toward_the_latest(@TempDir Path cache) {
        record(cache, NOW, new PhaseTimings.Sample("/m/io", "compile-java", 100.0));
        record(cache, NOW + 1, new PhaseTimings.Sample("/m/io", "compile-java", 200.0));
        var v = PhaseTimings.load(cache).perUnit("/m/io", "compile-java");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(140.0, within(1e-6));   // 0.4*200 + 0.6*100
    }

    @Test
    void survives_a_toml_round_trip_with_pathy_keys(@TempDir Path cache) {
        String dir = "/home/me/src/oss/jk/kernel/engine";
        record(cache, NOW, new PhaseTimings.Sample(dir, "run-tests", 31.4));
        PhaseTimings.clearMemo();   // force a real re-read from disk
        var v = PhaseTimings.load(cache).perUnit(dir, "run-tests");
        assertThat(v).isPresent();
        assertThat(v.getAsDouble()).isCloseTo(31.4, within(1e-3));
    }

    @Test
    void negative_observations_are_ignored(@TempDir Path cache) {
        record(cache, NOW, new PhaseTimings.Sample("/m/x", "run-tests", -5.0));
        assertThat(PhaseTimings.load(cache).perUnit("/m/x", "run-tests")).isEmpty();
    }

    @Test
    void prune_evicts_entries_older_than_the_max_age(@TempDir Path cache) {
        record(cache, NOW - 3 * 365 * DAY, new PhaseTimings.Sample("/m/stale", "run-tests", 10.0)); // 3y old
        record(cache, NOW - 10 * DAY, new PhaseTimings.Sample("/m/fresh", "run-tests", 20.0));       // 10d old

        var limits = new PhaseTimings.Limits(100L * 1024 * 1024, 730 * DAY);   // 100MB / 2y
        var report = PhaseTimings.prune(cache, limits, NOW, false);

        assertThat(report.evictedByAge()).isEqualTo(1);
        PhaseTimings t = PhaseTimings.load(cache);
        assertThat(t.perUnit("/m/stale", "run-tests")).isEmpty();   // aged out
        assertThat(t.perUnit("/m/fresh", "run-tests")).hasValue(20.0);
    }

    @Test
    void prune_evicts_the_oldest_until_under_the_size_cap(@TempDir Path cache) {
        // 30 entries, each stamped a day apart so "oldest" is well-defined.
        for (int i = 0; i < 30; i++) {
            record(cache, NOW + i * DAY, new PhaseTimings.Sample("/m/mod" + i, "run-tests", 5.0));
        }
        // A cap far smaller than 30 entries' worth (~120 bytes each) forces size eviction.
        var report = PhaseTimings.prune(cache, new PhaseTimings.Limits(400, 730 * DAY), NOW + 100 * DAY, false);

        assertThat(report.evictedBySize()).isGreaterThan(0);
        assertThat(report.kept()).isLessThan(30);
        assertThat(report.finalBytes()).isLessThanOrEqualTo(400L);
        PhaseTimings t = PhaseTimings.load(cache);
        assertThat(t.perUnit("/m/mod0", "run-tests")).isEmpty();        // oldest dropped
        assertThat(t.perUnit("/m/mod29", "run-tests")).hasValue(5.0);   // newest kept
    }

    @Test
    void prune_is_a_noop_with_no_file(@TempDir Path cache) {
        var report = PhaseTimings.prune(cache, new PhaseTimings.Limits(400, 730 * DAY), NOW, false);
        assertThat(report.evictedByAge()).isZero();
        assertThat(report.evictedBySize()).isZero();
    }

    @Test
    void limits_resolve_from_env_over_config_over_default(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.toml");
        java.nio.file.Files.writeString(cfg, "[cache]\ntimings-max-size-mb = 50\ntimings-max-age-days = 365\n");

        // Defaults when nothing set.
        var def = PhaseTimings.Limits.resolve(dir.resolve("missing.toml"), k -> null);
        assertThat(def.maxBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(def.maxAgeMillis()).isEqualTo(730 * DAY);

        // Config wins over default.
        var fromCfg = PhaseTimings.Limits.resolve(cfg, k -> null);
        assertThat(fromCfg.maxBytes()).isEqualTo(50L * 1024 * 1024);
        assertThat(fromCfg.maxAgeMillis()).isEqualTo(365 * DAY);

        // Env wins over config.
        var fromEnv = PhaseTimings.Limits.resolve(cfg,
                k -> "JK_TIMINGS_MAX_SIZE_MB".equals(k) ? "10" : null);
        assertThat(fromEnv.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(fromEnv.maxAgeMillis()).isEqualTo(365 * DAY);   // age still from config
    }
}
