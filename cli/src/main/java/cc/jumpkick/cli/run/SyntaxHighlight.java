// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

import cc.jumpkick.cli.theme.Theme;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.AttributedStyle;

/**
 * Single-line Java/Kotlin syntax highlighter for compiler-diagnostic source snippets. Tokenizes one
 * line, colorizes each token via the active {@link Theme}, and optionally underlines the single
 * character a caret points at — keeping that character's syntax color and layering the underline on
 * top, all in one pass.
 *
 * <p>The token <em>rules</em> are ported from the <a href="https://prismjs.com/">Prism.js</a>
 * grammars for {@code java} and {@code kotlin} (their {@code clike} base folded in). Rather than
 * reproduce Prism's full nested tokenizer, each grammar is flattened into an ordered,
 * first-match-wins list of {@link Rule}s: at every cursor position the rules are tried in order and
 * the first that matches at the cursor wins. This keeps the lexer small while reading like Prism's
 * output. The same engine and role→style mapping are reused by {@link StackTraceHighlight}.
 *
 * <p>It only ever inserts SGR escapes — never adds or removes characters — so stripping the ANSI
 * yields the original source line byte-for-byte. The line is a fragment (not a full compilation
 * unit), so the lexer degrades gracefully: an unterminated string or block comment colors to
 * end-of-line, and anything unrecognized falls back to plain text.
 */
public final class SyntaxHighlight {

    private SyntaxHighlight() {}

    /** Which Prism grammar to colorize with. */
    public enum Language {
        JAVA,
        KOTLIN
    }

    /** A colorable token class; maps to a {@link Theme} style via {@link #styleFor}. */
    enum Role {
        KEYWORD,
        TYPE,
        FUNCTION,
        CONSTANT,
        STRING,
        NUMBER,
        COMMENT,
        ANNOTATION,
        NAMESPACE,
        PUNCTUATION,
        PATH,
        PLAIN
    }

    /** One ordered grammar rule: a regex anchored at the cursor and the role it paints. */
    record Rule(Pattern pattern, Role role) {
        static Rule of(String regex, Role role) {
            return new Rule(Pattern.compile(regex), role);
        }

        static Rule of(String regex, int flags, Role role) {
            return new Rule(Pattern.compile(regex, flags), role);
        }
    }

    // --- shared (clike) fragments -----------------------------------------

    /** Closed block/JavaDoc comment, then line comment, then an unterminated block to EOL. */
    private static final Rule BLOCK_COMMENT = Rule.of("/\\*\\*?[\\s\\S]*?\\*/", Role.COMMENT);

    /** A line comment, but not the {@code //} inside a URL ({@code http://}) or escaped. */
    private static final Rule LINE_COMMENT = Rule.of("(?<![:\\\\])//.*", Role.COMMENT);

    private static final Rule OPEN_COMMENT = Rule.of("/\\*[\\s\\S]*", Role.COMMENT);

    private static final Rule PUNCTUATION = Rule.of("[{}\\[\\]();,.:]", Role.PUNCTUATION);

    // --- Java grammar (java.js + clike.js base) ---------------------------

