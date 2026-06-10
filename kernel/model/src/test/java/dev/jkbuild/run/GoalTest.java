// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.run;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTest {

    @Test
    void single_phase_succeeds_and_collects_progress() {
        AtomicInteger ran = new AtomicInteger();
        var goal = Goal.builder("test")
                .addPhase(Phase.builder("step")
                        .scope(5)
                        .execute(ctx -> {
                            for (int i = 0; i < 5; i++) ctx.progress(1);
                            ran.incrementAndGet();
                        })
                        .build())
                .build();

        var result = goal.run();
        assertThat(ran).hasValue(1);
        assertThat(result.success()).isTrue();
        assertThat(result.phases()).hasSize(1);
        assertThat(result.phases().getFirst().status()).isEqualTo(PhaseStatus.SUCCESS);
        assertThat(goal.snapshot().percent()).isEqualTo(100);
    }

    @Test
    void scope_sums_across_phases() {
        var goal = Goal.builder("multi")
                .addPhase(Phase.builder("a").scope(3).execute(ctx -> {}).build())
                .addPhase(Phase.builder("b").scope(7).execute(ctx -> {}).build())
                .addPhase(Phase.builder("c").scope(2).execute(ctx -> {}).build())
                .build();

        var result = goal.run();
        assertThat(result.success()).isTrue();
        // Auto-fill: phases reported zero progress but each succeeded, so
        // the numerator climbs to the denominator (3+7+2 = 12).
        assertThat(goal.snapshot().numerator()).isEqualTo(12);
        assertThat(goal.snapshot().denominator()).isEqualTo(12);
    }

    @Test
    void interleaved_progress_and_update_scope_never_overshoot() {
        // Regression for the "318 of 161" bug: when a phase calls
        // updateScope(1) followed by progress(1) repeatedly (jk test's
        // per-test bridge), numerator must never exceed denominator
        // mid-phase. An earlier implementation advanced the numerator
        // proportionally inside updateScope to "preserve the fraction,"
        // which compounded into a 2× overshoot.
        AtomicInteger maxNumOverDen = new AtomicInteger(0);
        var goal = Goal.builder("interleaved")
                .addListener(new GoalListener() {
                    @Override public void progress(String phase, int delta, GoalView view) {
                        if (view.numerator() > view.denominator()) {
                            maxNumOverDen.updateAndGet(prev -> Math.max(prev,
                                    (int) (view.numerator() - view.denominator())));
                        }
                    }
                    @Override public void scopeUpdate(String phase, int delta, GoalView view) {
                        if (view.numerator() > view.denominator()) {
                            maxNumOverDen.updateAndGet(prev -> Math.max(prev,
                                    (int) (view.numerator() - view.denominator())));
                        }
                    }
                })
                .addPhase(Phase.builder("loop").scope(0)
                        .execute(ctx -> {
                            for (int i = 0; i < 100; i++) {
                                ctx.updateScope(1);
                                ctx.progress(1);
                            }
                        }).build())
                .build();

        var result = goal.run();
        assertThat(result.success()).isTrue();
        assertThat(maxNumOverDen).hasValue(0);
        assertThat(goal.snapshot().numerator()).isEqualTo(100);
        assertThat(goal.snapshot().denominator()).isEqualTo(100);
    }

    @Test
    void update_scope_grows_denominator() {
        var listener = new RecordingListener();
        var goal = Goal.builder("growing")
                .addListener(listener)
                .addPhase(Phase.builder("expand").scope(2)
                        .execute(ctx -> {
                            ctx.progress(1);
                            ctx.updateScope(5);   // denominator climbs from 2 → 7
                            ctx.progress(4);
                        }).build())
                .build();

        var result = goal.run();
        assertThat(result.success()).isTrue();
        assertThat(listener.scopeUpdates).contains(5);
        // Auto-fill closes the gap: 1 + 4 reported + 2 auto-filled = 7.
        assertThat(goal.snapshot().numerator()).isEqualTo(7);
        assertThat(goal.snapshot().denominator()).isEqualTo(7);
    }

    @Test
    void dag_respects_requires_ordering() {
        List<String> order = new ArrayList<>();
        var goal = Goal.builder("dag")
                .addPhase(Phase.builder("setup").execute(ctx -> order.add("setup")).build())
                .addPhase(Phase.builder("middle").requires("setup")
                        .execute(ctx -> order.add("middle")).build())
                .addPhase(Phase.builder("end").requires("middle")
                        .execute(ctx -> order.add("end")).build())
                .build();

        goal.run();
        assertThat(order).containsExactly("setup", "middle", "end");
    }

    @Test
    void independent_async_phases_run_in_parallel() throws InterruptedException {
        CountDownLatch sawBothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var goal = Goal.builder("parallel")
                .addPhase(Phase.builder("a").kind(PhaseKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            release.await();
                        }).build())
                .addPhase(Phase.builder("b").kind(PhaseKind.IO)
                        .execute(ctx -> {
                            sawBothRunning.countDown();
                            release.await();
                        }).build())
                .build();

        Thread runner = new Thread(goal::run);
        runner.start();
        // Both phases must have entered execute() concurrently — proves
        // the IO pool dispatched them in parallel, not sequentially.
        assertThat(sawBothRunning.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        runner.join(2000);
    }

    @Test
    void failed_phase_cancels_dependent_phases() {
        var ran = new AtomicInteger();
        var goal = Goal.builder("fail")
                .addPhase(Phase.builder("ok").execute(ctx -> {}).build())
                .addPhase(Phase.builder("boom").requires("ok")
                        .execute(ctx -> { throw new RuntimeException("intentional"); }).build())
                .addPhase(Phase.builder("downstream").requires("boom")
                        .execute(ctx -> ran.incrementAndGet()).build())
                .build();

        var result = goal.run();
        assertThat(result.success()).isFalse();
        assertThat(ran).hasValue(0); // downstream never ran
        assertThat(result.phases())
                .extracting(GoalResult.PhaseReport::status)
                .containsExactly(PhaseStatus.SUCCESS, PhaseStatus.FAIL, PhaseStatus.CANCELLED);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().phase()).isEqualTo("boom");
    }

    @Test
    void warnings_accumulate_and_dont_fail_the_goal() {
        var goal = Goal.builder("nags")
                .addPhase(Phase.builder("one")
                        .execute(ctx -> {
                            ctx.warn("dep.unverified", "no checksum for X");
                            ctx.warn("dep.unverified", "no checksum for Y");
                        }).build())
                .build();
        var result = goal.run();
        assertThat(result.success()).isTrue();
        assertThat(result.warnings()).hasSize(2);
    }

    @Test
    void duplicate_phase_names_are_rejected() {
        assertThatThrownBy(() -> Goal.builder("dup")
                .addPhase(Phase.builder("x").execute(ctx -> {}).build())
                .addPhase(Phase.builder("x").execute(ctx -> {}).build())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void unknown_requires_is_rejected() {
        assertThatThrownBy(() -> Goal.builder("bad")
                .addPhase(Phase.builder("x").requires("nope").execute(ctx -> {}).build())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void cycle_is_rejected() {
        assertThatThrownBy(() -> Goal.builder("loop")
                .addPhase(Phase.builder("a").requires("b").execute(ctx -> {}).build())
                .addPhase(Phase.builder("b").requires("a").execute(ctx -> {}).build())
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void typed_state_flows_between_phases() {
        GoalKey<String> NAME = GoalKey.of("name", String.class);
        GoalKey<Integer> COUNT = GoalKey.of("count", Integer.class);
        List<String> consumed = new ArrayList<>();

        var goal = Goal.builder("flow")
                .addPhase(Phase.builder("producer").execute(ctx -> {
                    ctx.put(NAME, "widget");
                    ctx.put(COUNT, 42);
                }).build())
                .addPhase(Phase.builder("consumer").requires("producer")
                        .execute(ctx -> {
                            consumed.add(ctx.require(NAME));
                            consumed.add(String.valueOf((int) ctx.require(COUNT)));
                        }).build())
                .build();

        var result = goal.run();
        assertThat(result.success()).isTrue();
        assertThat(consumed).containsExactly("widget", "42");
        // Command body can read state back too.
        assertThat(goal.get(NAME)).hasValue("widget");
    }

    @Test
    void require_throws_when_key_missing() {
        GoalKey<String> MISSING = GoalKey.of("missing", String.class);
        var goal = Goal.builder("oops")
                .addPhase(Phase.builder("reader").execute(ctx -> ctx.require(MISSING)).build())
                .build();
        var result = goal.run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("required key 'missing'");
    }

    @Test
    void cancellation_propagates_to_running_phases() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch sawCancelled = new CountDownLatch(1);
        var goal = Goal.builder("cancellable")
                .addPhase(Phase.builder("worker").kind(PhaseKind.IO)
                        .execute(ctx -> {
                            started.countDown();
                            while (!ctx.cancelled()) {
                                Thread.sleep(10);
                            }
                            sawCancelled.countDown();
                        }).build())
                .build();

        Thread runner = new Thread(goal::run);
        runner.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        goal.requestCancel();
        assertThat(sawCancelled.await(2, TimeUnit.SECONDS)).isTrue();
        runner.join(2000);
        assertThat(goal.snapshot().cancelled()).isTrue();
    }

    /** Listener that records every event for assertion. */
    static final class RecordingListener implements GoalListener {
        final List<Integer> scopeUpdates = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        volatile GoalResult finalResult;

        @Override public void scopeUpdate(String phase, int delta, GoalView view) {
            scopeUpdates.add(delta);
        }
        @Override public void warn(String phase, String code, String message) {
            warnings.add(code + ":" + message);
        }
        @Override public void error(String phase, String code, String message) {
            errors.add(code + ":" + message);
        }
        @Override public void goalFinish(GoalResult result) {
            finalResult = result;
        }
    }
}
