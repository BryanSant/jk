// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * In tests there is no controlling terminal, so {@link Confirm#ask()} takes the cooked (non-TTY)
 * fallback — the same path piped/CI input hits. We drive it via {@code System.in} and assert the
 * y/n/default/EOF semantics, plus that the prompt is written to stderr (so it stays visible when
 * stdout is redirected).
 */
class ConfirmTest {

    @Test
    void typed_yes_and_no_are_honored() {
        assertThat(askWith("y\n", /*defaultYes*/ false)).isTrue();
        assertThat(askWith("yes\n", false)).isTrue();
        assertThat(askWith("n\n", /*defaultYes*/ true)).isFalse();
        assertThat(askWith("no\n", true)).isFalse();
    }

    @Test
    void empty_line_takes_the_default() {
        assertThat(askWith("\n", /*defaultYes*/ true)).isTrue();
        assertThat(askWith("\n", /*defaultYes*/ false)).isFalse();
    }

    @Test
    void eof_declines_regardless_of_default() {
        assertThat(askWith("", /*defaultYes*/ true)).isFalse();
        assertThat(askWith("", /*defaultYes*/ false)).isFalse();
    }

    @Test
    void prompt_is_written_to_stderr_not_stdout() {
        InputStream savedIn = System.in;
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Confirm.of("Proceed?", false).ask();
        } finally {
            System.setIn(savedIn);
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
        // The question rides stderr so `cmd | less` still shows it; stdout stays clean for piping.
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Proceed?");
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("Proceed?");
    }

    private static boolean askWith(String input, boolean defaultYes) {
        InputStream savedIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
            return Confirm.of("Proceed?", defaultYes).ask();
        } finally {
            System.setIn(savedIn);
        }
    }
}
