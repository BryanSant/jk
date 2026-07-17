// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonlTest {

    @Test
    void readsStringIntBoolFields() {
        String json = "{\"t\":\"diag\",\"sev\":\"ERROR\",\"count\":42,\"ok\":true}";
        assertThat(Jsonl.str(json, "t")).isEqualTo("diag");
        assertThat(Jsonl.str(json, "sev")).isEqualTo("ERROR");
        assertThat(Jsonl.intValue(json, "count", -1)).isEqualTo(42);
        assertThat(Jsonl.bool(json, "ok", false)).isTrue();
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        String json = "{\"t\":\"result\"}";
        assertThat(Jsonl.str(json, "absent")).isNull();
        assertThat(Jsonl.intValue(json, "absent", 7)).isEqualTo(7);
        assertThat(Jsonl.bool(json, "absent", true)).isTrue();
        assertThat(Jsonl.has(json, "t")).isTrue();
        assertThat(Jsonl.has(json, "absent")).isFalse();
        assertThat(Jsonl.strArray(json, "absent")).isEmpty();
    }

    @Test
    void readsNegativeIntegers() {
        assertThat(Jsonl.intValue("{\"d\":-15}", "d", 0)).isEqualTo(-15);
        assertThat(Jsonl.intValue("{\"d\":-}", "d", 99)).isEqualTo(99);
    }

    @Test
    void unescapesStringEscapeSequences() {
        String json = "{\"msg\":\"line1\\nline2\\ttab \\\"q\\\" \\\\slash\"}";
        assertThat(Jsonl.str(json, "msg")).isEqualTo("line1\nline2\ttab \"q\" \\slash");
    }

    @Test
    void readsStringArrays() {
        String json = "{\"src\":[\"a.java\",\"b.java\",\"c.java\"]}";
        assertThat(Jsonl.strArray(json, "src")).containsExactly("a.java", "b.java", "c.java");
        assertThat(Jsonl.strArray("{\"src\":[]}", "src")).isEmpty();
    }

    @Test
    void stringArrayElementsMayContainClosingBrackets() {
        // Regression: a value that itself contains ']' (a scaffolded jk.toml carries TOML tables
        // like "[project]") must not truncate the array at that inner bracket.
        String json = "{\"contents\":[\"[project]\\nname = 1\",\"x\"]}";
        assertThat(Jsonl.strArray(json, "contents")).containsExactly("[project]\nname = 1", "x");
    }

    @Test
    void stringArrayRoundTripsMultilineBracketedValues() {
        // The exact shape jk new --spring sends: multi-line TOML with [tables], quotes, and tabs,
        // packed by quote() into an array and read back by strArray().
        String toml = "[project]\nname = \"demo\"\n\n[spring-boot]\nversion = \"4.1.0\"\n\tindented";
        String array = "[" + Jsonl.quote(toml) + "," + Jsonl.quote("second") + "]";
        assertThat(Jsonl.strArray("{\"paramValues\":" + array + "}", "paramValues"))
                .containsExactly(toml, "second");
    }

    @Test
    void extractsNestedObjects() {
        String json = "{\"id\":\"1\",\"throwable\":{\"class\":\"E\",\"message\":\"boom {x}\"}}";
        String nested = Jsonl.nested(json, "throwable");
        assertThat(nested).isEqualTo("{\"class\":\"E\",\"message\":\"boom {x}\"}");
        assertThat(Jsonl.str(nested, "class")).isEqualTo("E");
        assertThat(Jsonl.str(nested, "message")).isEqualTo("boom {x}");
    }

    @Test
    void quoteEscapesAndRoundTripsThroughStr() {
        String raw = "he said \"hi\"\n\tand \\ left";
        String quoted = Jsonl.quote(raw);
        assertThat(quoted).startsWith("\"").endsWith("\"");
        // Embed the quoted literal as a field value and read it back out.
        assertThat(Jsonl.str("{\"v\":" + quoted + "}", "v")).isEqualTo(raw);
    }

    @Test
    void quoteEscapesControlCharsAsUnicode() {
        assertThat(Jsonl.quote("\u0001")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void quoteEncodesNullAsBareJsonNull() {
        assertThat(Jsonl.quote(null)).isEqualTo("null");
        // "field":null is read back as an absent string by str().
        assertThat(Jsonl.str("{\"e\":" + Jsonl.quote(null) + "}", "e")).isNull();
    }
}
