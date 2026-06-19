// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cli.theme.Theme;

import org.jline.utils.AttributedStyle;

import java.util.Set;

/**
 * Single-line Java/Kotlin syntax highlighter for compiler-diagnostic source
 * snippets. Tokenizes one line, colorizes each token via the active
 * {@link Theme}, and optionally underlines the single character a caret points
 * at — keeping that character's syntax color and layering the underline on top,
 * all in one pass.
 *
 * <p>It only ever inserts SGR escapes — never adds or removes characters — so
 * stripping the ANSI yields the original source line byte-for-byte. The line is
 * a fragment (not a full compilation unit), so the lexer degrades gracefully:
 * an unterminated string or block comment colors to end-of-line, and anything
 * unrecognized falls back to plain text.
 */
public final class SyntaxHighlight {

    private SyntaxHighlight() {}

    private enum Kind { PLAIN, KEYWORD, TYPE, FUNCTION, CONSTANT, STRING, NUMBER, COMMENT, ANNOTATION }

    /**
     * Union of Java and Kotlin reserved words. The console layer doesn't know
     * which language produced the line, but the overlap is harmless — a Kotlin
     * {@code fun} never appears in Java source and vice versa, so highlighting
     * both vocabularies on either language only ever helps.
     */
    private static final Set<String> KEYWORDS = Set.of(
            // Java
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "var", "yield",
            "record", "sealed", "permits", "true", "false", "null",
            // Kotlin (additions beyond the Java set)
            "fun", "val", "when", "is", "in", "object", "typealias", "companion",
            "init", "constructor", "internal", "open", "override", "data",
            "suspend", "lateinit", "by", "where", "out", "reified", "inline",
            "crossinline", "noinline", "vararg", "tailrec", "operator", "infix",
            "external", "annotation");

    /**
     * Highlight {@code src}, underlining the character at {@code caretCol}. A
     * {@code caretCol} outside the line (negative, or past the end — a
     * misaligned caret) simply highlights without an underline.
     */
    public static String highlight(String src, int caretCol) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            int start = i;
            char c = src.charAt(i);
            Kind kind;
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = n;                                       // line comment runs to EOL
                kind = Kind.COMMENT;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) i++;
                if (i < n) i += 2;                           // include the closing */ when present
                kind = Kind.COMMENT;
            } else if (c == '"' || c == '\'') {
                i = scanQuoted(src, i, c);
                kind = Kind.STRING;
            } else if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(src.charAt(i + 1))) {
                i++;
                while (i < n && (Character.isJavaIdentifierPart(src.charAt(i)) || src.charAt(i) == '.')) i++;
                kind = Kind.ANNOTATION;
            } else if (Character.isDigit(c)) {
                i++;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_'
                        || (src.charAt(i) == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))))) i++;
                kind = Kind.NUMBER;
            } else if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) i++;
                kind = classifyWord(src.substring(start, i), src, i);
            } else {
                i++;                                         // a run of punctuation / whitespace
                while (i < n && isPlainRun(src, i)) i++;
                kind = Kind.PLAIN;
            }
            emit(out, src, start, i, kind, caretCol);
        }
        return out.toString();
    }

    /**
     * Classify an identifier word the way GitHub's lexical grammar does, with no
     * AST to lean on:
     * <ul>
     *   <li>a reserved word → {@link Kind#KEYWORD};</li>
     *   <li>ALL_CAPS (e.g. {@code MAX_VALUE}, {@code THROWABLE}) → {@link Kind#CONSTANT};</li>
     *   <li>Capitalized (e.g. {@code String}, {@code ParseException}) → {@link Kind#TYPE}
     *       — this also catches constructor calls like {@code new Foo()};</li>
     *   <li>otherwise, an identifier immediately followed by {@code (} → {@link Kind#FUNCTION};</li>
     *   <li>anything else (a plain variable) → {@link Kind#PLAIN}.</li>
     * </ul>
     */
    private static Kind classifyWord(String w, String src, int end) {
        if (KEYWORDS.contains(w)) return Kind.KEYWORD;
        if (isAllCaps(w)) return Kind.CONSTANT;
        if (Character.isUpperCase(w.charAt(0))) return Kind.TYPE;
        if (end < src.length() && src.charAt(end) == '(') return Kind.FUNCTION;
        return Kind.PLAIN;
    }

    /** True for a multi-char identifier with at least one letter and no lowercase letter. */
    private static boolean isAllCaps(String w) {
        if (w.length() < 2) return false;
        boolean hasLetter = false;
        for (int k = 0; k < w.length(); k++) {
            char c = w.charAt(k);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetter;
    }

    /** Scan a quoted run from the opening quote at {@code i} to its close (or EOL). */
    private static int scanQuoted(String src, int i, char quote) {
        int n = src.length();
        i++;                                                 // opening quote
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n) {                    // escape — skip the next char
                i += 2;
                continue;
            }
            i++;
            if (c == quote) break;                           // closing quote consumed
        }
        return i;
    }

    /** True while {@code src[i]} cannot start a distinct token (so it joins the plain run). */
    private static boolean isPlainRun(String src, int i) {
        char c = src.charAt(i);
        if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) return false;
        if (c == '"' || c == '\'' || c == '@') return false;
        if (c == '/' && i + 1 < src.length()
                && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) return false;
        return true;
    }

    /**
     * Emit {@code src[s, e)} colored for {@code kind}. If the caret falls inside
     * the token, the run is split so the caret character keeps the token color
     * and gains an underline, while its neighbours stay plainly colored.
     */
    private static void emit(StringBuilder out, String src, int s, int e, Kind kind, int caretCol) {
        AttributedStyle style = styleFor(kind);
        if (caretCol < s || caretCol >= e) {
            out.append(Theme.colorize(src.substring(s, e), style));
            return;
        }
        if (caretCol > s) out.append(Theme.colorize(src.substring(s, caretCol), style));
        out.append(Theme.colorize(String.valueOf(src.charAt(caretCol)), style.underline()));
        if (caretCol + 1 < e) out.append(Theme.colorize(src.substring(caretCol + 1, e), style));
    }

    private static AttributedStyle styleFor(Kind kind) {
        Theme t = Theme.active();
        return switch (kind) {
            case KEYWORD    -> t.synKeyword();
            case TYPE       -> t.synType();
            case FUNCTION   -> t.synFunction();
            case CONSTANT   -> t.synConstant();
            case STRING     -> t.synString();
            case NUMBER     -> t.synNumber();
            case COMMENT    -> t.synComment();
            case ANNOTATION -> t.synAnnotation();
            case PLAIN      -> AttributedStyle.DEFAULT;
        };
    }
}
