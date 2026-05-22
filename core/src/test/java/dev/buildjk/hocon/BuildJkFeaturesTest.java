// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkFeaturesTest {

    private static final String PROJECT = """
            project {
              group    = "com.example"
              artifact = "widget"
              version  = "0.1.0"
            }
            """;

    @Test
    void parses_features_with_deps_and_defaults() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                features {
                  default  = [ "postgres", "jackson" ]
                  postgres = { deps = [ "org.postgresql:postgresql:42.7.4" ] }
                  jackson  = { deps = [ "com.fasterxml.jackson.core:jackson-databind:2.18.2" ] }
                  mysql    = { deps = [ "com.mysql:mysql-connector-j:9.0.0" ] }
                }
                """);

        assertThat(parsed.features().defaults()).containsExactly("postgres", "jackson");
        assertThat(parsed.features().byName()).containsOnlyKeys("postgres", "jackson", "mysql");
        assertThat(parsed.features().byName().get("postgres").deps())
                .containsExactly("org.postgresql:postgresql:42.7.4");
    }

    @Test
    void parses_features_with_nested_feature_refs() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                features {
                  full     = { features = [ "postgres", "jackson" ] }
                  postgres = { deps = [ "org.postgresql:postgresql:42.7.4" ] }
                  jackson  = { deps = [ "com.fasterxml.jackson.core:jackson-databind:2.18.2" ] }
                }
                """);

        assertThat(parsed.features().byName().get("full").features())
                .containsExactly("postgres", "jackson");
    }

    @Test
    void non_object_feature_rejected() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                features {
                  bad = "oops"
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("must be an object");
    }

    @Test
    void default_must_be_a_list() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                features {
                  default = "postgres"
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("list");
    }

    @Test
    void empty_features_block_yields_empty_features() {
        BuildJk parsed = BuildJkParser.parse(PROJECT);
        assertThat(parsed.features().isEmpty()).isTrue();
    }
}
