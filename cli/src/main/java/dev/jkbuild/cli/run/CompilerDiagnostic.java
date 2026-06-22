// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.theme.Theme;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a compiler's (javac/kotlinc) verbatim diagnostic block — header,
 * source snippet, caret, and {@code symbol:}/{@code location:} trailers — with
 * relative paths and color, without altering the text itself. Stripping the
 * ANSI codes yields exactly what the compiler printed, only with the absolute
 * path shortened (see {@link PathDisplay}), so the output stays paste-friendly
 * for agents and greppable for humans.
 *
 * <p>Color map: path {@link Theme#highlight() yellow}, line/column
 * {@link Theme#cyan() cyan}, {@code key: value} trailer values cyan, and the
 * source line above a {@code ^} caret syntax-highlighted (see
 * {@link SyntaxHighlight}) with the caret's character additionally underlined.
 */
public final class CompilerDiagnostic {

    private CompilerDiagnostic() {}

    /** {@code <path ending in .java/.kt/.kts>:<line>[:<col>]:<rest>}. */
    private static final Pattern HEADER = Pattern.compile(
            "^(?<file>.+?\\.(?:java|kt|kts)):(?<line>\\d+)(?::(?<col>\\d+))?:(?<rest>.*)$");

    /** A caret line: optional indent, a single {@code ^}, optional trailing space. */
    private static final Pattern CARET = Pattern.compile("^(\\s*)\\^\\s*$");

    /** An indented {@code label: value} trailer (symbol:, location:, required:, found:, …). */
    private static final Pattern KEY_VALUE = Pattern.compile("^(\\s+\\S[^:]*:)(\\s+)(\\S.*)$");

    /** Colorize a raw javac/kotlinc block (one diagnostic, or kotlinc's whole batch). */
    public static String render(String rawBlock) {
        String[] lines = rawBlock.split("\n", -1);
        StringBuilder out = new StringBuilder();
        // The language of the source snippets, tracked from the most recent header's
        // file extension (.kt/.kts → Kotlin, else Java). kotlinc batches several
        // diagnostics in one block, so re-read it per header rather than once.
        SyntaxHighlight.Language lang = SyntaxHighlight.Language.JAVA;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                lang = languageOf(header.group("file"));
                out.append(header(header));
            } else if (CARET.matcher(line).matches()) {
                out.append(line);   // the highlighted char was emitted on the line above
            } else if (i + 1 < lines.length && CARET.matcher(lines[i + 1]).matches()) {
                out.append(sourceWithCaret(line, lines[i + 1], lang));
            } else {
                Matcher kv = KEY_VALUE.matcher(line);
                if (kv.matches()) {
                    out.append(kv.group(1)).append(kv.group(2))
                            .append(Theme.colorize(kv.group(3), Theme.active().cyan()));
                } else {
                    out.append(line);
                }
            }
        }
        return out.toString();
    }

    private static String header(Matcher h) {
        Theme th = Theme.active();
        String rel = PathDisplay.of(Path.of(h.group("file")));
        StringBuilder sb = new StringBuilder()
                .append(Theme.colorize(rel, th.path()))
                .append(':').append(Theme.colorize(h.group("line"), th.cyan()));
        if (h.group("col") != null) {
            sb.append(':').append(Theme.colorize(h.group("col"), th.cyan()));
        }
        return sb.append(':').append(h.group("rest")).toString();
    }

    /**
     * Syntax-highlight the source line and underline the single character the
     * caret points at. A misaligned caret (out of range) still highlights the
     * line, just without an underline — {@link SyntaxHighlight} handles both.
     */
    private static String sourceWithCaret(String src, String caretLine, SyntaxHighlight.Language lang) {
        return SyntaxHighlight.highlight(src, caretLine.indexOf('^'), lang);
    }

    /** Java unless the header's file ends in {@code .kt}/{@code .kts}. */
    private static SyntaxHighlight.Language languageOf(String file) {
        return file.endsWith(".kt") || file.endsWith(".kts")
                ? SyntaxHighlight.Language.KOTLIN
                : SyntaxHighlight.Language.JAVA;
    }
}
