// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.registry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasRegistryTest {

    @Test
    void bundled_registry_loads_and_contains_spot_check_entries() {
        AliasRegistry r = AliasRegistry.bundled();
        assertThat(r.lookup("jackson-databind"))
                .get().extracting(AliasRegistry.Module::moduleKey)
                .isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(r.lookup("junit-jupiter"))
                .get().extracting(AliasRegistry.Module::moduleKey)
                .isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(r.lookup("picocli"))
                .get().extracting(AliasRegistry.Module::moduleKey)
                .isEqualTo("info.picocli:picocli");
    }

    @Test
    void bundled_registry_is_singleton() {
        assertThat(AliasRegistry.bundled()).isSameAs(AliasRegistry.bundled());
    }

    @Test
    void unknown_names_return_empty() {
        assertThat(AliasRegistry.bundled().lookup("does-not-exist-xyz")).isEmpty();
        assertThat(AliasRegistry.bundled().lookup(null)).isEmpty();
    }

    @Test
    void parse_accepts_inline_toml() {
        AliasRegistry r = AliasRegistry.parse("""
                [aliases]
                foo = "com.acme:foo"
                bar = "org.example:bar-core"
                """);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.lookup("foo"))
                .get().extracting(AliasRegistry.Module::moduleKey).isEqualTo("com.acme:foo");
    }

    @Test
    void parse_rejects_coord_with_version() {
        assertThatThrownBy(() -> AliasRegistry.parse("""
                [aliases]
                foo = "com.acme:foo:1.0.0"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries a version");
    }

    @Test
    void parse_rejects_non_string_value() {
        assertThatThrownBy(() -> AliasRegistry.parse("""
                [aliases]
                foo = { group = "com.acme", artifact = "foo" }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a string");
    }

    @Test
    void parse_rejects_missing_table() {
        assertThatThrownBy(() -> AliasRegistry.parse("# empty\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[aliases] table");
    }
}
