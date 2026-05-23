// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli.tui;

import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the wizard against JLine's {@link DumbTerminal}. The terminal is
 * fed bytes through a {@link PipedOutputStream}; the wizard reads via its
 * normal {@code term.reader()} path. Raw-mode signal/attribute integration
 * is exercised manually in Task 7 — DumbTerminal's no-op {@code enterRawMode}
 * is enough to verify the state machine.
 */
class WizardTest {

    private static final long STEP_TIMEOUT_MS = 200L;

    private record Harness(DumbTerminal terminal, PipedOutputStream input, ByteArrayOutputStream output) {}

    private static Harness newHarness() throws IOException {
        var pipeIn = new PipedInputStream(8192);
        var pipeOut = new PipedOutputStream(pipeIn);
        var sink = new ByteArrayOutputStream();
        var term = new DumbTerminal("test", "ansi", pipeIn, sink, StandardCharsets.UTF_8);
        return new Harness(term, pipeOut, sink);
    }

    private static void write(OutputStream out, byte... bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    private static <T> T await(Future<T> f) throws Exception {
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw e;
        }
    }

    @Test
    void input_step_records_typed_string() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Project name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 'f', (byte) 'o', (byte) 'o', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("foo");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void radio_step_records_default_on_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.RadioStep.horizontal("lang", "Language")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("java");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void radio_step_navigates_right_then_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.RadioStep.horizontal("lang", "Language")
                        .choice("java", "Java")
                        .choice("kotlin", "Kotlin")
                        .defaultChoice("java")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("lang")).isEqualTo("kotlin");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void multi_select_toggles_with_space() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.MultiSelectStep.vertical("deps", "Dependencies")
                        .choice("lombok", "Lombok")
                        .choice("guava", "Guava")
                        .choice("commons-io", "Commons IO")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            // Select first (lombok), move down twice, select third (commons-io), enter.
            write(h.input(), (byte) 0x20);
            Thread.sleep(50);
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            Thread.sleep(50);
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'B');
            Thread.sleep(50);
            write(h.input(), (byte) 0x20);
            Thread.sleep(50);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "commons-io");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void multi_select_toggle_all_with_a() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.MultiSelectStep.vertical("deps", "Dependencies")
                        .choice("lombok", "Lombok")
                        .choice("guava", "Guava")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 'a');
            Thread.sleep(50);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.getList("deps")).containsExactly("lombok", "guava");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void output_step_advances_on_enter() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .step(WizardStep.OutputStep.of("preview", a -> java.util.List.of("Hello " + a.get("name"))).build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 'b', (byte) 'a', (byte) 'r', (byte) 0x0A);
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("bar");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void ctrl_c_returns_empty() throws Exception {
        var h = newHarness();
        var savedBefore = h.terminal().getAttributes();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x03);
            var answers = await(result);
            assertThat(answers).isEmpty();
            // Attributes should be restored to a value matching the snapshot prior to entry.
            var savedAfter = h.terminal().getAttributes();
            assertThat(savedAfter.getControlChars()).isEqualTo(savedBefore.getControlChars());
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void conditional_step_is_skipped_when_predicate_false() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.RadioStep.horizontal("mode", "Mode")
                        .choice("lib", "Library")
                        .choice("bin", "Binary")
                        .defaultChoice("lib")
                        .build())
                .step(WizardStep.InputStep.of("main", "Main class")
                        .when(a -> "bin".equals(a.get("mode")))
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            // Accept default ("lib") and let the wizard skip the conditional step.
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("mode")).isEqualTo("lib");
            assertThat(answers.asMap()).doesNotContainKey("main");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void backspace_deletes_char() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 'a', (byte) 'b', (byte) 'c', (byte) 0x7F, (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("ab");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void right_arrow_realizes_placeholder_into_input() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name")
                        .placeholder("widget")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            // Right-arrow then Enter → placeholder becomes the answer.
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void right_arrow_realized_text_can_be_edited_further() throws Exception {
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name")
                        .placeholder("widget")
                        .build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            // Realize "widget", then append "-2" → final = "widget-2".
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) '-', (byte) '2', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("widget-2");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }

    @Test
    void right_arrow_is_a_noop_when_placeholder_is_empty() throws Exception {
        // No placeholder → Right is ignored (Enter falls through to default
        // value, which here is also empty, so input is just "").
        var h = newHarness();
        var wizard = Wizard.builder()
                .title("Test")
                .step(WizardStep.InputStep.of("name", "Name").build())
                .build();

        var exec = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<Answers>> result = exec.submit(() -> wizard.run(h.terminal()));
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 0x1B, (byte) '[', (byte) 'C');
            Thread.sleep(STEP_TIMEOUT_MS);
            write(h.input(), (byte) 'h', (byte) 'i', (byte) 0x0A);
            var answers = await(result).orElseThrow();
            assertThat(answers.get("name")).isEqualTo("hi");
        } finally {
            exec.shutdownNow();
            h.terminal().close();
        }
    }
}
