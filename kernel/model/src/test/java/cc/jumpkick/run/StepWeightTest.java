// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A step's {@code weight} is its share of the progress bar — time-proportional, decoupled from its
 * {@code ticks} (internal unit count). The pipeline denominator sums weights, and a step's own 0→ticks
 * progress is scaled into its weight, so a file-count-scoped compile can't dominate the bar. Steps
 * without an explicit weight keep the legacy 1:1 (weight tracks ticks) behaviour.
 */
class StepWeightTest {

    /** Records the numerator/denominator seen on each progress event. */
    private static final class ProgressTrace implements PipelineListener {
        final List<long[]> points = new ArrayList<>(); // {numerator, denominator}

        @Override
        public void progress(String step, int delta, PipelineView v) {
            points.add(new long[] {v.numerator(), v.denominator()});
        }
    }

    @Test
    void denominator_sums_weights_not_scopes() {
        // A: weight 40 over 4 units; B: no weight → tracks its ticks of 6.
        Pipeline pipeline = Pipeline.builder("g")
                .addStep(Step.builder("a")
                        .weight(40)
                        .ticks(4)
                        .execute(ctx -> {})
                        .build())
                .addStep(Step.builder("b").ticks(6).execute(ctx -> {}).build())
                .build();

        assertThat(pipeline.estimatedTotalWeight()).isEqualTo(46); // 40 + 6, not 4 + 6
        pipeline.run();
        assertThat(pipeline.snapshot().denominator()).isEqualTo(46);
        assertThat(pipeline.snapshot().numerator()).isEqualTo(46); // auto-filled on success
    }

    @Test
    void internal_progress_scales_into_the_weight() {
        ProgressTrace trace = new ProgressTrace();
        Pipeline pipeline = Pipeline.builder("g")
                .addListener(trace)
                // 4 internal ticks mapped onto a 40- tick weight → +10 each.
                .addStep(Step.builder("a")
                        .weight(40)
                        .ticks(4)
                        .execute(ctx -> {
                            for (int i = 0; i < 4; i++) ctx.progress(1);
                        })
                        .build())
                .build();

        pipeline.run();

        // Each progress(1) advances 1/4 of the 40-tick budget against a fixed
        // denominator — smooth, monotonic, and exactly filling the weight.
        assertThat(trace.points).extracting(p -> p[0]).containsExactly(10L, 20L, 30L, 40L);
        assertThat(trace.points).allSatisfy(p -> assertThat(p[1]).isEqualTo(40L));
    }

    @Test
    void weighted_progress_rounds_and_stays_monotonic() {
        ProgressTrace trace = new ProgressTrace();
        Pipeline pipeline = Pipeline.builder("g")
                .addListener(trace)
                // 3 units over a weight of 10: round(10/3)=3, round(20/3)=7, 10.
                .addStep(Step.builder("a")
                        .weight(10)
                        .ticks(3)
                        .execute(ctx -> {
                            for (int i = 0; i < 3; i++) ctx.progress(1);
                        })
                        .build())
                .build();

        pipeline.run();

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
        Pipeline pipeline = Pipeline.builder("g")
                .addListener(trace)
                // Reports more units (5) than its ticks (2): the bar clamps at weight.
                .addStep(Step.builder("a")
                        .weight(20)
                        .ticks(2)
                        .execute(ctx -> {
                            for (int i = 0; i < 5; i++) ctx.progress(1);
                        })
                        .build())
                .build();

        pipeline.run();

        assertThat(trace.points).allSatisfy(p -> assertThat(p[0]).isLessThanOrEqualTo(20L));
        assertThat(pipeline.snapshot().numerator()).isEqualTo(20);
        assertThat(pipeline.snapshot().denominator()).isEqualTo(20);
    }

    @Test
    void reweight_resizes_the_slice_and_the_denominator_mid_run() {
        // 'a' is estimated at 40 up front but discovers early it's a cheap restore (3).
        Pipeline pipeline = Pipeline.builder("g")
                .addStep(Step.builder("a")
                        .weight(40)
                        .ticks(1)
                        .execute(ctx -> {
                            ctx.reweight(3);
                            ctx.progress(1);
                        })
                        .build())
                .addStep(Step.builder("b")
                        .weight(10)
                        .ticks(1)
                        .execute(ctx -> ctx.progress(1))
                        .build())
                .build();

        assertThat(pipeline.estimatedTotalWeight()).isEqualTo(50); // 40 + 10, before running
        pipeline.run();
        // 'a' shrank 40 → 3, so the denominator drops to 3 + 10; both fill fully.
        assertThat(pipeline.snapshot().denominator()).isEqualTo(13);
        assertThat(pipeline.snapshot().numerator()).isEqualTo(13);
    }

    @Test
    void interpolation_eases_toward_the_weight_and_caps() {
        // A weighted, interpolated step with expected duration 1ms and weight 100.
        // Driving tick() with controlled timestamps eases the slice toward
        // weight × elapsed/expected, then holds at the 90% cap.
        Pipeline pipeline = Pipeline.builder("g").build();
        DefaultStepContext ctx = new DefaultStepContext(
                "p",
                pipeline, /*ticks*/
                1, /*weight*/
                100, /*weighted*/
                true,
                /*expectedNanos*/ 1_000_000L, /*startNanos*/
                0L);
        assertThat(ctx.interpolating()).isTrue();

        ctx.tick(500_000L); // 50% elapsed → 50
        assertThat(pipeline.snapshot().numerator()).isEqualTo(50);
        ctx.tick(900_000L); // 90% elapsed → 90
        assertThat(pipeline.snapshot().numerator()).isEqualTo(90);
        ctx.tick(5_000_000L); // way over → capped at 90% (90)
        assertThat(pipeline.snapshot().numerator()).isEqualTo(90);
    }

    @Test
    void real_progress_overrides_interpolation_and_fills_past_the_cap() {
        Pipeline pipeline = Pipeline.builder("g").build();
        DefaultStepContext ctx = new DefaultStepContext(
                "p",
                pipeline, /*ticks*/
                1, /*weight*/
                100, /*weighted*/
                true,
                /*expectedNanos*/ 1_000_000L, /*startNanos*/
                0L);

        ctx.tick(900_000L); // interpolated to the 90 cap
        assertThat(pipeline.snapshot().numerator()).isEqualTo(90);
        ctx.progress(1); // real completion (ticks 1) → full weight
        assertThat(pipeline.snapshot().numerator()).isEqualTo(100);
        ctx.tick(950_000L); // a late tick can't pull it back
        assertThat(pipeline.snapshot().numerator()).isEqualTo(100);
    }

    @Test
    void unweighted_phase_is_unchanged_one_to_one() {
        ProgressTrace trace = new ProgressTrace();
        Pipeline pipeline = Pipeline.builder("g")
                .addListener(trace)
                .addStep(Step.builder("a")
                        .ticks(3)
                        .execute(ctx -> {
                            for (int i = 0; i < 3; i++) ctx.progress(1);
                        })
                        .build())
                .build();

        pipeline.run();

        // Legacy behaviour: deltas hit the numerator 1:1 against a denominator of 3.
        assertThat(trace.points).extracting(p -> p[0]).containsExactly(1L, 2L, 3L);
        assertThat(trace.points).allSatisfy(p -> assertThat(p[1]).isEqualTo(3L));
    }
}
