// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Whitebox tests for {@link JUnitLauncher.ResultAggregator}. The aggregator processes the NDJSON
 * event stream the workers emit; we feed it raw lines here rather than spinning up a real fork.
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
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).isEqualTo("c()");
            assertThat(f.exceptionClass()).isEqualTo("AssertionError");
            assertThat(f.message()).isEqualTo("nope");
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
    void dynamic_registered_events_mark_subsequent_finished_as_non_static() {
        // The aggregator must remodule every id it saw via dynamic_registered
        // (TEST type) and stamp wasStatic=false on the matching finished
        // event. Plain @Test methods are not preceded by dynamic_registered
        // and should arrive as wasStatic=true.
        var captured = new java.util.ArrayList<boolean[]>(); // [isTest, wasStatic]
        var listener = new TestProgressListener() {
            @Override
            public void onTestFinished(
                    String id,
                    String display,
                    String status,
                    boolean isTest,
                    boolean wasStatic,
                    long durationMs,
                    int workerId) {
                captured.add(new boolean[] {isTest, wasStatic});
            }
        };
        var agg = new JUnitLauncher.ResultAggregator(listener, 0);

        // Plain static test — no preceding dynamic_registered.
        agg.accept("{\"e\":\"finished\",\"id\":\"static-1\"," + "\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        // Parameterized invocation — preceded by dynamic_registered.
        agg.accept("{\"e\":\"dynamic_registered\",\"id\":\"dyn-1\",\"type\":\"TEST\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"dyn-1\"," + "\"type\":\"TEST\",\"status\":\"SUCCESSFUL\"}");
        // CONTAINER-typed dynamic_registered must NOT count as a dynamic
        // test id — its later finished (also CONTAINER) shouldn't affect
        // progress regardless.
        agg.accept("{\"e\":\"dynamic_registered\",\"id\":\"c-1\",\"type\":\"CONTAINER\"}");
        agg.accept("{\"e\":\"finished\",\"id\":\"c-1\"," + "\"type\":\"CONTAINER\",\"status\":\"SUCCESSFUL\"}");

        assertThat(captured).hasSize(3);
        assertThat(captured.get(0)).containsExactly(true, true); // static @Test
        assertThat(captured.get(1)).containsExactly(true, false); // parameterized
        assertThat(captured.get(2)).containsExactly(false, false); // container
    }

    @Test
    void non_zero_exit_with_empty_results_reports_a_run_level_failure() {
        // Worker crashed before emitting any tests — we still want a non-zero
        // pass/fail signal.
        var agg = new JUnitLauncher.ResultAggregator();
        var result = agg.toResult(2);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures())
                .singleElement()
                .extracting(JUnitLauncher.Failure::testName)
                .isEqualTo("(test run)");
    }

    @Test
    void failed_test_keeps_the_full_stack_trace() {
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept("{\"e\":\"finished\",\"id\":\"c\",\"type\":\"TEST\",\"status\":\"FAILED\","
                + "\"display\":\"c()\",\"throwable\":{\"class\":\"AssertionError\","
                + "\"message\":\"nope\",\"stack\":\"AssertionError: nope\\n\\tat Foo.c(Foo.java:9)\"}}");
        assertThat(agg.toResult(0).failures()).singleElement().satisfies(f -> assertThat(f.details())
                .contains("AssertionError: nope")
                .contains("at Foo.c(Foo.java:9)"));
    }

    @Test
    void container_failure_is_captured_so_init_errors_are_visible() {
        // A class initializer / @BeforeAll error finishes the CONTAINER as FAILED
        // and fires no TEST event — capture it instead of a silent "runner exited".
        var agg = new JUnitLauncher.ResultAggregator();
        agg.accept(
                "{\"e\":\"finished\",\"id\":\"cls\",\"type\":\"CONTAINER\",\"status\":\"FAILED\","
                        + "\"display\":\"FooTest\",\"throwable\":{\"class\":\"ExceptionInInitializerError\","
                        + "\"message\":\"\",\"stack\":\"ExceptionInInitializerError\\n\\tat FooTest.<clinit>(FooTest.java:3)\"}}");
        var result = agg.toResult(0);
        assertThat(result.allPassed()).isFalse();
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).contains("FooTest");
            assertThat(f.details()).contains("ExceptionInInitializerError");
        });
    }

    @Test
    void crash_output_is_attached_to_the_synthetic_failure() {
        var agg = new JUnitLauncher.ResultAggregator();
        String crash = "Exception in thread \"main\" java.lang.NoClassDefFoundError: Missing\n"
                + "\tat dev.jkbuild.Boot.main(Boot.java:1)";
        var result = agg.toResult(1, crash); // no events, non-zero exit
        assertThat(result.failures()).singleElement().satisfies(f -> {
            assertThat(f.testName()).isEqualTo("(test run)");
            assertThat(f.details()).contains("NoClassDefFoundError").contains("at dev.jkbuild.Boot.main");
        });
    }

    @Test
    void capture_buffer_keeps_only_the_last_lines() {
        var buf = new JUnitLauncher.CaptureBuffer();
        for (int i = 0; i < 1000; i++) buf.add("line " + i);
        String text = buf.text();
        assertThat(text).contains("line 999").doesNotContain("line 0\n");
        assertThat(text.split("\n")).hasSizeLessThanOrEqualTo(400);
    }
}
