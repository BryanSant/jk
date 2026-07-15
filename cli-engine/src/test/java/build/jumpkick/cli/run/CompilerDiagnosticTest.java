// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

/**
 * {@link CompilerDiagnostic} must colorize a javac/kotlinc block without changing its text:
 * stripping the ANSI codes has to yield the compiler's verbatim output (only the path is
 * relativized, by the caller's anchor).
 */
class CompilerDiagnosticTest {

    private static final String ESC = "\u001b";

    /** ANSI-stripped view of the rendered block — what an agent pastes back. */
    private static String plain(String rendered) {
        return AttributedString.stripAnsi(rendered);
    }

    @Test
    void caret_block_round_trips_to_plain_text() {
        String raw = String.join(
                "\n",
                "Foo.java:17: error: cannot find symbol",
                "    @Test",
                "     ^",
                "  symbol:   class Test",
                "  location: class Foo");
        String rendered = CompilerDiagnostic.render(raw);
        assertThat(rendered).contains(ESC); // color was added
        assertThat(plain(rendered)).isEqualTo(raw); // ...but the text is byte-for-byte javac's
    }

    @Test
    void highlights_exactly_the_caret_character() {
        String raw = String.join("\n", "Foo.java:17: error: cannot find symbol", "    @Test", "     ^");
        String rendered = CompilerDiagnostic.render(raw);
        assertThat(rendered).contains(ESC);
        assertThat(plain(rendered)).isEqualTo(raw);
    }

    @Test
    void single_line_diagnostic_with_no_snippet() {
        String raw = "Bar.java:3: error: package org.junit.jupiter.api does not exist";
        assertThat(plain(CompilerDiagnostic.render(raw))).isEqualTo(raw);
    }

    @Test
    void kotlin_header_with_column_round_trips() {
        String raw = String.join("\n", "Baz.kt:10:5: error: unresolved reference: Test", "    @Test", "     ^");
        assertThat(plain(CompilerDiagnostic.render(raw))).isEqualTo(raw);
    }

    @Test
    void non_diagnostic_text_passes_through_untouched() {
        String raw = "just some text\nwith no header";
        assertThat(CompilerDiagnostic.render(raw)).isEqualTo(raw);
    }
}
