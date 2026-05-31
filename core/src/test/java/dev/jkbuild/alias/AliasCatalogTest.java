// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.alias;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasCatalogTest {

    @Test
    void bundled_catalog_loads_and_contains_spot_check_entries() {
        AliasCatalog r = AliasCatalog.bundled();
        // Jackson is split by major-version because the Maven coordinate
        // moved between 2 and 3 (com.fasterxml → tools.jackson). The
        // bundled set intentionally has no unprefixed `jackson-*`.
        assertThat(r.lookup("jackson2-databind"))
                .get().extracting(AliasCatalog.Module::moduleKey)
                .isEqualTo("com.fasterxml.jackson.core:jackson-databind");
        assertThat(r.lookup("jackson3-databind"))
                .get().extracting(AliasCatalog.Module::moduleKey)
                .isEqualTo("tools.jackson.core:jackson-databind");
        assertThat(r.lookup("jackson-databind")).isEmpty();

        assertThat(r.lookup("junit-jupiter"))
                .get().extracting(AliasCatalog.Module::moduleKey)
                .isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(r.lookup("picocli"))
                .get().extracting(AliasCatalog.Module::moduleKey)
                .isEqualTo("info.picocli:picocli");
    }

    @Test
    void suggestions_surface_split_family_for_jackson_databind() {
        // The defining motivation: a user typing the unprefixed name
        // should see both major-version flavors.
        var hits = AliasCatalog.bundled().suggestionsFor("jackson-databind", 5);
        assertThat(hits).contains("jackson2-databind", "jackson3-databind");
    }

    @Test
    void suggestions_handle_no_dash_input() {
        var hits = AliasCatalog.bundled().suggestionsFor("picocli", 5);
        // "picocli" is itself a catalog entry; we filter the exact match
        // out so a "did you mean" doesn't echo the input.
        assertThat(hits).doesNotContain("picocli");
        // …but it still surfaces related names (picocli-codegen).
        assertThat(hits).contains("picocli-codegen");
    }

    @Test
    void suggestions_return_empty_when_no_substring_matches() {
        assertThat(AliasCatalog.bundled().suggestionsFor("totally-unrelated-xyz", 5))
                .isEmpty();
    }

    @Test
    void suggestions_respect_max_results() {
        var hits = AliasCatalog.bundled().suggestionsFor("jackson", 2);
        assertThat(hits).hasSize(2);
    }

    @Test
    void suggestions_for_blank_or_null_return_empty() {
        AliasCatalog r = AliasCatalog.bundled();
        assertThat(r.suggestionsFor(null, 5)).isEmpty();
        assertThat(r.suggestionsFor("", 5)).isEmpty();
        assertThat(r.suggestionsFor("  ", 5)).isEmpty();
        assertThat(r.suggestionsFor("anything", 0)).isEmpty();
    }

    @Test
    void bundled_catalog_is_singleton() {
        assertThat(AliasCatalog.bundled()).isSameAs(AliasCatalog.bundled());
    }

    @Test
    void unknown_names_return_empty() {
        assertThat(AliasCatalog.bundled().lookup("does-not-exist-xyz")).isEmpty();
        assertThat(AliasCatalog.bundled().lookup(null)).isEmpty();
    }

    @Test
    void parse_accepts_inline_toml() {
        AliasCatalog r = AliasCatalog.parse("""
                [aliases]
                foo = "com.acme:foo"
                bar = "org.example:bar-core"
                """);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.lookup("foo"))
                .get().extracting(AliasCatalog.Module::moduleKey).isEqualTo("com.acme:foo");
    }

    @Test
    void parse_rejects_coord_with_version() {
        assertThatThrownBy(() -> AliasCatalog.parse("""
                [aliases]
                foo = "com.acme:foo:1.0.0"
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carries a version");
    }

    @Test
    void parse_rejects_non_string_value() {
        assertThatThrownBy(() -> AliasCatalog.parse("""
                [aliases]
                foo = { group = "com.acme", artifact = "foo" }
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a string");
    }

    @Test
    void parse_rejects_missing_table() {
        assertThatThrownBy(() -> AliasCatalog.parse("# empty\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[aliases] table");
    }
}
