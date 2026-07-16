// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import cc.jumpkick.runtime.CacheBenefit.ModuleInput;
import cc.jumpkick.runtime.CacheBenefit.Result;
import cc.jumpkick.runtime.CacheBenefit.StepInput;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class CacheBenefitTest {

    private static final BiFunction<String, String, OptionalLong> NO_BASELINE = (d, s) -> OptionalLong.empty();

    private static StepInput ran(String name, long millis, String... requires) {
        return new StepInput(name, "SUCCESS", millis, List.of(requires));
    }

    private static StepInput cached(String name, String... requires) {
        return new StepInput(name, "SKIPPED", 0, List.of(requires));
    }

    /** A diamond a→{b,c}→d: the critical path is the heavier branch, not the sum of all steps. */
    @Test
    void level1_longest_path_follows_the_heaviest_branch() {
        ModuleInput m = new ModuleInput("", List.of(
                ran("a", 10), ran("b", 20, "a"), ran("c", 5, "a"), ran("d", 30, "b", "c")));
        // a→b→d = 60, a→c→d = 45. Wall-clock of a parallel cold run is the critical path (60).
        Result r = CacheBenefit.compute(List.of(m), Map.of(), 60, NO_BASELINE);
        assertThat(r.estimatedUncachedMillis()).isEqualTo(60);
        assertThat(r.savedMillis()).isZero(); // nothing cached → nothing saved
        assertThat(r.totalSkips()).isZero();
        assertThat(r.coverage()).isEqualTo(1.0);
    }

    /** A fully-cached rebuild: cold cost comes from baselines, actual is ~instant → big saving. */
    @Test
    void fully_cached_rebuild_saves_the_cold_critical_path() {
        ModuleInput m = new ModuleInput("p", List.of(
                cached("a"), cached("b", "a"), cached("c", "a"), cached("d", "b", "c")));
        Map<String, Long> cold = Map.of("a", 10L, "b", 20L, "c", 5L, "d", 30L);
        BiFunction<String, String, OptionalLong> baseline = (d, s) -> OptionalLong.of(cold.get(s));
        Result r = CacheBenefit.compute(List.of(m), Map.of(), 2, baseline);
        assertThat(r.estimatedUncachedMillis()).isEqualTo(60);
        assertThat(r.savedMillis()).isEqualTo(58); // 60 cold − 2 actual
        assertThat(r.totalSkips()).isEqualTo(4);
        assertThat(r.coveredSkips()).isEqualTo(4);
        assertThat(r.coverage()).isEqualTo(1.0);
        assertThat(r.pct()).isCloseTo(58.0 / 60.0, within(1e-9));
    }

    /** A cache hit with no historical baseline contributes ~0 and lowers coverage (never over-counts). */
    @Test
    void missing_baseline_lowers_coverage_and_undercounts() {
        ModuleInput m = new ModuleInput("p", List.of(cached("a"), cached("b", "a")));
        // Only "a" has a baseline; "b" has none → contributes its ~0 skip millis.
        BiFunction<String, String, OptionalLong> baseline =
                (d, s) -> s.equals("a") ? OptionalLong.of(40) : OptionalLong.empty();
        Result r = CacheBenefit.compute(List.of(m), Map.of(), 1, baseline);
        assertThat(r.estimatedUncachedMillis()).isEqualTo(40); // a(40) + b(0)
        assertThat(r.totalSkips()).isEqualTo(2);
        assertThat(r.coveredSkips()).isEqualTo(1);
        assertThat(r.coverage()).isEqualTo(0.5);
    }

    /** When the estimate is below actual wall-clock (noise/contention), saved clamps to zero. */
    @Test
    void clamps_saved_to_zero_when_estimate_below_actual() {
        ModuleInput m = new ModuleInput("", List.of(ran("only", 10)));
        Result r = CacheBenefit.compute(List.of(m), Map.of(), 100, NO_BASELINE);
        assertThat(r.estimatedUncachedMillis()).isEqualTo(10);
        assertThat(r.savedMillis()).isZero();
        assertThat(r.pct()).isZero();
    }

    /** Level 2 composes per-module cold costs along the module DAG's longest path. */
    @Test
    void level2_composes_dependent_modules_along_the_module_dag() {
        ModuleInput m1 = new ModuleInput("m1", List.of(ran("x", 30)));
        ModuleInput m2 = new ModuleInput("m2", List.of(ran("y", 20)));
        // m2 depends on m1 → cold wall-clock is sequential: 30 + 20.
        Result seq = CacheBenefit.compute(List.of(m1, m2), Map.of("m2", Set.of("m1")), 50, NO_BASELINE);
        assertThat(seq.estimatedUncachedMillis()).isEqualTo(50);

        // Independent modules → cold wall-clock is the slower one (they run in parallel).
        Result par = CacheBenefit.compute(List.of(m1, m2), Map.of(), 30, NO_BASELINE);
        assertThat(par.estimatedUncachedMillis()).isEqualTo(30);
    }

    /** A single module with no edges degenerates to its own Level-1 cost. */
    @Test
    void single_module_degenerates_to_level1() {
        ModuleInput m = new ModuleInput("solo", List.of(ran("a", 15), ran("b", 25, "a")));
        Result r = CacheBenefit.compute(List.of(m), Map.of(), 40, NO_BASELINE);
        assertThat(r.estimatedUncachedMillis()).isEqualTo(40);
    }

    /** No modules / no steps → a well-defined zero (no division blow-ups). */
    @Test
    void empty_build_is_zero() {
        Result r = CacheBenefit.compute(List.of(), Map.of(), 0, NO_BASELINE);
        assertThat(r.estimatedUncachedMillis()).isZero();
        assertThat(r.savedMillis()).isZero();
        assertThat(r.pct()).isZero();
        assertThat(r.coverage()).isEqualTo(1.0);
    }
}
