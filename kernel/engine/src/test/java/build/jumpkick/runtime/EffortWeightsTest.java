// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The compile-weight formula: ceil(sources × 0.1), floored at 1 when the step runs. */
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
    void scheduleMillis_explicit_msPerWeight_scales_the_estimate() {
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/a"), Set.of(), 10, 4),
                new EffortWeights.ModuleCost(Path.of("/b"), Set.of(), 20, 8));
        // A calibrated 50 ms/unit gives 30 × 50; the 4-arg overload delegates with MS_PER_WEIGHT.
        assertThat(EffortWeights.scheduleMillis(mods, 4, true, false, 50)).isEqualTo(30L * 50);
        assertThat(EffortWeights.scheduleMillis(mods, 4, true, false, MS))
                .isEqualTo(EffortWeights.scheduleMillis(mods, 4, true, false));
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
        // Independent modules whose serial test steps (20 each) dwarf their blocking work (5).
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
        // A skipped step is a true no-op — off the bar entirely, not a stray tick.
        assertThat(EffortWeights.SKIP).isEqualTo(0);
    }

    @Test
    void running_tests_carry_a_startup_floor_plus_per_method() {
        // The JVM-startup floor is paid even by a zero-method suite, so a small,
        // serialized test run still reserves real bar space instead of ~nothing.
        assertThat(EffortWeights.runTestsWeight(0)).isEqualTo(EffortWeights.TEST_STARTUP);
        assertThat(EffortWeights.runTestsWeight(10))
                .isEqualTo(EffortWeights.TEST_STARTUP + 10 * EffortWeights.TEST_METHOD);
        // Dominates the trivial always-run steps (parse/assemble/stamps ≈ 1–4).
        assertThat(EffortWeights.runTestsWeight(0)).isGreaterThan(EffortWeights.PACKAGE_JAR);
    }

    @Test
    void learned_prefers_module_history_then_cross_module_median_then_static(@TempDir Path cache) {
        int staticWeight = EffortWeights.runTestsWeight(100); // 15 + 100*8 = 815

        // (3) Truly cold — no run-tests recorded anywhere → exact Step-1 static.
        StepTimings.clearMemo();
        assertThat(EffortWeights.learned(StepTimings.load(cache), "/m/x", "run-tests", 100, staticWeight))
                .isEqualTo(staticWeight);

        // A fast suite on a *different* module teaches the ledger ~0.7 weight/test.
        StepTimings.clearMemo();
        StepTimings.record(cache, List.of(new StepTimings.Sample("/m/seen", "run-tests", 0.7)), 0.4, 1L);
        StepTimings t = StepTimings.load(cache);

        // (2) A never-built module borrows the cross-module rate, not the hot static. The learned
        // reconstruction adds the small learnable TEST_STARTUP_FLOOR, NOT the larger cold TEST_STARTUP
        // guess — that decoupling is what lets a fast suite learn a rate below the old 15-unit floor.
        int crossModule = EffortWeights.learned(t, "/m/never", "run-tests", 100, staticWeight);
        assertThat(crossModule).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 0.7 * 100)); // 72
        assertThat(crossModule).isLessThan(staticWeight);

        // (1) A module with its own history uses that, ignoring the cross-module rate.
        StepTimings.clearMemo();
        StepTimings.record(cache, List.of(new StepTimings.Sample("/m/seen", "run-tests", 2.0)), 0.4, 2L);
        int own = EffortWeights.learned(StepTimings.load(cache), "/m/seen", "run-tests", 100, staticWeight);
        // EWMA: 0.4*2 + 0.6*0.7 = 1.22 → round(2 + 122) = 124
        assertThat(own).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 1.22 * 100));
    }

    @Test
    void learned_prefers_project_median_over_host_median(@TempDir Path cache) {
        StepTimings.clearMemo();
        int staticWeight = EffortWeights.runTestsWeight(10);
        // Host has slow run-tests rates from modules OUTSIDE this project, and one fast sibling IN it.
        StepTimings.record(
                cache,
                List.of(
                        new StepTimings.Sample("/other/x", "run-tests", 5.0),
                        new StepTimings.Sample("/other/y", "run-tests", 5.0),
                        new StepTimings.Sample("/proj/sib", "run-tests", 1.0)),
                0.4,
                1L);
        StepTimings t = StepTimings.load(cache);
        var projectDirs = List.of("/proj/sib", "/proj/new");

        // A never-built module in the project borrows the project (sibling) rate, not the host median.
        int project = EffortWeights.learned(t, "/proj/new", "run-tests", 10, staticWeight, projectDirs);
        int host = EffortWeights.learned(t, "/proj/new", "run-tests", 10, staticWeight); // no project ctx
        assertThat(project).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 1.0 * 10)); // 12
        assertThat(host).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 5.0 * 10)); // 52
        assertThat(project).isLessThan(host);

        // The module's OWN history still wins over the project tier.
        StepTimings.clearMemo();
        StepTimings.record(cache, List.of(new StepTimings.Sample("/proj/new", "run-tests", 3.0)), 0.4, 2L);
        int own = EffortWeights.learned(
                StepTimings.load(cache), "/proj/new", "run-tests", 10, staticWeight, projectDirs);
        assertThat(own).isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 3.0 * 10)); // 32
    }

    // ---- the metrics-learned flat weights (BuildMetrics tiers) ----------------------------------

    /** A store with {@code n} successful runs of {@code step} under {@code dir}, avg {@code avgMs}. */
    private static BuildMetrics metricsWith(Path file, String dir, String step, int n, long avgMs) {
        for (int i = 0; i < n; i++) {
            BuildMetrics.record(
                    file,
                    new BuildMetrics.Outcome(
                            "build",
                            dir,
                            null,
                            true,
                            false,
                            avgMs,
                            List.of(new BuildMetrics.StepSample(dir, step, "SUCCESS", avgMs))),
                    i);
        }
        BuildMetrics.clearMemo();
        return BuildMetrics.load(file);
    }

    @Test
    void learned_fixed_weight_prefers_own_project_then_host_then_static(@TempDir Path dir) {
        // (3) Empty store → the static constant, bit-for-bit.
        BuildMetrics.clearMemo();
        BuildMetrics empty = BuildMetrics.load(dir.resolve("empty.json"));
        assertThat(EffortWeights.learnedFixedWeight(empty, "/m/a", "package-jar", EffortWeights.PACKAGE_JAR))
                .isEqualTo(EffortWeights.PACKAGE_JAR);

        // (2) Another project's history feeds the host tier: 1500 ms avg → 10 units.
        BuildMetrics host = metricsWith(dir.resolve("host.json"), "/m/other", "package-jar", 3, 1500);
        assertThat(EffortWeights.learnedFixedWeight(host, "/m/a", "package-jar", EffortWeights.PACKAGE_JAR))
                .isEqualTo(10);

        // (1) The module's own history wins over the host tier: 300 ms avg → 2 units.
        BuildMetrics own = metricsWith(dir.resolve("own.json"), "/m/a", "package-jar", 3, 300);
        assertThat(EffortWeights.learnedFixedWeight(own, "/m/a", "package-jar", EffortWeights.PACKAGE_JAR))
                .isEqualTo(2);
    }

    @Test
    void learned_fixed_weight_needs_enough_samples_before_it_outranks_the_static(@TempDir Path dir) {
        BuildMetrics thin = metricsWith(dir.resolve("thin.json"), "/m/a", "package-jar", 2, 6000);
        assertThat(EffortWeights.learnedFixedWeight(thin, "/m/a", "package-jar", EffortWeights.PACKAGE_JAR))
                .isEqualTo(EffortWeights.PACKAGE_JAR); // 2 < MIN_METRICS_SAMPLES — not trusted yet
    }

    @Test
    void learned_falls_back_to_metrics_history_only_when_the_ledger_is_cold(@TempDir Path dir) {
        int staticWeight = EffortWeights.runTestsWeight(10);
        BuildMetrics metrics = metricsWith(dir.resolve("m.json"), "/m/a", "run-tests", 3, 3000); // → 20 units
        StepTimings.clearMemo();

        // Cold ledger (e.g. right after `jk clean`) → the surviving metrics average, not the static.
        StepTimings cold = StepTimings.load(dir.resolve("no-cache"));
        assertThat(EffortWeights.learned(cold, metrics, "/m/a", "run-tests", 10, staticWeight, List.of()))
                .isEqualTo(20);

        // A warm ledger still wins: rates are tighter than whole-step averages.
        StepTimings.record(dir, List.of(new StepTimings.Sample("/m/a", "run-tests", 1.0)), 0.4, 1L);
        StepTimings.clearMemo();
        assertThat(EffortWeights.learned(
                        StepTimings.load(dir), metrics, "/m/a", "run-tests", 10, staticWeight, List.of()))
                .isEqualTo((int) Math.round(EffortWeights.TEST_STARTUP_FLOOR + 1.0 * 10));
    }

    @Test
    void run_tests_is_learnable_below_the_old_startup_floor() {
        // A fast suite (450 ms ≈ 3 units for 1 test) now teaches a real rate: residual against the
        // small learnable floor (2) is positive. Under the old floor (TEST_STARTUP = 15 ≈ 2.25 s) the
        // residual was negative, so EVERY fast suite was dropped and run-tests never learned.
        assertThat(EffortWeights.observedPerUnit("run-tests", 450, 1)).isEqualTo(1.0); // (3 − 2) / 1
        assertThat(EffortWeights.TEST_STARTUP_FLOOR).isLessThan(EffortWeights.TEST_STARTUP);
        double underOldFloor = 450 / (double) MS - EffortWeights.TEST_STARTUP; // would have been dropped
        assertThat(underOldFloor).isNegative();
        // A genuinely trivial run at/under the floor still drops (no ~0 rate taught).
        assertThat(EffortWeights.observedPerUnit("run-tests", 300, 1)).isEqualTo(-1);
    }

    @Test
    void scheduleMillis_prices_each_module_by_its_own_rate() {
        var mods = List.of(
                new EffortWeights.ModuleCost(Path.of("/warm"), Set.of(), 10, 0),
                new EffortWeights.ModuleCost(Path.of("/cold"), Set.of(), 10, 0));
        // Warm module converts at MS_PER_WEIGHT (its learned rates round-trip); cold at a slower
        // reference constant would be wrong — price the cold one at this host's (faster) calibration.
        java.util.function.ToDoubleFunction<Path> rate =
                d -> d.equals(Path.of("/warm")) ? EffortWeights.MS_PER_WEIGHT : 50.0;
        assertThat(EffortWeights.scheduleMillis(mods, 1, true, false, rate))
                .isEqualTo(10L * EffortWeights.MS_PER_WEIGHT + 10L * 50);
        // A uniform per-module rate reduces to the scalar overload.
        assertThat(EffortWeights.scheduleMillis(mods, 1, true, false, d -> 50.0))
                .isEqualTo(EffortWeights.scheduleMillis(mods, 1, true, false, 50));
    }
}
