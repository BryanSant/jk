// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.jumpkick.config.JkBuildParseException;
import build.jumpkick.config.JkBuildParser;
import org.junit.jupiter.api.Test;

class UnownedTableTest {

    @Test
    void unowned_table_is_an_error_naming_the_remedy() {
        assertThatThrownBy(() -> JkBuildParser.parse("""
                        [project]
                        name = "demo"
                        group = "com.example"
                        version = "1.0.0"

                        [micronaut]
                        version = "4.0.0"
                        """))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[micronaut] is not owned by any installed plugin")
                .hasMessageContaining("add it under [plugins]");
    }

    @Test
    void core_and_plugin_tables_pass() {
        var build = JkBuildParser.parse("""
                [project]
                name = "demo"
                group = "com.example"
                version = "1.0.0"

                [dependencies]

                [test-dependencies]

                [jvm]
                max-ram-percent = 50

                [deny]

                [spring-boot]
                version = "4.0.1"
                """);
        assertThat(build.pluginConfig("spring-boot")).isPresent();
    }

    @Test
    void unresolved_declarations_suppress_the_gate() {
        // [acme] cannot be judged: the declared plugin's manifest is not materialized (no lock,
        // no store), so the parse must stay soft — the engine materializes and re-parses.
        var build = JkBuildParser.parse("""
                [project]
                name = "demo"
                group = "com.example"
                version = "1.0.0"

                [plugins]
                acme = { group = "com.example", name = "acme-jk-plugin", version = "1.0.0" }

                [acme]
                widgets = true
                """);
        assertThat(build.plugins()).hasSize(1);
        assertThat(build.pluginConfig("acme")).isEmpty();
    }
}
