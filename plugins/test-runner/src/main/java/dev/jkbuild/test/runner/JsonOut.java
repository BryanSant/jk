// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON encoder. Tiny and dep-free so jk-test-runner stays a
 * single-jar artifact; we don't drag jackson/gson into every user's test
 * classpath. Covers exactly the value types our event schema uses: String,
 * Number, Boolean, Map&lt;String,?&gt;, List&lt;?&gt;, and {@code null}.
 *
 * <p>Output matches the JSON spec for these shapes — verifiable by feeding
 * the output through any standard JSON parser.
 */
final class JsonOut {

    private JsonOut() {}

    /** Append {@code value} to {@code out} as a JSON literal. */
    static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence cs) {
            writeString(out, cs);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else if (value instanceof Iterable<?> it) {
            writeArray(out, it);
        } else {
            // Defensive: stringify anything else (e.g. enums, paths) — keeps
            // the encoder total even if a future event payload sneaks in an
            // unexpected type.
            writeString(out, value.toString());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(out, String.valueOf(e.getKey()));
            out.append(':');
            write(out, e.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> it) {
        out.append('[');
        boolean first = true;
        for (var v : it) {
            if (!first) out.append(',');
            first = false;
            write(out, v);
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, CharSequence cs) {
        // Shared codec: surrounding quotes + escaping (backspace/form-feed
        // normalise to unicode escapes rather than their own short forms).
        out.append(Ndjson.quote(cs.toString()));
    }

    /** Convenience for callers that want a finished String for a small object. */
    static String string(Object value) {
        var sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    /** Convenience for callers that want a finished String for a key/value map. */
    static String object(Map<String, Object> map) {
        return string(map);
    }

    /** Allow tests to use the List shorthand without depending on Map.of size limits. */
    static List<Object> list(Object... items) {
        return List.of(items);
    }
}
