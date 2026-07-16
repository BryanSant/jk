// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

/**
 * {@link StackTraceHighlight} paints genuine stack-trace lines (frame, summary, "{@code … N more}")
 * and leaves everything else byte-for-byte unchanged.
 */
class StackTraceHighlightTest {

    private static final String ESC = "\u001b";

    private static String plain(String s) {
        return AttributedString.stripAnsi(s);
    }

    @Test
    void a_frame_colors_class_method_file_and_line() {
        String raw = "\tat cc.jumpkick.FooTest.bar(FooTest.java:42)";
        String out = StackTraceHighlight.line(raw);
        assertThat(out).contains(ESC);
        assertThat(plain(out)).isEqualTo(raw); // round-trips
        assertThat(out).contains(Theme.colorize("FooTest", Theme.active().synType()));
        assertThat(out).contains(Theme.colorize("bar", Theme.active().synFunction()));
        assertThat(out).contains(Theme.colorize("FooTest.java", Theme.active().path()));
        assertThat(out).contains(Theme.colorize("42", Theme.active().synNumber()));
    }

    @Test
    void a_caused_by_summary_colors_the_prefix_and_exception() {
        String raw = "Caused by: java.lang.NullPointerException: oops";
        String out = StackTraceHighlight.line(raw);
        assertThat(out).contains(ESC);
        assertThat(plain(out)).isEqualTo(raw);
        assertThat(out).contains(Theme.colorize("Caused by:", Theme.active().synKeyword()));
        assertThat(out)
                .contains(Theme.colorize("NullPointerException", Theme.active().synType()));
    }

    @Test
    void a_more_line_colors_its_count() {
        String raw = "\t... 3 more";
        String out = StackTraceHighlight.line(raw);
        assertThat(plain(out)).isEqualTo(raw);
        assertThat(out).contains(Theme.colorize("3", Theme.active().synNumber()));
    }

    @Test
    void plain_lines_pass_through_untouched() {
        // A bare message, a failure header, a 'key: value' note, and ordinary
        // program output must not be mistaken for a trace and must not gain ANSI.
        for (String raw : new String[] {
            "boom",
            "1 test failed:",
            "  FAILED  cc.jumpkick.FooTest > bar()",
            "Note: Recompile with -Xlint",
            "Building project..."
        }) {
            assertThat(StackTraceHighlight.line(raw)).isEqualTo(raw);
        }
    }
}
