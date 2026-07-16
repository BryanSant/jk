// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's single JSON home — the engine deliberately ships no JSON library, and its JSON
 * needs are small config-shaped documents (reachability-metadata indexes, the build journal,
 * HTTP responses). Two halves:
 *
 * <ul>
 *   <li>{@link #parse}: full-grammar recursive descent into {@code Map<String,Object> /
 *       List<Object> / String / Double / Boolean / null}.
 *   <li>{@link #write}: the inverse for the same object model (plus integral {@link Number}s
 *       written without a decimal point), compact or pretty. String escaping delegates to {@link
 *       cc.jumpkick.plugin.protocol.Ndjson#quote} — the wire contract in {@code plugin-api} owns
 *       the one escaping implementation, so a string means the same thing on every jk surface.
 * </ul>
 *
 * <p>Not streaming, not tuned — do not use for large documents. If jk ever needs streaming or
 * data-binding, that is the moment to adopt a real library (engine-side only), not to grow this.
 */
public final class MiniJson {

    /** Serialize {@code value} (Map/List/String/Number/Boolean/null) as compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1);
        return sb.toString();
    }

    /** As {@link #write(Object)}, pretty-printed with 2-space indentation. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    /** {@code indent < 0} = compact; otherwise the current pretty-print depth. */
    private static void writeValue(StringBuilder sb, Object value, int indent) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> sb.append(cc.jumpkick.plugin.protocol.Ndjson.quote(s));
            case Boolean b -> sb.append(b);
            case Double d -> {
                // Integral doubles (the parser's number type) print without the ".0" so
                // parse→write round-trips don't reformat whole numbers.
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.007199254740992E15) {
                    sb.append((long) (double) d);
                } else {
                    sb.append(d);
                }
            }
            case Float f -> writeValue(sb, f.doubleValue(), indent);
            case Number n -> sb.append(n); // integral types print naturally
            case Map<?, ?> map -> writeObject(sb, map, indent);
            case List<?> list -> writeArray(sb, list, indent);
            default -> throw new IllegalArgumentException(
                    "not JSON-representable: " + value.getClass().getName());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newlineIndent(sb, indent < 0 ? -1 : indent + 1);
            sb.append(cc.jumpkick.plugin.protocol.Ndjson.quote(String.valueOf(e.getKey())))
                    .append(':');
            if (indent >= 0) sb.append(' ');
            writeValue(sb, e.getValue(), indent < 0 ? -1 : indent + 1);
        }
        newlineIndent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            newlineIndent(sb, indent < 0 ? -1 : indent + 1);
            writeValue(sb, item, indent < 0 ? -1 : indent + 1);
        }
        newlineIndent(sb, indent);
        sb.append(']');
    }

    private static void newlineIndent(StringBuilder sb, int indent) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append("  ".repeat(indent));
    }

    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Parse a complete JSON document; trailing non-whitespace is an error. */
    public static Object parse(String json) {
        MiniJson p = new MiniJson(json);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos != json.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            map.put(key, parseValue());
            skipWhitespace();
            char c = next();
            if (c == '}') return map;
            if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at offset " + (pos - 1));
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at offset " + (pos - 1));
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw new IllegalArgumentException("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> throw new IllegalArgumentException("bad escape \\" + esc + " at offset " + (pos - 1));
            }
        }
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("bad literal at offset " + pos);
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("bad literal at offset " + pos);
    }

    private Double parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
        if (pos == start) throw new IllegalArgumentException("unexpected character at offset " + pos);
        return Double.parseDouble(src.substring(start, pos));
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private char peek() {
        if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of input");
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("expected '" + c + "' at offset " + (pos - 1));
    }
}
