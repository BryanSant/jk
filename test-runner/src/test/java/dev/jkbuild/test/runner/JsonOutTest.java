// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.test.runner;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonOutTest {

    @Test
    void primitives_roundtrip() {
        assertEquals("null", JsonOut.string(null));
        assertEquals("\"hi\"", JsonOut.string("hi"));
        assertEquals("42", JsonOut.string(42));
        assertEquals("3.14", JsonOut.string(3.14));
        assertEquals("true", JsonOut.string(true));
    }

    @Test
    void strings_escape_quotes_and_backslashes() {
        assertEquals("\"it\\\\'s \\\"that\\\"\"", JsonOut.string("it\\'s \"that\""));
    }

    @Test
    void strings_escape_control_chars() {
        assertEquals("\"a\\nb\\tc\\u0001d\"", JsonOut.string("a\nb\tcd"));
    }

    @Test
    void objects_preserve_insertion_order_and_quote_keys() {
        var m = new LinkedHashMap<String, Object>();
        m.put("e", "started");
        m.put("id", "x");
        assertEquals("{\"e\":\"started\",\"id\":\"x\"}", JsonOut.object(m));
    }

    @Test
    void arrays_render_inline() {
        assertEquals("[1,2,3]", JsonOut.string(List.of(1, 2, 3)));
    }

    @Test
    void nested_object_inside_array() {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("name", "foo");
        assertEquals("[{\"name\":\"foo\"}]", JsonOut.string(List.of(inner)));
    }

    @Test
    void enum_value_falls_back_to_string() {
        // EventType is rendered via its toString() — not its wire() form by
        // default, so callers should use wire() explicitly. The encoder being
        // total over arbitrary objects is what matters here.
        assertEquals("\"PLAN_STARTED\"", JsonOut.string(EventType.PLAN_STARTED));
    }
}
