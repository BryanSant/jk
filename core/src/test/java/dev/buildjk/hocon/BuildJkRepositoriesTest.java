// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkRepositoriesTest {

    private static final String PROJECT = """
            project {
              group    = "com.example"
              artifact = "widget"
              version  = "0.1.0"
            }
            """;

    @Test
    void parses_object_form_repositories() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                repositories {
                  central = { url = "https://repo.maven.apache.org/maven2/" }
                  internal = { url = "https://nexus.example/repository/maven-releases/" }
                }
                """);

        assertThat(parsed.repositories()).hasSize(2);
        assertThat(parsed.repositories())
                .extracting(r -> r.name() + "=" + r.url())
                .containsExactlyInAnyOrder(
                        "central=https://repo.maven.apache.org/maven2/",
                        "internal=https://nexus.example/repository/maven-releases/");
    }

    @Test
    void parses_string_form_repositories() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                repositories {
                  central = "https://repo.maven.apache.org/maven2/"
                }
                """);
        assertThat(parsed.repositories())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("central");
                    assertThat(r.url()).isEqualTo(URI.create("https://repo.maven.apache.org/maven2/"));
                });
    }

    @Test
    void empty_block_means_no_repos() {
        BuildJk parsed = BuildJkParser.parse(PROJECT);
        assertThat(parsed.repositories()).isEmpty();
    }

    @Test
    void missing_url_field_rejected() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                repositories {
                  weird = { name = "weird" }
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("url");
    }
}
