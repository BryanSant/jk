// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The whole-build history prior: a sanity anchor for the seeded ETA, one-sided by design. */
class BuildServiceEtaTest {

    private static BuildMetrics.Stats ok(long count, long avg, long min, long max) {
        return new BuildMetrics.Stats(count, count * avg, min, max);
    }

    @Test
    void no_history_leaves_the_base_untouched() {
        assertThat(BuildService.applyHistoryPrior(0, null)).isZero();
        assertThat(BuildService.applyHistoryPrior(0, BuildMetrics.Stats.EMPTY)).isZero();
        assertThat(BuildService.applyHistoryPrior(4200, BuildMetrics.Stats.EMPTY))
                .isEqualTo(4200);
    }

    @Test
    void count_up_becomes_the_historical_average_when_the_project_has_history() {
        // Cold module + uncalibratable host used to mean "count up" (0) — history beats that.
        assertThat(BuildService.applyHistoryPrior(0, ok(5, 2000, 800, 6000))).isEqualTo(2000);
    }

    @Test
    void absurd_over_estimates_clamp_down_to_twice_the_historical_max() {
        assertThat(BuildService.applyHistoryPrior(60_000, ok(5, 2000, 800, 6000)))
                .isEqualTo(12_000);
    }

    @Test
    void the_clamp_is_one_sided_and_needs_a_settled_history() {
        // Incremental runs legitimately beat the historical average — never clamp UP.
        assertThat(BuildService.applyHistoryPrior(500, ok(5, 2000, 800, 6000))).isEqualTo(500);
        // Within 2× max → trusted as-is.
        assertThat(BuildService.applyHistoryPrior(9000, ok(5, 2000, 800, 6000))).isEqualTo(9000);
        // Fewer than 3 successful builds is too thin to clamp against.
        assertThat(BuildService.applyHistoryPrior(60_000, ok(2, 2000, 800, 6000)))
                .isEqualTo(60_000);
    }
}
