// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Whitebox tests for {@link WorkspaceScheduler}: the unbounded batch-per-level path and the bounded
 * rolling-window path (including the strict-serial {@code cap = 1} used by {@code --no-parallel}).
 */
class WorkspaceSchedulerTest {

    private static Path p(String s) {
        return Path.of(s);
    }

    /**
     * A diamond DAG: {@code a} → {b, c} → {@code d}. Returns edges keyed by unit dir → its prereq
     * dirs, matching {@link BuildGraph.Result#edges()}'s convention.
     */
    private static Map<Path, Set<Path>> diamondEdges() {
        return Map.of(
                p("a"), Set.of(),
                p("b"), Set.of(p("a")),
                p("c"), Set.of(p("a")),
                p("d"), Set.of(p("b"), p("c")));
    }

    private static List<String> diamondUnits() {
        return List.of("a", "b", "c", "d");
    }

    /** Runs the scheduler tracking peak concurrency + completion order; every unit succeeds. */
    private record Trace(int peakConcurrency, List<String> completionOrder, int sinkCalls, int maxBatch) {}

    private static Trace trace(int maxConcurrency) {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        int[] sinkCalls = {0};
        int[] maxBatch = {0};
        WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(40); // hold the slot so genuine overlap is observable
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    completed.add(unit);
                    return unit; // non-null result, but the sink returns null so the schedule continues
                },
                (justCompleted, results, remaining) -> {
                    sinkCalls[0]++;
                    maxBatch[0] = Math.max(maxBatch[0], justCompleted.size());
                    assertThat(results).hasSameSizeAs(justCompleted);
                    return null;
                },
                maxConcurrency);
        return new Trace(peak.get(), new ArrayList<>(completed), sinkCalls[0], maxBatch[0]);
    }

    /** Assert every unit ran only after its prereqs finished (positional check on completion order). */
    private static void assertDependencyOrder(List<String> order) {
        assertThat(order).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("c"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("d"));
        assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
    }

    @Test
    void cap1_runs_strictly_serial_in_dependency_order() {
        Trace t = trace(1);
        assertThat(t.peakConcurrency()).isEqualTo(1); // never two modules at once
        assertDependencyOrder(t.completionOrder());
        // Bounded cadence: the sink is called once per completed unit.
        assertThat(t.sinkCalls()).isEqualTo(4);
        assertThat(t.maxBatch()).isEqualTo(1);
    }

    @Test
    void cap2_bounds_concurrency_at_two_and_respects_order() {
        Trace t = trace(2);
        assertThat(t.peakConcurrency()).isBetween(1, 2); // b and c may overlap, but never three
        assertDependencyOrder(t.completionOrder());
        assertThat(t.sinkCalls()).isEqualTo(4); // still one sink call per unit under a cap
        assertThat(t.maxBatch()).isEqualTo(1);
    }

    @Test
    void unbounded_batches_per_level_and_preserves_order() {
        Trace t = trace(0);
        assertThat(t.peakConcurrency()).isEqualTo(2); // the {b, c} level runs both at once
        assertDependencyOrder(t.completionOrder());
        // Batch-per-level cadence: levels [a], [b, c], [d] → 3 sink calls, one batch of size 2.
        assertThat(t.sinkCalls()).isEqualTo(3);
        assertThat(t.maxBatch()).isEqualTo(2);
    }

    @Test
    void bounded_fails_fast_and_stops_admitting() {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    ran.add(unit);
                    return unit;
                },
                (justCompleted, results, remaining) ->
                        justCompleted.contains("a") ? "FAILED" : null, // stop after the root
                1);
        assertThat(stop).isEqualTo("FAILED");
        // Serial + fail-fast: nothing past the failing unit's dependents should have started.
        assertThat(ran).containsExactly("a");
    }

    @Test
    void unbounded_fails_fast() {
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> unit,
                (justCompleted, results, remaining) -> justCompleted.contains("a") ? "FAILED" : null,
                0);
        assertThat(stop).isEqualTo("FAILED");
    }
}
