// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.hocon;

import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Profile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildJkProfilesTest {

    private static final String PROJECT = """
            project {
              group    = "com.example"
              artifact = "widget"
              version  = "0.1.0"
            }
            """;

    @Test
    void parses_simple_profile() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                profiles {
                  dev = {
                    javac = [ "-g", "-parameters" ]
                  }
                }
                """);

        assertThat(parsed.profiles().contains("dev")).isTrue();
        Profile dev = parsed.profiles().resolve("dev");
        assertThat(dev.javacArgs()).containsExactly("-g", "-parameters");
    }

    @Test
    void parses_inherits_and_jvm_args() {
        BuildJk parsed = BuildJkParser.parse(PROJECT + """
                profiles {
                  dev = {
                    javac    = [ "-g" ]
                    jvm-args = [ "-Xshare:auto" ]
                  }
                  ci = {
                    inherits = "dev"
                    javac    = [ "-Werror" ]
                  }
                }
                """);

        Profile ci = parsed.profiles().resolve("ci");
        assertThat(ci.javacArgs()).containsExactly("-g", "-Werror");
        assertThat(ci.jvmArgs()).containsExactly("-Xshare:auto");
    }

    @Test
    void empty_profiles_block_yields_no_profiles() {
        BuildJk parsed = BuildJkParser.parse(PROJECT);
        assertThat(parsed.profiles().byName()).isEmpty();
    }

    @Test
    void non_object_profile_rejected() {
        assertThatThrownBy(() -> BuildJkParser.parse(PROJECT + """
                profiles {
                  weird = "not an object"
                }
                """))
                .isInstanceOf(BuildJkParseException.class)
                .hasMessageContaining("must be an object");
    }
}