    private static final List<Rule> JAVA_RULES = List.of(
            BLOCK_COMMENT,
            LINE_COMMENT,
            OPEN_COMMENT,
            // char literal
            Rule.of("'(?:\\\\.|[^'\\\\\\r\\n]){1,6}'", Role.STRING),
            // text block, then a closed string, then an unterminated string to EOL
            Rule.of("\"\"\"[\\s\\S]*?\"\"\"", Role.STRING),
            Rule.of("\"(?:\\\\.|[^\"\\\\\\r\\n])*\"", Role.STRING),
            Rule.of("\"(?:\\\\.|[^\"\\\\\\r\\n])*", Role.STRING),
            // annotation use (@Test, @foo.Bar) — not a field access like x@y is invalid anyway
            Rule.of("(?<!\\.)@[A-Za-z_]\\w*(?:\\s*\\.\\s*\\w+)*", Role.ANNOTATION),
            // number: binary, hex (with optional fraction/exponent), decimal/float — case-insensitive
            Rule.of(
                    "\\b0b[01][01_]*l?\\b"
                            + "|\\b0x(?:\\.[\\da-f_p+-]+|[\\da-f_]+(?:\\.[\\da-f_p+-]+)?)\\b"
                            + "|(?:\\b\\d[\\d_]*(?:\\.[\\d_]*)?|\\B\\.\\d[\\d_]*)(?:e[+-]?\\d[\\d_]*)?[dfl]?",
                    Pattern.CASE_INSENSITIVE,
                    Role.NUMBER),
            // keywords (incl. boolean/null literals, folded in for one color)
            Rule.of(
                    "\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|const"
                            + "|continue|default|do|double|else|enum|exports|extends|final|finally|float"
                            + "|for|goto|if|implements|import|instanceof|int|interface|long|module|native"
                            + "|new|non-sealed|null|open|opens|package|permits|private|protected|provides"
                            + "|public|record|requires|return|sealed|short|static|strictfp|super|switch"
                            + "|synchronized|this|throw|throws|to|transient|transitive|try|uses|var|void"
                            + "|volatile|while|with|yield|true|false)\\b",
                    Role.KEYWORD),
            // ALL_CAPS constant — must precede the type rule
            Rule.of("\\b[A-Z][A-Z_\\d]+\\b", Role.CONSTANT),
            // class name / type: any Capitalized identifier (also catches constructors)
            Rule.of("\\b[A-Z]\\w*\\b", Role.TYPE),
            // function: a (lower-case-led) identifier immediately before '('
            Rule.of("\\b[A-Za-z_]\\w*(?=\\s*\\()", Role.FUNCTION),
            PUNCTUATION);

    // --- Kotlin grammar (kotlin.js + clike.js base) -----------------------
    // Prism's Kotlin grammar deletes clike's `class-name`, so types are NOT
    // colored — keyword/function/number/annotation/string carry the highlight.

