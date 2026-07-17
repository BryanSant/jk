// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The canonical codec for jk's host&lt;-&gt;plugin wire protocol: the simple flat JSON objects
 * plugins (today's "runner" workers) emit as JSONL ({@code ##PREFIX:{...}}).
 *
 * <p>Two halves, both dependency-free so the codec can be bundled into a tiny plugin jar without
 * dragging in Jackson or any jk internals:
 *
 * <ul>
 *   <li><b>Reader</b> — {@link #str}, {@link #intValue}, {@link #bool}, {@link #has}, {@link
 *       #strArray}, {@link #nested}: pull fields out of a single JSON object line (the parent side,
 *       after the prefix is stripped).
 *   <li><b>Writer</b> — {@link #quote}: escape a string into a JSON string literal (the plugin
 *       side, building a line to emit).
 * </ul>
 *
 * <p>The reader treats a missing or malformed field as the supplied default (or {@code null})
 * rather than throwing, matching the contract callers already relied on from Jackson's {@code
 * node.path("x").asString()}. It handles string, integer, boolean, string-array, and nested-object
 * fields; it does not parse nested arrays or non-string array elements.
 */
public final class Jsonl {

    private Jsonl() {}

    /**
     * Extract a JSON string field value, handling basic escape sequences ({@code \"}, {@code \\},
     * {@code \n}, {@code \r}, {@code \t}). Returns {@code null} when the key is absent.
     */
    public static String str(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Extract a JSON integer field, returning {@code defaultVal} when absent or non-numeric. */
    public static int intValue(String json, String key, int defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        boolean neg = end < json.length() && json.charAt(end) == '-';
        if (neg) end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start || (neg && end == start + 1)) return defaultVal;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON long field, returning {@code defaultVal} when absent or non-numeric. */
    public static long longValue(String json, String key, long defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        boolean neg = end < json.length() && json.charAt(end) == '-';
        if (neg) end++;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start || (neg && end == start + 1)) return defaultVal;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException ignored) {
            return defaultVal;
        }
    }

    /** Extract a JSON boolean field, returning {@code defaultVal} when absent. */
    public static boolean bool(String json, String key, boolean defaultVal) {
        if (json == null) return defaultVal;
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return defaultVal;
        start += needle.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (json.startsWith("true", start)) return true;
        if (json.startsWith("false", start)) return false;
        return defaultVal;
    }

    /** Returns {@code true} when the key is present with any non-null, non-"null" value. */
    public static boolean has(String json, String key) {
        if (json == null) return false;
        return json.contains("\"" + key + "\":");
    }

    /**
     * Extract a JSON string-array field ({@code "key":["a","b","c"]}). Returns an empty list when the
     * key is absent or the value is not an array. Does not handle nested arrays or non-string
     * elements.
     */
    public static List<String> strArray(String json, String key) {
        if (json == null) return Collections.emptyList();
        String needle = "\"" + key + "\":[";
        int start = json.indexOf(needle);
        if (start < 0) return Collections.emptyList();
        start += needle.length();
        // The array's closing ']' is the first one that isn't inside a quoted element — a naive
        // indexOf(']') truncates any value that itself contains ']' (e.g. TOML tables like
        // "[project]" carried as a scaffold param or generated-file content).
        int end = arrayEnd(json, start);
        if (end < 0) return Collections.emptyList();
        String content = json.substring(start, end).trim();
        if (content.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < content.length() && content.charAt(i) != '"') {
                    char c = content.charAt(i);
                    if (c == '\\' && i + 1 < content.length()) {
                        char n = content.charAt(++i);
                        switch (n) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                // Unicode escape (4 hex digits) — as emitted by quote() for control chars.
                                if (i + 4 < content.length()) {
                                    sb.append((char) Integer.parseInt(content.substring(i + 1, i + 5), 16));
                                    i += 4;
                                }
                            }
                            default -> {
                                sb.append('\\');
                                sb.append(n);
                            }
                        }
                    } else {
                        sb.append(c);
                    }
                    i++;
                }
                result.add(sb.toString());
                i++; // closing quote
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * Index of the {@code ']'} that closes the array beginning at {@code start} — the first one that
     * lies outside a quoted element (quotes and their {@code \\}-escapes are skipped), or {@code -1}
     * if unterminated. A plain {@code indexOf(']')} would stop at a {@code ']'} inside an element's
     * own text.
     */
    private static int arrayEnd(String json, int start) {
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++; // skip the escaped char (incl. an escaped quote)
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract the raw JSON for a nested object field ({@code "key":{...}}). Returns the {@code {...}}
     * string (suitable for passing back to other {@code Jsonl} methods), or {@code null} when
     * absent.
     */
    public static String nested(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":{";
        int start = json.indexOf(needle);
        if (start < 0) {
            // Also handle "key": { with a space
            needle = "\"" + key + "\": {";
            start = json.indexOf(needle);
            if (start < 0) return null;
        }
        // Walk forward to find the matching closing brace.
        int braceStart = json.indexOf('{', start + needle.length() - 1);
        if (braceStart < 0) return null;
        int depth = 1;
        int i = braceStart + 1;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '"') {
                // skip string to avoid counting braces inside strings
                i++;
                while (i < json.length() && json.charAt(i) != '"') {
                    if (json.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            i++;
        }
        return json.substring(braceStart, i);
    }

    /**
     * Escape a string into a JSON string literal, surrounding quotes included ({@code foo"bar} →
     * {@code "foo\"bar"}). Control characters below 0x20 are emitted as {@code \\uXXXX}. The inverse
     * of {@link #str} for the escape sequences both sides handle.
     *
     * <p>This is the writer half: a plugin building a protocol line uses it to encode arbitrary
     * string values (diagnostics, paths, messages). A {@code null} value encodes as the bare JSON
     * literal {@code null} (not a quoted string), so {@code "msg":} + {@code quote(maybeNull)} is
     * always valid JSON.
     */
    /**
     * Extract a flat string-to-string map field ({@code "key":{"a":"1","b":"2"}}). Returns an
     * empty (mutable-safe, insertion-ordered) map when absent or malformed. The ONE wire encoding
     * for maps — parallel name/value arrays are gone.
     */
    public static java.util.Map<String, String> strMap(String json, String key) {
        var out = new java.util.LinkedHashMap<String, String>();
        String obj = nested(json, key);
        if (obj == null) return out;
        // obj is "{...}" — scan "k":"v" pairs at depth 1.
        int i = 1;
        while (i < obj.length()) {
            if (obj.charAt(i) == '"') {
                int[] pos = {i};
                String k = readString(obj, pos);
                i = pos[0];
                while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == ':')) i++;
                if (i < obj.length() && obj.charAt(i) == '"') {
                    pos[0] = i;
                    String v = readString(obj, pos);
                    i = pos[0];
                    out.put(k, v);
                }
            } else {
                i++;
            }
        }
        return out;
    }

    /** Read a quoted string starting at {@code pos[0]} (on the opening quote); advances past it. */
    private static String readString(String s, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int i = pos[0] + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append('\\');
                        sb.append(n);
                    }
                }
            } else {
                sb.append(c);
            }
            i++;
        }
        pos[0] = i + 1;
        return sb.toString();
    }

    /**
     * Encode a flat string map as a JSON object ({@code {"a":"1"}}), keys in iteration order —
     * the writer half of {@link #strMap}. Null maps encode as {@code {}}.
     */
    public static String map(java.util.Map<String, String> m) {
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append('}').toString();
    }

    /** Encode a list of strings as a JSON array of quoted strings: {@code ["a","b"]}. */
    public static String array(List<String> values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) b.append(',');
            b.append(quote(values.get(i)));
        }
        return b.append(']').toString();
    }

    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
