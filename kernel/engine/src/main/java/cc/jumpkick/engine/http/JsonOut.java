// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.util.MiniJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent sugar for the flat JSON objects the REST surface emits — the same deliberate
 * flat-scalar-fields discipline as the engine wire protocol ({@code EngineProtocol}). Rendering
 * (and therefore escaping) is {@link MiniJson}, the engine's single JSON home; this class only
 * contributes the builder ergonomics the HTTP handlers use in ~40 places. Public (not
 * package-private) because {@code EngineServer} builds {@link HttpEvents} payloads.
 */
public final class JsonOut {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    public static JsonOut object() {
        return new JsonOut();
    }

    public JsonOut put(String key, String value) {
        fields.put(key, value);
        return this;
    }

    public JsonOut put(String key, long value) {
        fields.put(key, value);
        return this;
    }

    public JsonOut put(String key, boolean value) {
        fields.put(key, value);
        return this;
    }

    /** A flat array of strings — the one non-scalar shape the jk wire discipline allows. */
    public JsonOut putStrings(String key, List<String> values) {
        fields.put(key, List.copyOf(values));
        return this;
    }

    /** Render the object (compact). */
    @Override
    public String toString() {
        return MiniJson.write(fields);
    }
}
