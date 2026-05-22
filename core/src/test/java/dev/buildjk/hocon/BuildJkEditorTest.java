// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.Scope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkEditorTest {

    private static final String MINIMAL = """
            project {
              group    = "com.example"
              artifact = "widget"
              version  = "0.1.0"
            }
            """;

    @Test
    void add_creates_block_when_absent() {
        String result = BuildJkEditor.addDependency(MINIMAL, Scope.MAIN,
                "com.fasterxml.jackson.core:jackson-databind", "2.18.2");

        assertThat(result).contains("dependencies.main {");
        assertThat(result).contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"2.18.2\"");
        assertThat(result).endsWith("}\n");
    }

    @Test
    void add_inserts_into_existing_block() {
        String input = MINIMAL + """

                dependencies.main {
                  "org.slf4j:slf4j-api" = "2.0.16"
                }
                """;
        String result = BuildJkEditor.addDependency(input, Scope.MAIN,
                "com.fasterxml.jackson.core:jackson-databind", "2.18.2");

        assertThat(result).contains("\"org.slf4j:slf4j-api\" = \"2.0.16\"");
        assertThat(result).contains("\"com.fasterxml.jackson.core:jackson-databind\" = \"2.18.2\"");
        // The existing block was reused (no second `dependencies.main {`).
        assertThat(result.split("dependencies\\.main \\{")).hasSize(2);
    }

    @Test
    void add_preserves_comments_in_unrelated_lines() {
        String input = """
                # widget service
                project {
                  group    = "com.example"
                  artifact = "widget"  # canonical name
                  version  = "0.1.0"
                }
                """;
        String result = BuildJkEditor.addDependency(input, Scope.MAIN, "com.foo:bar", "1.0");

        assertThat(result).contains("# widget service");
        assertThat(result).contains("# canonical name");
    }

    @Test
    void add_rejects_duplicate() {
        String input = MINIMAL + """

                dependencies.main {
                  "com.foo:bar" = "1.0"
                }
                """;
        assertThatThrownBy(() -> BuildJkEditor.addDependency(input, Scope.MAIN, "com.foo:bar", "2.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already contains");
    }

    @Test
    void remove_drops_declaration() {
        String input = MINIMAL + """

                dependencies.main {
                  "com.foo:bar" = "1.0"
                  "com.baz:qux" = "2.0"
                }
                """;
        String result = BuildJkEditor.removeDependency(input, Scope.MAIN, "com.foo:bar");

        assertThat(result).doesNotContain("com.foo:bar");
        assertThat(result).contains("\"com.baz:qux\" = \"2.0\"");
    }

    @Test
    void remove_complains_when_module_absent() {
        String input = MINIMAL + """

                dependencies.main {
                  "com.foo:bar" = "1.0"
                }
                """;
        assertThatThrownBy(() -> BuildJkEditor.removeDependency(input, Scope.MAIN, "com.missing:lib"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void remove_complains_when_block_absent() {
        assertThatThrownBy(() -> BuildJkEditor.removeDependency(MINIMAL, Scope.TEST, "com.foo:bar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("block not found");
    }
}
