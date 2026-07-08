// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.run.TestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link TestSupport#renderFailures} surfaces each failure's name + stack, not just a count. */
class TestFailureRenderingTest {

    @Test
    void no_failures_renders_nothing() {
        var result = new TestSummary(3, 3, 0, 0, List.of());
        assertThat(TestSupport.renderFailures(result)).isEmpty();
    }

    @Test
    void a_failure_renders_name_and_indented_stack() {
        var f = new TestSummary.Failure(
                "dev.jkbuild.FooTest > bar()",
                "org.opentest4j.AssertionFailedError",
                "expected: <1> but was: <2>",
                "org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n"
                        + "\tat dev.jkbuild.FooTest.bar(FooTest.java:42)");
        var result = new TestSummary(1, 0, 1, 0, List.of(f));

        List<String> lines = TestSupport.renderFailures(result);
        String text = String.join("\n", lines);

        assertThat(lines).anyMatch(l -> l.equals("1 test failed:"));
        assertThat(lines).anyMatch(l -> l.contains("FAILED  dev.jkbuild.FooTest > bar()"));
        assertThat(text).contains("AssertionFailedError: expected: <1> but was: <2>");
        assertThat(text).contains("at dev.jkbuild.FooTest.bar(FooTest.java:42)");
    }

    @Test
    void pluralises_and_falls_back_to_message_when_no_stack() {
        var a = new TestSummary.Failure("A > x()", "", "boom", "boom\n\tat A.x(A.java:1)");
        var b = new TestSummary.Failure("(test run)", "", "runner exited 1", ""); // no stack
        var result = new TestSummary(2, 0, 2, 0, List.of(a, b));

        List<String> lines = TestSupport.renderFailures(result);
        assertThat(lines).anyMatch(l -> l.equals("2 tests failed:"));
        // The stack-less failure falls back to its one-line message.
        assertThat(lines).anyMatch(l -> l.contains("FAILED  (test run)"));
        assertThat(String.join("\n", lines)).contains("runner exited 1");
    }
}
