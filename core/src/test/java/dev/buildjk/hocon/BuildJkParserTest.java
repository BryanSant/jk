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

    @Test
    void parses_main_shadow_native_and_language() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "0.1.0"
                  jdk      = "25"
                  language = "kotlin"
                  main     = "com.example.App"
                  shadow   = true
                  native   = true
                }
                """;
        BuildJk parsed = BuildJkParser.parse(hocon);
        assertThat(parsed.project().main()).isEqualTo("com.example.App");
        assertThat(parsed.project().shadow()).isTrue();
        assertThat(parsed.project().nativeImage()).isTrue();
        assertThat(parsed.project().language()).isEqualTo("kotlin");
        assertThat(parsed.project().isRunnable()).isTrue();
    }

    @Test
    void defaults_when_new_fields_absent() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "0.1.0"
                }
                """;
        BuildJk parsed = BuildJkParser.parse(hocon);
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().shadow()).isFalse();
        assertThat(parsed.project().nativeImage()).isFalse();
        assertThat(parsed.project().language()).isEqualTo("java");
        assertThat(parsed.project().isRunnable()).isFalse();
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecated_bin_field_still_parses_and_marks_runnable() {
        String hocon = """
                project {
                  group    = "com.example"
                  artifact = "widget"
                  version  = "0.1.0"
                  bin      = "widget"
                }
                """;
        BuildJk parsed = BuildJkParser.parse(hocon);
        assertThat(parsed.project().bin()).isEqualTo("widget");
        assertThat(parsed.project().main()).isNull();
        assertThat(parsed.project().isRunnable()).isTrue();
    }
}