    private static final List<Rule> KOTLIN_RULES = List.of(
            BLOCK_COMMENT,
            LINE_COMMENT,
            OPEN_COMMENT,
            // text block, char, closed string, unterminated string
            Rule.of("\"\"\"[\\s\\S]*?\"\"\"", Role.STRING),
            Rule.of("'(?:[^'\\\\\\r\\n]|\\\\(?:.|u[a-fA-F0-9]{0,4}))'", Role.STRING),
            Rule.of("\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"", Role.STRING),
            Rule.of("\"(?:[^\"\\\\\\r\\n]|\\\\.)*", Role.STRING),
            // annotation: @Foo, @field:Foo, @[Foo Bar]
            Rule.of("(?<!\\.)@(?:\\w+:)?(?:[A-Z]\\w*|\\[[^\\]]+\\])", Role.ANNOTATION),
            // number
            Rule.of(
                    "\\b(?:0[xX][\\da-fA-F]+(?:_[\\da-fA-F]+)*"
                            + "|0[bB][01]+(?:_[01]+)*"
                            + "|\\d+(?:_\\d+)*(?:\\.\\d+(?:_\\d+)*)?(?:[eE][+-]?\\d+(?:_\\d+)*)?[fFL]?)\\b",
                    Role.NUMBER),
            // boolean literals (inherited from clike)
            Rule.of("\\b(?:true|false)\\b", Role.KEYWORD),
            // keywords — the lookbehind prevents coloring member access like kotlin.properties.get
            Rule.of(
                    "(?<!\\.)\\b(?:abstract|actual|annotation|as|break|by|catch|class|companion"
                            + "|const|constructor|continue|crossinline|data|do|dynamic|else|enum|expect"
                            + "|external|final|finally|for|fun|get|if|import|in|infix|init|inline|inner"
                            + "|interface|internal|is|lateinit|noinline|null|object|open|operator|out"
                            + "|override|package|private|protected|public|reified|return|sealed|set|super"
                            + "|suspend|tailrec|this|throw|to|try|typealias|val|var|vararg|when|where"
                            + "|while)\\b",
                    Role.KEYWORD),
            // label: loop@ / this@Foo
            Rule.of("\\b\\w+@", Role.FUNCTION),
            // function: name (or `back-ticked name`) before '('
            Rule.of("(?:`[^`\\r\\n]+`|\\b[A-Za-z_]\\w*)(?=\\s*\\()", Role.FUNCTION),
            PUNCTUATION);

    /**
     * Highlight {@code src} as Java, underlining the character at {@code caretCol}. Convenience
     * overload for the common case.
     */
    public static String highlight(String src, int caretCol) {
        return highlight(src, caretCol, Language.JAVA);
    }

    /**
     * Highlight {@code src} in {@code lang}, underlining the character at {@code caretCol}. A {@code
     * caretCol} outside the line (negative, or past the end — a misaligned caret) simply highlights
     * without an underline.
     */
    public static String highlight(String src, int caretCol, Language lang) {
        return render(src, caretCol, lang == Language.KOTLIN ? KOTLIN_RULES : JAVA_RULES);
    }

    /**
     * The ordered-rule engine. Walks the cursor left-to-right; at each position the first {@code
     * rules} entry that matches (a non-empty match anchored at the cursor) wins and its span is
     * emitted in that role's style. When no rule matches, a single plain character is emitted — this
     * guarantees forward progress and that stripping the ANSI restores the input byte-for-byte.
     */
    static String render(String src, int caretCol, List<Rule> rules) {
        StringBuilder out = new StringBuilder();
        int n = src.length();
        Matcher[] matchers = new Matcher[rules.size()];
        for (int r = 0; r < rules.size(); r++) {
            matchers[r] = rules.get(r).pattern().matcher(src);
        }
        int i = 0;
        while (i < n) {
            int matchEnd = -1;
            Role role = Role.PLAIN;
            for (int r = 0; r < rules.size(); r++) {
                Matcher m = matchers[r];
                m.region(i, n);
                // Transparent bounds so \b and look-behinds see the real neighbours
                // outside [i, n); non-anchoring so ^/$ don't fire mid-line.
                m.useTransparentBounds(true);
                m.useAnchoringBounds(false);
                if (m.lookingAt() && m.end() > i) {
                    matchEnd = m.end();
                    role = rules.get(r).role();
                    break;
                }
            }
            if (matchEnd < 0) {
                emit(out, src, i, i + 1, Role.PLAIN, caretCol);
                i++;
            } else {
                emit(out, src, i, matchEnd, role, caretCol);
                i = matchEnd;
            }
        }
        return out.toString();
    }

    /**
     * Emit {@code src[s, e)} colored for {@code role}. If the caret falls inside the token, the run
     * is split so the caret character keeps the token color and gains an underline, while its
     * neighbours stay plainly colored.
     */
    private static void emit(StringBuilder out, String src, int s, int e, Role role, int caretCol) {
        AttributedStyle style = styleFor(role);
        if (caretCol < s || caretCol >= e) {
            out.append(Theme.colorize(src.substring(s, e), style));
            return;
        }
        if (caretCol > s) out.append(Theme.colorize(src.substring(s, caretCol), style));
        out.append(Theme.colorize(String.valueOf(src.charAt(caretCol)), style.underline()));
        if (caretCol + 1 < e) out.append(Theme.colorize(src.substring(caretCol + 1, e), style));
    }

    /** Map a token {@link Role} onto the active theme's style. */
    static AttributedStyle styleFor(Role role) {
        Theme t = Theme.active();
        return switch (role) {
            case KEYWORD -> t.synKeyword();
            case TYPE -> t.synType();
            case FUNCTION -> t.synFunction();
            case CONSTANT -> t.synConstant();
            case STRING -> t.synString();
            case NUMBER -> t.synNumber();
            case COMMENT -> t.synComment();
            case ANNOTATION -> t.synAnnotation();
            case NAMESPACE -> t.synNamespace();
            case PUNCTUATION -> t.synPunctuation();
            case PATH -> t.path();
            case PLAIN -> AttributedStyle.DEFAULT;
        };
    }
}
