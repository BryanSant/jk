// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine.http;

import dev.jkbuild.plugin.protocol.Ndjson;

/**
 * A tiny writer for the flat JSON objects the REST surface emits — the same deliberate
 * flat-scalar-fields discipline as the engine wire protocol ({@code EngineProtocol}/{@link
 * Ndjson}), and the same dependency-free reasoning: no nesting means no JSON library on either
 * side. Escaping is {@link Ndjson#quote} so a string means the same thing on every jk wire.
 * Public (not package-private) because {@code EngineServer} builds {@link HttpEvents} payloads.
 */
public final class JsonOut {

    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    public static JsonOut object() {
        return new JsonOut();
    }

    public JsonOut put(String key, String value) {
        return raw(key, value != null ? Ndjson.quote(value) : "null");
    }

    public JsonOut put(String key, long value) {
        return raw(key, Long.toString(value));
    }

    public JsonOut put(String key, boolean value) {
        return raw(key, Boolean.toString(value));
    }

    /** A flat array of strings — the one non-scalar shape the jk wire discipline allows. */
    public JsonOut putStrings(String key, java.util.List<String> values) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) arr.append(',');
            arr.append(Ndjson.quote(values.get(i)));
        }
        return raw(key, arr.append(']').toString());
    }

    private JsonOut raw(String key, String rendered) {
        if (!first) sb.append(',');
        first = false;
        sb.append(Ndjson.quote(key)).append(':').append(rendered);
        return this;
    }

    /** Render the object. The builder is single-use; don't append after this. */
    @Override
    public String toString() {
        return sb.toString() + "}";
    }
}
