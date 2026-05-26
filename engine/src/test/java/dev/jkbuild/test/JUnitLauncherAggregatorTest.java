// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whitebox tests for {@link JUnitLauncher.ResultAggregator}. The aggregator
 * processes the NDJSON event stream the workers emit; we feed it raw lines
 * here rather than spinning up a real fork.
 */
class JUnitLauncherAggregatorTest {

    @Test
    void counts_successful_failed_and_skipped_tests() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"e\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"b\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"display\":\"c()\","
                + "\"throwable\":{\"class\":\"AssertionError\",\"message\":\"nope\"}}");
        agg.accept("{\"e\":\"skipped\",\"id\":\"d\",\"type\":\"TEST\",\"reason\":\"@Disabled\"}");

        var result = agg.toResult(0);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .satisfies(f -> {
                    assertThat(f.testName()).isEqualTo("c()");
                    assertThat(f.message()).contains("AssertionError").contains("nope");
                });
    }

    @Test
    void container_events_do_not_count_toward_test_totals() {
        // JUnit fires FINISHED for engine roots and test classes too — those
        // are CONTAINER nodes and must not inflate the test count.
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"e\":\"finished\",\"id\":\"engine\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"class\",\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"method\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void merges_event_streams_from_multiple_workers() {
        // Simulate two parallel workers each running a couple of classes.
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"e\":\"finished\",\"id\":\"w1.a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"w\":1}");
        agg.accept("{\"e\":\"finished\",\"id\":\"w2.x\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"w\":2}");
        agg.accept("{\"e\":\"finished\",\"id\":\"w1.b\",\"type\":\"TEST\",\"status\":\"FAILED\",\"w\":1,"
                + "\"display\":\"b()\",\"throwable\":{\"class\":\"E\",\"message\":\"m\"}}");
        agg.accept("{\"e\":\"finished\",\"id\":\"w2.y\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\",\"w\":2}");

        var result = agg.toResult(0);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void ready_and_plan_events_are_ignored_for_counts() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"e\":\"ready\",\"w\":1}");
        agg.accept("{\"e\":\"plan_started\"}");
        agg.accept("{\"e\":\"plan_finished\",\"duration_ms\":100}");
        agg.accept("{\"e\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");

        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void malformed_json_does_not_blow_up_the_aggregator() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("not actually json");
        agg.accept("{\"e\":\"finished\",\"id\":\"a\",\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        assertThat(agg.toResult(0).total()).isEqualTo(1);
    }

    @Test
    void non_zero_exit_with_empty_results_reports_a_run_level_failure() {
        // Worker crashed before emitting any tests — we still want a non-zero
        // pass/fail signal.
        var agg = new JUnitLauncher.ResultAggregator();
        var result = agg.toResult(2);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures()).singleElement()
                .extracting(JUnitLauncher.Failure::testName)
                .isEqualTo("(test run)");
    }
}
