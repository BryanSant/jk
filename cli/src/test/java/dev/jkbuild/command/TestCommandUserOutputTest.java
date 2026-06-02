// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.PhaseContext;
import dev.jkbuild.test.TestProgressListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the verbose gate on test-process stdout/stderr forwarding
 * and the Goal-framework wiring of the bridge listener.
 */
class TestCommandUserOutputTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void replaceStdout() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void user_output_is_suppressed_when_verbose_is_false() {
        var ctx = new RecordingContext();
        TestProgressListener listener =
                TestCommand.bridgeListener(ctx, /* workerCount */ 1, /* verbose */ false);

        listener.onUserOutput(0, "hello from a passing test");

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .doesNotContain("hello from a passing test");
    }

    @Test
    void user_output_is_forwarded_when_verbose_is_true() {
        var ctx = new RecordingContext();
        TestProgressListener listener =
                TestCommand.bridgeListener(ctx, /* workerCount */ 1, /* verbose */ true);

        listener.onUserOutput(0, "hello from a passing test");

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("hello from a passing test");
    }

    @Test
    void worker_prefix_appears_in_parallel_mode() {
        var ctx = new RecordingContext();
        TestProgressListener listener =
                TestCommand.bridgeListener(ctx, /* workerCount */ 4, /* verbose */ true);

        listener.onUserOutput(2, "from worker two");

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("[w2] from worker two");
    }

    @Test
    void discovery_total_does_not_reshape_phase_scope() {
        // The runTests phase's scope was set up-front from the lexical
        // estimate — the runner's runtime discovery_total event must NOT
        // reshape the denominator, or the bar would visibly reset when
        // the test JVM finishes its static walk.
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onDiscoveryTotal(/* classes */ 3, /* tests */ 42);

        assertThat(ctx.scopeAdded).isZero();
    }

    @Test
    void static_test_finished_ticks_progress_and_sets_label() {
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onTestFinished("id", "ClassA.method", "PASSED",
                /* isTest */ true, /* wasStatic */ true, 5L, 0);

        assertThat(ctx.progressTicks).containsExactly(1);
        assertThat(ctx.labels).containsExactly("ClassA.method");
    }

    @Test
    void dynamic_test_finished_sets_label_but_does_not_tick() {
        // @ParameterizedTest invocations etc. run and finish but the bar's
        // numerator must NOT advance — they're not in the denominator
        // either, so a tick here would push num past den.
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onTestFinished("id", "ClassA.parameterized[1]", "PASSED",
                /* isTest */ true, /* wasStatic */ false, 5L, 0);

        assertThat(ctx.progressTicks).isEmpty();
        assertThat(ctx.labels).containsExactly("ClassA.parameterized[1]");
    }

    @Test
    void static_test_skipped_ticks_progress() {
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onTestSkipped("id", "ClassA.disabled",
                "@Disabled", /* isTest */ true, /* wasStatic */ true, 0);

        assertThat(ctx.progressTicks).containsExactly(1);
    }

    @Test
    void dynamic_test_skipped_does_not_tick() {
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onTestSkipped("id", "ClassA.parameterized[3]",
                "assumption failed", /* isTest */ true, /* wasStatic */ false, 0);

        assertThat(ctx.progressTicks).isEmpty();
    }

    @Test
    void failures_route_through_ctx_error() {
        var ctx = new RecordingContext();
        TestProgressListener listener = TestCommand.bridgeListener(ctx, 1, false);

        listener.onFailure("id", "ClassA.brokenTest",
                "java.lang.AssertionError", "expected 5 got 4", 0);

        assertThat(ctx.errors).hasSize(1);
        assertThat(ctx.errors.get(0).code()).isEqualTo("test");
        assertThat(ctx.errors.get(0).message())
                .isEqualTo("ClassA.brokenTest: java.lang.AssertionError — expected 5 got 4");
    }

    @Test
    void estimate_test_count_counts_test_annotations(@TempDir Path tempDir) throws IOException {
        Path src = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("FooTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                class FooTest {
                    @Test void a() {}
                    @Test void b() {}
                    @ParameterizedTest void c() {}
                    @TestFactory void d() {}
                    @RepeatedTest(3) void e() {}
                    @TestTemplate void f() {}
                    void notATest() {}
                }
                """);

        assertThat(TestCommand.estimateTestCount(tempDir.resolve("src/test/java")))
                .isEqualTo(6);
    }

    @Test
    void estimate_test_count_for_missing_dir_is_zero(@TempDir Path tempDir) {
        assertThat(TestCommand.estimateTestCount(tempDir.resolve("does/not/exist")))
                .isZero();
    }

    @Test
    void estimate_test_count_does_not_match_unrelated_annotations(@TempDir Path tempDir) throws IOException {
        // The regex must not catch @TestSetup, @TestableMethod, etc. —
        // word-boundary anchoring keeps those out.
        Path src = tempDir.resolve("src/test/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Lookalikes.java"), """
                @TestSetup
                @TestableMethod
                @TestingInProgress
                @Test
                """);

        assertThat(TestCommand.estimateTestCount(src)).isEqualTo(1);
    }

    /** Minimal PhaseContext stub that records every call. */
    private static final class RecordingContext implements PhaseContext {
        int scopeAdded = 0;
        final List<Integer> progressTicks = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Diag> errors = new ArrayList<>();

        record Diag(String code, String message) {}

        @Override public void progress(int delta) { progressTicks.add(delta); }
        @Override public void updateScope(int additionalScope) { scopeAdded += additionalScope; }
        @Override public void label(String description) { labels.add(description); }
        @Override public void warn(String code, String message) {}
        @Override public void error(String code, String message) { errors.add(new Diag(code, message)); }
        @Override public boolean cancelled() { return false; }
        @Override public <T> void put(GoalKey<T> key, T value) {}
        @Override public <T> Optional<T> get(GoalKey<T> key) { return Optional.empty(); }
        @Override public <T> T require(GoalKey<T> key) { throw new IllegalStateException(); }
    }
}
