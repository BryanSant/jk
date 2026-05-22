// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkDependenciesTest {

    private static final String PROJECT = """
            project {
              group    = "com.example"
              artifact = "widget"
              version  = "0.1.0"
            }
            """;

    @Test
    void parses_string_form_dependencies() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                dependencies.main {
                  "com.fasterxml.jackson.core:jackson-databind" = "2.18.2"
                  "org.slf4j:slf4j-api" = "=2.0.16"
                }
                """);

        assertThat(parsed.dependencies().of(Scope.MAIN))
                .extracting(d -> d.module())
                .containsExactlyInAnyOrder(
                        "com.fasterxml.jackson.core:jackson-databind",
                        "org.slf4j:slf4j-api");

        VersionSelector slf4jSelector = parsed.dependencies().of(Scope.MAIN).stream()
                .filter(d -> d.module().equals("org.slf4j:slf4j-api"))
                .findFirst()
                .orElseThrow()
                .version();
        assertThat(slf4jSelector).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void parses_object_form_dependencies() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                dependencies.test {
                  "org.junit.jupiter:junit-jupiter" = { version = "6.1.0" }
                }
                """);

        assertThat(parsed.dependencies().of(Scope.TEST))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.module()).isEqualTo("org.junit.jupiter:junit-jupiter");
                    assertThat(d.version()).isInstanceOf(VersionSelector.Caret.class);
                });
    }

    @Test
    void object_form_requires_version() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                dependencies.main {
                  "com.foo:bar" = { from = "internal" }
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("version");
    }

    @Test
    void empty_dependencies_section_is_fine() {
        BuildJk parsed = BuildJkParser.parse(PROJECT);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
        assertThat(parsed.dependencies().of(Scope.TEST)).isEmpty();
    }
}
