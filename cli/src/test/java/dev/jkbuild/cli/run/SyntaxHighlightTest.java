// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

/**
 * {@link SyntaxHighlight} colorizes a single source line without changing its
 * text, and overlays the caret underline on the highlighted character.
 */
class SyntaxHighlightTest {

    private static final String ESC = "\u001b";

    private static String plain(String s) {
        return AttributedString.stripAnsi(s);
    }

    @Test
    void highlighting_never_changes_the_text() {
        String src = "    public static final int X = 0x1F; // hi";
        String out = SyntaxHighlight.highlight(src, -1);
        assertThat(out).contains(ESC); // styling was applied
        assertThat(plain(out)).isEqualTo(src); // ...but the text round-trips exactly
    }

    @Test
    void keywords_are_colored_with_the_keyword_style() {
        String keyword = Theme.colorize("class", Theme.active().synKeyword());
        assertThat(SyntaxHighlight.highlight("class Foo {", -1)).contains(keyword);
    }

    @Test
    void string_literals_are_colored_with_the_string_style() {
        String lit = Theme.colorize("\"hi\"", Theme.active().synString());
        assertThat(SyntaxHighlight.highlight("var s = \"hi\";", -1)).contains(lit);
    }

    @Test
    void capitalized_identifiers_are_colored_as_types() {
        String type = Theme.colorize("ParseException", Theme.active().synType());
        assertThat(SyntaxHighlight.highlight("ParseException tooMany;", -1)).contains(type);
    }

    @Test
    void identifier_before_paren_is_colored_as_a_function() {
        String fn = Theme.colorize("parse", Theme.active().synFunction());
        assertThat(SyntaxHighlight.highlight("parse(oneRequired);", -1)).contains(fn);
    }

    @Test
    void all_caps_identifiers_are_colored_as_constants() {
        String konst = Theme.colorize("THROWABLE", Theme.active().synConstant());
        assertThat(SyntaxHighlight.highlight("Class<THROWABLE> c;", -1)).contains(konst);
    }

    @Test
    void plain_variables_stay_uncolored() {
        // a lowercase identifier not followed by '(' is a variable — left plain.
        assertThat(SyntaxHighlight.highlight("tooMany = 1;", -1)).startsWith("tooMany");
    }

    @Test
    void caret_char_keeps_its_token_color_and_gains_an_underline() {
        // "new" is a keyword; underline the 'e' (index 1) within it.
        String src = "new Foo()";
        String out = SyntaxHighlight.highlight(src, 1);
        AttributedStyle kw = Theme.active().synKeyword();
        assertThat(out)
                .contains(Theme.colorize("n", kw)) // un-underlined keyword head
                .contains(Theme.colorize("e", kw.underline())) // caret char: color + underline
                .contains(Theme.colorize("w", kw)); // un-underlined keyword tail
        assertThat(plain(out)).isEqualTo(src);
    }

    @Test
    void misaligned_caret_still_highlights_without_underline() {
        String src = "int x;";
        String out = SyntaxHighlight.highlight(src, 999); // caret past EOL
        assertThat(out).isEqualTo(SyntaxHighlight.highlight(src, -1)); // identical to no-caret render
        assertThat(plain(out)).isEqualTo(src);
    }

    @Test
    void kotlin_keywords_are_colored() {
        String src = "fun greet(name: String) = name";
        String fun = Theme.colorize("fun", Theme.active().synKeyword());
        String out = SyntaxHighlight.highlight(src, -1, SyntaxHighlight.Language.KOTLIN);
        assertThat(out).contains(fun);
        assertThat(plain(out)).isEqualTo(src); // text still round-trips
    }

    @Test
    void kotlin_does_not_color_types_per_prism_grammar() {
        // Prism's Kotlin grammar deletes clike's `class-name`, so a Capitalized
        // identifier is NOT painted as a type (unlike Java).
        String src = "val x: String";
        String typed = Theme.colorize("String", Theme.active().synType());
        assertThat(SyntaxHighlight.highlight(src, -1, SyntaxHighlight.Language.KOTLIN))
                .doesNotContain(typed);
    }
}
