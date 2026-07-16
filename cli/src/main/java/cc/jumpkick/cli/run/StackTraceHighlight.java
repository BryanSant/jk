// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.run.SyntaxHighlight.Role;
import cc.jumpkick.cli.run.SyntaxHighlight.Rule;
import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-line colorizer for Java stack traces, with token rules ported from the <a
 * href="https://prismjs.com/">Prism.js</a> {@code javastacktrace} grammar.
 *
 * <p>The grammar's three top-level tokens — {@code summary} (the exception line), {@code
 * stack-frame} (an {@code at …} line) and {@code more} ({@code … 42 more}) — are all single-line
 * {@code /m} patterns, so this is a stateless per-line classifier: {@link #line(String)} recognizes
 * which of the three (if any) a line is and colorizes it, returning the input unchanged otherwise.
 * That lets it sit directly on a console listener's {@code output} path: every program/output line
 * is offered, and only genuine trace lines are painted.
 *
 * <p>Recognition is deliberately conservative — a bare word, a {@code "N tests failed:"} header, or
 * arbitrary program output must <em>not</em> be mistaken for an exception summary — so the summary
 * form requires a {@code Caused by:}/{@code Suppressed:}/{@code Exception in thread} prefix, or a
 * leading token that looks like a fully-qualified class name or an {@code …Exception}/ {@code
 * …Error}. As with {@link SyntaxHighlight}, only SGR escapes are inserted; stripping the ANSI
 * restores the line byte-for-byte.
 */
public final class StackTraceHighlight {

    private StackTraceHighlight() {}

    // --- stack-frame: at com.foo.Bar.method(Bar.java:42) -----------------

    /** Anchored detector for an {@code at …(…)} frame line (Prism's stack-frame pattern). */
    private static final Pattern FRAME =
            Pattern.compile("^[\\t ]*at (?:[\\w$./]|@[\\w$.+-]*/)+(?:<init>)?\\([^()]*\\)");

    private static final List<Rule> FRAME_RULES = List.of(
            // the leading 'at' command
            Rule.of("\\bat\\b(?=\\s)", Role.KEYWORD),
            // (Native Method) / (Unknown Source)
            Rule.of("(?:Native Method|Unknown Source)", Role.KEYWORD),
            // source file before ':line' — colored like a path
            Rule.of("[A-Za-z_$][\\w$]*\\.[A-Za-z][\\w]*(?=:\\d)", Role.PATH),
            // the declaring class (immediately before .method( or .<init>( )
            Rule.of("[\\w$]+(?=\\.(?:<init>|[\\w$]+)\\()", Role.TYPE),
            // the method (or constructor) name before '('
            Rule.of("(?:<init>|[\\w$]+)(?=\\()", Role.FUNCTION),
            // package qualifier (lower-case-led dotted segments)
            Rule.of("(?:[a-z][\\w$]*\\.)+", Role.NAMESPACE),
            // line number (and any other bare digits)
            Rule.of("\\d+", Role.NUMBER),
            Rule.of("[().:/@$]", Role.PUNCTUATION));

    // --- more: ... 42 more / ... 3 common frames omitted -----------------

    private static final Pattern MORE = Pattern.compile("^[\\t ]*\\.{3} \\d+ [a-z]+(?: [a-z]+)*\\s*$");

    private static final List<Rule> MORE_RULES =
            List.of(Rule.of("\\.{3}", Role.PUNCTUATION), Rule.of("\\d+", Role.NUMBER), Rule.of("[a-z]+", Role.KEYWORD));

    // --- summary: [Caused by: ]com.foo.MyException: message --------------

    private static final Pattern SUMMARY = Pattern.compile("^(?<indent>[\\t ]*)"
            + "(?<prefix>Caused by:|Suppressed:|Exception in thread \"[^\"]*\")?"
            + "(?<sp>[\\t ]*)"
            + "(?<fqcn>[\\w$]+(?:\\.[\\w$]+)*)"
            + "(?<rest>:[\\s\\S]*)?$");

    /** A class token worth treating as an exception even without a prefix or package. */
    private static final Pattern EXCEPTION_NAME = Pattern.compile("[A-Z][\\w$]*(?:Exception|Error|Throwable)");

    /**
     * Colorize {@code raw} if it is a stack-trace line (frame, summary, or "{@code … N more}");
     * otherwise return it unchanged.
     */
    public static String line(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        Matcher frame = FRAME.matcher(raw);
        if (frame.lookingAt()) return SyntaxHighlight.render(raw, -1, FRAME_RULES);

        if (MORE.matcher(raw).matches()) return SyntaxHighlight.render(raw, -1, MORE_RULES);

        Matcher s = SUMMARY.matcher(raw);
        if (s.matches() && isExceptionSummary(s)) return summary(s);

        return raw;
    }

    /**
     * A summary line only counts when it is clearly an exception, not arbitrary {@code word: text}.
     */
    private static boolean isExceptionSummary(Matcher s) {
        if (s.group("prefix") != null) return true;
        String fqcn = s.group("fqcn");
        return fqcn.indexOf('.') >= 0 || EXCEPTION_NAME.matcher(fqcn).matches();
    }

    private static String summary(Matcher s) {
        StringBuilder out = new StringBuilder();
        out.append(s.group("indent")); // plain
        String prefix = s.group("prefix");
        if (prefix != null) out.append(col(prefix, Role.KEYWORD));
        out.append(s.group("sp")); // plain
        String fqcn = s.group("fqcn");
        int dot = fqcn.lastIndexOf('.');
        if (dot >= 0) {
            out.append(col(fqcn.substring(0, dot + 1), Role.NAMESPACE)); // package incl. trailing '.'
            out.append(col(fqcn.substring(dot + 1), Role.TYPE)); // simple class name
        } else {
            out.append(col(fqcn, Role.TYPE));
        }
        String rest = s.group("rest");
        if (rest != null) {
            out.append(col(":", Role.PUNCTUATION));
            if (rest.length() > 1) out.append(col(rest.substring(1), Role.STRING)); // the message
        }
        return out.toString();
    }

    private static String col(String text, Role role) {
        return Theme.colorize(text, SyntaxHighlight.styleFor(role));
    }
}
