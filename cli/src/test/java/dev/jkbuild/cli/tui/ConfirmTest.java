// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * In tests {@code System.console()} is null, so {@link Confirm#ask()} takes the
 * cooked (non-TTY) fallback — the same path piped/CI input hits. We drive it via
 * {@code System.in} and assert the y/n/default/EOF semantics.
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
