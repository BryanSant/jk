// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The memory-budget routine, exercised against the four worked examples in the spec. */
class HeapPlanTest {

    private static long mb(long mebibytes) {
        return mebibytes << 20;
    }

    @Test
    void example_2g_reduces_parallelism_to_two() {
        // 1.2 GiB free, want 4 → buffer 10% → ~1106 MiB usable → 2 JVMs @ ~553 MiB.
        HeapPlan.Plan p = HeapPlan.compute(mb(1228), 4);
        assertThat(p.parallelism()).isEqualTo(2);
        assertThat(HeapPlan.mib(p.xmxBytes())).isBetween(540L, 560L);
        assertThat(HeapPlan.mib(p.softMaxBytes())).isEqualTo(512); // clamp floor
        assertThat(p.warning()).isNull();
    }

    @Test
    void example_32g_keeps_four_with_a_50pct_soft_target() {
        // 22 GiB free, want 4 → ~20275 MiB usable → 4 JVMs @ ~5068 MiB, soft ~2.5 GiB.
        HeapPlan.Plan p = HeapPlan.compute(mb(22528), 4);
        assertThat(p.parallelism()).isEqualTo(4);
        assertThat(HeapPlan.mib(p.xmxBytes())).isBetween(5000L, 5100L);
        assertThat(HeapPlan.mib(p.softMaxBytes())).isBetween(2500L, 2540L); // 50% of perJvm
        assertThat(p.warning()).isNull();
    }

    @Test
    void example_8g_keeps_eight_with_a_512_floor_soft_target() {
        // 6.8 GiB free, want 8 → ~6267 MiB usable → 8 JVMs @ ~783 MiB, soft = 512 (floor).
        HeapPlan.Plan p = HeapPlan.compute(mb(6963), 8);
        assertThat(p.parallelism()).isEqualTo(8);
        assertThat(HeapPlan.mib(p.xmxBytes())).isBetween(770L, 790L);
        assertThat(HeapPlan.mib(p.softMaxBytes())).isEqualTo(512);
        assertThat(p.warning()).isNull();
    }

    @Test
    void example_512m_falls_back_to_serial_with_a_warning() {
        // 442 MiB free, want 4 → buffer floor 96 → 346 usable → serial best-effort.
        HeapPlan.Plan p = HeapPlan.compute(mb(442), 4);
        assertThat(p.parallelism()).isEqualTo(1);
        assertThat(HeapPlan.mib(p.xmxBytes())).isBetween(340L, 350L);
        assertThat(HeapPlan.mib(p.softMaxBytes())).isEqualTo(HeapPlan.mib(p.xmxBytes())); // soft == burst
        assertThat(p.warning()).isNotNull().contains("best effort");
    }

    @Test
    void buffer_is_the_greater_of_ten_percent_or_96_mib() {
        // Small pool: 10% of 442 MiB = 44 MiB < 96 → the 96 MiB floor applies.
        assertThat(HeapPlan.mib(HeapPlan.compute(mb(442), 1).xmxBytes())).isEqualTo(442 - 96);
        // Large pool: 10% of 22528 MiB ≈ 2252.8 MiB > 96 → the 10% applies (byte-accurate).
        assertThat(HeapPlan.mib(HeapPlan.compute(mb(22528), 1).xmxBytes())).isBetween(20270L, 20276L);
    }

    @Test
    void xms_stays_small_so_nothing_is_pre_committed() {
        HeapPlan.Plan p = HeapPlan.compute(mb(22528), 4);
        assertThat(HeapPlan.mib(p.xmsBytes())).isEqualTo(64); // XMS_FLOOR, not the 5 GiB burst
    }

    @Test
    void requested_jvms_folds_workers_and_parallel_tests() {
        assertThat(HeapPlan.requestedJvms(4, 1, false, 16)).isEqualTo(4); // modules only
        assertThat(HeapPlan.requestedJvms(1, 8, false, 16)).isEqualTo(8); // workers in one module
        assertThat(HeapPlan.requestedJvms(4, 2, true, 16)).isEqualTo(8); // overlap multiplies
        assertThat(HeapPlan.requestedJvms(4, 2, true, 4)).isEqualTo(4); // clamped to cap
        assertThat(HeapPlan.requestedJvms(0, 0, false, 16)).isEqualTo(1); // never below 1
    }
}
