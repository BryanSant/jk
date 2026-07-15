// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NdjsonTest {

    @Test
    void readsStringIntBoolFields() {
        String json = "{\"t\":\"diag\",\"sev\":\"ERROR\",\"count\":42,\"ok\":true}";
        assertThat(Ndjson.str(json, "t")).isEqualTo("diag");
        assertThat(Ndjson.str(json, "sev")).isEqualTo("ERROR");
        assertThat(Ndjson.intValue(json, "count", -1)).isEqualTo(42);
        assertThat(Ndjson.bool(json, "ok", false)).isTrue();
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        String json = "{\"t\":\"result\"}";
        assertThat(Ndjson.str(json, "absent")).isNull();
        assertThat(Ndjson.intValue(json, "absent", 7)).isEqualTo(7);
        assertThat(Ndjson.bool(json, "absent", true)).isTrue();
        assertThat(Ndjson.has(json, "t")).isTrue();
        assertThat(Ndjson.has(json, "absent")).isFalse();
        assertThat(Ndjson.strArray(json, "absent")).isEmpty();
    }

    @Test
    void readsNegativeIntegers() {
        assertThat(Ndjson.intValue("{\"d\":-15}", "d", 0)).isEqualTo(-15);
        assertThat(Ndjson.intValue("{\"d\":-}", "d", 99)).isEqualTo(99);
    }

    @Test
    void unescapesStringEscapeSequences() {
        String json = "{\"msg\":\"line1\\nline2\\ttab \\\"q\\\" \\\\slash\"}";
        assertThat(Ndjson.str(json, "msg")).isEqualTo("line1\nline2\ttab \"q\" \\slash");
    }

    @Test
    void readsStringArrays() {
        String json = "{\"src\":[\"a.java\",\"b.java\",\"c.java\"]}";
        assertThat(Ndjson.strArray(json, "src")).containsExactly("a.java", "b.java", "c.java");
        assertThat(Ndjson.strArray("{\"src\":[]}", "src")).isEmpty();
    }

    @Test
    void stringArrayElementsMayContainClosingBrackets() {
        // Regression: a value that itself contains ']' (a scaffolded jk.toml carries TOML tables
        // like "[project]") must not truncate the array at that inner bracket.
        String json = "{\"contents\":[\"[project]\\nname = 1\",\"x\"]}";
        assertThat(Ndjson.strArray(json, "contents")).containsExactly("[project]\nname = 1", "x");
    }

    @Test
    void stringArrayRoundTripsMultilineBracketedValues() {
        // The exact shape jk new --spring sends: multi-line TOML with [tables], quotes, and tabs,
        // packed by quote() into an array and read back by strArray().
        String toml = "[project]\nname = \"demo\"\n\n[spring-boot]\nversion = \"4.1.0\"\n\tindented";
        String array = "[" + Ndjson.quote(toml) + "," + Ndjson.quote("second") + "]";
        assertThat(Ndjson.strArray("{\"paramValues\":" + array + "}", "paramValues"))
                .containsExactly(toml, "second");
    }

    @Test
    void extractsNestedObjects() {
        String json = "{\"id\":\"1\",\"throwable\":{\"class\":\"E\",\"message\":\"boom {x}\"}}";
        String nested = Ndjson.nested(json, "throwable");
        assertThat(nested).isEqualTo("{\"class\":\"E\",\"message\":\"boom {x}\"}");
        assertThat(Ndjson.str(nested, "class")).isEqualTo("E");
        assertThat(Ndjson.str(nested, "message")).isEqualTo("boom {x}");
    }

    @Test
    void quoteEscapesAndRoundTripsThroughStr() {
        String raw = "he said \"hi\"\n\tand \\ left";
        String quoted = Ndjson.quote(raw);
        assertThat(quoted).startsWith("\"").endsWith("\"");
        // Embed the quoted literal as a field value and read it back out.
        assertThat(Ndjson.str("{\"v\":" + quoted + "}", "v")).isEqualTo(raw);
    }

    @Test
    void quoteEscapesControlCharsAsUnicode() {
        assertThat(Ndjson.quote("\u0001")).isEqualTo("\"\\u0001\"");
    }

    @Test
    void quoteEncodesNullAsBareJsonNull() {
        assertThat(Ndjson.quote(null)).isEqualTo("null");
        // "field":null is read back as an absent string by str().
        assertThat(Ndjson.str("{\"e\":" + Ndjson.quote(null) + "}", "e")).isNull();
    }
}
