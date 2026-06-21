// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The compile-weight formula: ceil(sources × 0.1), floored at 1 when the phase runs. */
class EffortWeightsTest {

    @Test
    void compile_weight_is_ceil_of_a_tenth() {
        assertThat(EffortWeights.compileWeight(200)).isEqualTo(20);   // 200 → 20 (your example)
        assertThat(EffortWeights.compileWeight(201)).isEqualTo(21);   // rounds up
        assertThat(EffortWeights.compileWeight(11)).isEqualTo(2);
        assertThat(EffortWeights.compileWeight(10)).isEqualTo(1);
        assertThat(EffortWeights.compileWeight(1)).isEqualTo(1);      // floored at 1
        assertThat(EffortWeights.compileWeight(0)).isEqualTo(1);      // floored at 1
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
        int staticWeight = EffortWeights.runTestsWeight(100);   // 15 + 100*8 = 815

        // (3) Truly cold — no run-tests recorded anywhere → exact Phase-1 static.
        PhaseTimings.clearMemo();
        assertThat(EffortWeights.learned(PhaseTimings.load(cache), "/m/x", "run-tests", 100, staticWeight))
                .isEqualTo(staticWeight);

        // A fast suite on a *different* module teaches the ledger ~0.7 weight/test.
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(
                new PhaseTimings.Sample("/m/seen", "run-tests", 0.7)), 0.4, 1L);
        PhaseTimings t = PhaseTimings.load(cache);

        // (2) A never-built module borrows the cross-module rate, not the hot static.
        int crossModule = EffortWeights.learned(t, "/m/never", "run-tests", 100, staticWeight);
        assertThat(crossModule).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP + 0.7 * 100)); // 85
        assertThat(crossModule).isLessThan(staticWeight);

        // (1) A module with its own history uses that, ignoring the cross-module rate.
        PhaseTimings.clearMemo();
        PhaseTimings.record(cache, List.of(
                new PhaseTimings.Sample("/m/seen", "run-tests", 2.0)), 0.4, 2L);
        int own = EffortWeights.learned(PhaseTimings.load(cache), "/m/seen", "run-tests", 100, staticWeight);
        // EWMA: 0.4*2 + 0.6*0.7 = 1.22 → round(15 + 122) = 137
        assertThat(own).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP + 1.22 * 100));
    }
}
