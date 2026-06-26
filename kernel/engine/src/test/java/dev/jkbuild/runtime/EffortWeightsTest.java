// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compile-weight formula: ceil(sources × 0.1), floored at 1 when the phase runs. */
class EffortWeightsTest {

    private static final int MS = EffortWeights.MS_PER_WEIGHT;

    @Test
    void scheduleMillis_serial_sums_every_module() {
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/a"), Set.of(), 10, 4),
                new EffortWeights.ModuleCost(Path.of("/b"), Set.of(), 20, 8));
        assertThat(EffortWeights.scheduleMillis(mods, 4, true, false)).isEqualTo(30L * MS);
    }

    @Test
    void scheduleMillis_parallel_overlaps_independent_modules() {
        // Three independent compile-only modules → bounded by throughput, not the sum.
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/a"), Set.of(), 12, 0),
                new EffortWeights.ModuleCost(Path.of("/b"), Set.of(), 12, 0),
                new EffortWeights.ModuleCost(Path.of("/c"), Set.of(), 12, 0));
        assertThat(EffortWeights.scheduleMillis(mods, 3, false, false)).isEqualTo(12L * MS);
        assertThat(EffortWeights.scheduleMillis(mods, 3, false, false))
                .isLessThan(EffortWeights.scheduleMillis(mods, 3, true, false));
    }

    @Test
    void scheduleMillis_serialized_tests_form_a_floor() {
        // Independent modules whose serial test phases (20 each) dwarf their blocking work (5).
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/a"), Set.of(), 25, 20),
                new EffortWeights.ModuleCost(Path.of("/b"), Set.of(), 25, 20));
        assertThat(EffortWeights.scheduleMillis(mods, 8, false, false)).isEqualTo(40L * MS); // test floor
        // --parallel-tests lifts the floor, so the estimate drops.
        assertThat(EffortWeights.scheduleMillis(mods, 8, false, true)).isLessThan(40L * MS);
    }

    @Test
    void scheduleMillis_chain_gets_no_parallelism_benefit() {
        // A → B → C dependency chain of compile-only work: critical path == serial sum.
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/a"), Set.of(), 10, 0),
                new EffortWeights.ModuleCost(Path.of("/b"), Set.of(Path.of("/a")), 10, 0),
                new EffortWeights.ModuleCost(Path.of("/c"), Set.of(Path.of("/b")), 10, 0));
        assertThat(EffortWeights.scheduleMillis(mods, 8, false, false)).isEqualTo(30L * MS);
    }

    @Test
    void compile_weight_is_ceil_of_a_tenth() {
        assertThat(EffortWeights.compileWeight(200)).isEqualTo(20); // 200 → 20 (your example)
        assertThat(EffortWeights.compileWeight(201)).isEqualTo(21); // rounds up
        assertThat(EffortWeights.compileWeight(11)).isEqualTo(2);
        assertThat(EffortWeights.compileWeight(10)).isEqualTo(1);
        assertThat(EffortWeights.compileWeight(1)).isEqualTo(1); // floored at 1
        assertThat(EffortWeights.compileWeight(0)).isEqualTo(1); // floored at 1
    }

    @Test
    void unit_weights_match_the_spec() {
        assertThat(EffortWeights.TEST_METHOD).isEqualTo(8);
        assertThat(EffortWeights.ARTIFACT_FETCH).isEqualTo(8);
        assertThat(EffortWeights.PACKAGE_JAR).isEqualTo(5);
        // A skipped phase is a true no-op — off the bar entirely, not a stray tick.
        assertThat(EffortWeights.SKIP).isEqualTo(0);
    }

    @Test
    void running_tests_carry_a_startup_floor_plus_per_method() {
        // The JVM-startup floor is paid even by a zero-method suite, so a small,
        // serialized test run still reserves real bar space instead of ~nothing.
        assertThat(EffortWeights.runTestsWeight(0)).isEqualTo(EffortWeights.TEST_STARTUP);
        assertThat(EffortWeights.runTestsWeight(10))
                .isEqualTo(EffortWeights.TEST_STARTUP + 10 * EffortWeights.TEST_METHOD);
        // Dominates the trivial always-run phases (parse/assemble/stamps ≈ 1–4).
        assertThat(EffortWeights.runTestsWeight(0)).isGreaterThan(EffortWeights.PACKAGE_JAR);
    }

    @Test
    void learned_prefers_module_history_then_cross_module_median_then_static(@TempDir Path cache) {
        int staticWeight = EffortWeights.runTestsWeight(100); // 15 + 100*8 = 815

        // (3) Truly cold — no run-tests recorded anywhere → exact Phase-1 static.
        PhaseTimings.clearMemo();
        assertThat(EffortWeights.learned(PhaseTimings.load(cache), "/m/x", "run-tests", 100, staticWeight))
                .isEqualTo(staticWeight);

        // A fast suite on a *different* module teaches the ledger ~0.7 weight/test.
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/seen", "run-tests", 0.7)), 0.4, 1L);
        PhaseTimings t = PhaseTimings.load(cache);

        // (2) A never-built module borrows the cross-module rate, not the hot static.
        int crossModule = EffortWeights.learned(t, "/m/never", "run-tests", 100, staticWeight);
        assertThat(crossModule).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP + 0.7 * 100)); // 85
        assertThat(crossModule).isLessThan(staticWeight);

        // (1) A module with its own history uses that, ignoring the cross-module rate.
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(new PhaseTimings.Sample("/m/seen", "run-tests", 2.0)), 0.4, 2L);
        int own = EffortWeights.learned(PhaseTimings.load(cache), "/m/seen", "run-tests", 100, staticWeight);
        // EWMA: 0.4*2 + 0.6*0.7 = 1.22 → round(15 + 122) = 137
        assertThat(own).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP + 1.22 * 100));
    }
}
