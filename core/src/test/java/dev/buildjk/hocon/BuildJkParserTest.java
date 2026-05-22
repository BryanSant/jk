// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkParserTest {

    @Test
    void parses_minimal_project_block() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "0.1.0"
                  jdk      = "21"
                }
                """;
        BuildJk parsed = BuildJkParser.parse(hocon);
        assertThat(parsed.project().group()).isEqualTo("com.example");
        assertThat(parsed.project().artifact()).isEqualTo("widget");
        assertThat(parsed.project().version()).isEqualTo("0.1.0");
        assertThat(parsed.project().jdk()).isEqualTo("21");
    }

    @Test
    void jdk_is_optional() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "0.1.0"
                }
                """;
        BuildJk parsed = BuildJkParser.parse(hocon);
        assertThat(parsed.project().jdk()).isNull();
    }

    @Test
    void missing_project_block_fails() {
        assertThatThrownBy(() -> BuildJkParser.parse(""))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("project");
    }

    @Test
    void missing_required_key_fails() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                }
                """;
        assertThatThrownBy(() -> BuildJkParser.parse(hocon))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("version");
    }
}
