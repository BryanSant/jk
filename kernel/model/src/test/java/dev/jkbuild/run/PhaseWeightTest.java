// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A phase's {@code weight} is its share of the progress bar — time-proportional,
 * decoupled from its {@code scope} (internal unit count). The goal denominator
 * sums weights, and a phase's own 0→scope progress is scaled into its weight, so
 * a file-count-scoped compile can't dominate the bar. Phases without an explicit
 * weight keep the legacy 1:1 (weight tracks scope) behaviour.
 */
class PhaseWeightTest {

    /** Records the numerator/denominator seen on each progress event. */
    private static final class ProgressTrace implements GoalListener {
        final List<long[]> points = new ArrayList<>();   // {numerator, denominator}
        @Override public void progress(String phase, int delta, GoalView v) {
            points.add(new long[]{v.numerator(), v.denominator()});
        }
    }

    @Test
    void denominator_sums_weights_not_scopes() {
        // A: weight 40 over 4 units; B: no weight → tracks its scope of 6.
        Goal goal = Goal.builder("g")
                .addPhase(Phase.builder("a").weight(40).scope(4).execute(ctx -> {}).build())
                .addPhase(Phase.builder("b").scope(6).execute(ctx -> {}).build())
                .build();

        assertThat(goal.estimatedTotalWeight()).isEqualTo(46); // 40 + 6, not 4 + 6
        goal.run();
        assertThat(goal.snapshot().denominator()).isEqualTo(46);
        assertThat(goal.snapshot().numerator()).isEqualTo(46); // auto-filled on success
    }

    @Test
    void internal_progress_scales_into_the_weight() {
        ProgressTrace trace = new ProgressTrace();
        Goal goal = Goal.builder("g")
                .addListener(trace)
                // 4 internal ticks mapped onto a 40- tick weight → +10 each.
                .addPhase(Phase.builder("a").weight(40).scope(4)
                        .execute(ctx -> { for (int i = 0; i < 4; i++) ctx.progress(1); })
                        .build())
                .build();

        goal.run();

        // Each progress(1) advances 1/4 of the 40-tick budget against a fixed
        // denominator — smooth, monotonic, and exactly filling the weight.
        assertThat(trace.points).extracting(p -> p[0])
                .containsExactly(10L, 20L, 30L, 40L);
        assertThat(trace.points).allSatisfy(p -> assertThat(p[1]).isEqualTo(40L));
    }

    @Test
    void weighted_progress_rounds_and_stays_monotonic() {
        ProgressTrace trace = new ProgressTrace();
        Goal goal = Goal.builder("g")
                .addListener(trace)
                // 3 units over a weight of 10: round(10/3)=3, round(20/3)=7, 10.
                .addPhase(Phase.builder("a").weight(10).scope(3)
                        .execute(ctx -> { for (int i = 0; i < 3; i++) ctx.progress(1); })
                        .build())
                .build();

        goal.run();

        List<Long> nums = trace.points.stream().map(p -> p[0]).toList();
        assertThat(nums).containsExactly(3L, 7L, 10L);
        // Never decreases.
        for (int i = 1; i < nums.size(); i++) {
            assertThat(nums.get(i)).isGreaterThanOrEqualTo(nums.get(i - 1));
        }
    }

    @Test
    void overrunning_internal_scope_never_exceeds_the_weight() {
        ProgressTrace trace = new ProgressTrace();
        Goal goal = Goal.builder("g")
                .addListener(trace)
                // Reports more units (5) than its scope (2): the bar clamps at weight.
                .addPhase(Phase.builder("a").weight(20).scope(2)
                        .execute(ctx -> { for (int i = 0; i < 5; i++) ctx.progress(1); })
                        .build())
                .build();

        goal.run();

        assertThat(trace.points).allSatisfy(p -> assertThat(p[0]).isLessThanOrEqualTo(20L));
        assertThat(goal.snapshot().numerator()).isEqualTo(20);
        assertThat(goal.snapshot().denominator()).isEqualTo(20);
    }

    @Test
    void unweighted_phase_is_unchanged_one_to_one() {
        ProgressTrace trace = new ProgressTrace();
        Goal goal = Goal.builder("g")
                .addListener(trace)
                .addPhase(Phase.builder("a").scope(3)
                        .execute(ctx -> { for (int i = 0; i < 3; i++) ctx.progress(1); })
                        .build())
                .build();

        goal.run();

        // Legacy behaviour: deltas hit the numerator 1:1 against a denominator of 3.
        assertThat(trace.points).extracting(p -> p[0]).containsExactly(1L, 2L, 3L);
        assertThat(trace.points).allSatisfy(p -> assertThat(p[1]).isEqualTo(3L));
    }
}
