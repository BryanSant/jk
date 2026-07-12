// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {

    @Test
    void parses_the_reachability_index_shape() {
        Object parsed = MiniJson.parse("""
                [
                  {
                    "latest" : true,
                    "metadata-version" : "42.7.3",
                    "tested-versions" : ["42.7.3", "42.7.4"],
                    "description" : "quote \\" and \\u00e9 and \\n"
                  },
                  { "metadata-version" : "42.3.4", "tested-versions" : [] }
                ]
                """);
        assertThat(parsed).isInstanceOf(List.class);
        List<?> entries = (List<?>) parsed;
        assertThat(entries).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) entries.get(0);
        assertThat(first.get("latest")).isEqualTo(Boolean.TRUE);
        assertThat(first.get("metadata-version")).isEqualTo("42.7.3");
        assertThat(first.get("tested-versions")).isEqualTo(List.of("42.7.3", "42.7.4"));
        assertThat(first.get("description")).isEqualTo("quote \" and é and \n");
    }

    @Test
    void parses_scalars_nesting_and_numbers() {
        assertThat(MiniJson.parse("{\"a\":{\"b\":[1,2.5,-3e2,null,false]}}"))
                .isEqualTo(Map.of("a", Map.of("b", java.util.Arrays.asList(1.0, 2.5, -300.0, null, false))));
        assertThat(MiniJson.parse("\"plain\"")).isEqualTo("plain");
        assertThat(MiniJson.parse(" true ")).isEqualTo(true);
    }

    @Test
    void write_round_trips_through_parse() {
        Map<String, Object> obj = new java.util.LinkedHashMap<>();
        obj.put("name", "quote \" slash \\ nl \n");
        obj.put("count", 42L);
        obj.put("ratio", 2.5);
        obj.put("on", true);
        obj.put("none", null);
        obj.put("items", List.of("a", "b"));
        String compact = MiniJson.write(obj);
        assertThat(compact).doesNotContain("\n");
        assertThat(MiniJson.parse(compact)).isEqualTo(MiniJson.parse(MiniJson.writePretty(obj)));
        // integral numbers survive a parse→write cycle without growing a ".0"
        assertThat(MiniJson.write(MiniJson.parse("{\"n\":42}"))).isEqualTo("{\"n\":42}");
    }

    @Test
    void rejects_trailing_garbage_and_truncation() {
        assertThatThrownBy(() -> MiniJson.parse("{} extra")).hasMessageContaining("trailing");
        assertThatThrownBy(() -> MiniJson.parse("[1,")).hasMessageContaining("unexpected end");
        assertThatThrownBy(() -> MiniJson.parse("{\"a\" 1}")).hasMessageContaining("expected");
    }
}
