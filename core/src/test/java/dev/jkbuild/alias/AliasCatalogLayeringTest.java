// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.alias;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasCatalogLayeringTest {

    private static final AliasCatalog.Module BUNDLED_PICOCLI =
            new AliasCatalog.Module("info.picocli", "picocli");

    private static final AliasCatalog.Module FORK_PICOCLI =
            new AliasCatalog.Module("io.fork", "picocli");

    @Test
    void project_overrides_shadow_bundled_entries() {
        AliasCatalog bundled = AliasCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        AliasCatalog effective = bundled.withProjectOverrides(Map.of("picocli", FORK_PICOCLI));

        assertThat(effective.lookup("picocli")).contains(FORK_PICOCLI);
        assertThat(effective.source("picocli"))
                .get().extracting(AliasCatalog.Source::layer).isEqualTo("project");
    }

    @Test
    void project_layer_extends_bundled_without_collisions() {
        AliasCatalog bundled = AliasCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        AliasCatalog effective = bundled.withProjectOverrides(Map.of(
                "internal-thing", new AliasCatalog.Module("com.acme", "internal-thing")));

        assertThat(effective.lookup("picocli")).contains(BUNDLED_PICOCLI);
        assertThat(effective.lookup("internal-thing"))
                .get().extracting(AliasCatalog.Module::moduleKey).isEqualTo("com.acme:internal-thing");
        assertThat(effective.source("picocli"))
                .get().extracting(AliasCatalog.Source::layer).isEqualTo("test");
        assertThat(effective.source("internal-thing"))
                .get().extracting(AliasCatalog.Source::layer).isEqualTo("project");
    }

    @Test
    void empty_project_overrides_return_the_same_catalog() {
        AliasCatalog bundled = AliasCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        assertThat(bundled.withProjectOverrides(Map.of())).isSameAs(bundled);
        assertThat(bundled.withProjectOverrides(null)).isSameAs(bundled);
    }

    @Test
    void layer_names_walk_in_lookup_order() {
        AliasCatalog bundled = AliasCatalog.of(Map.of("a", BUNDLED_PICOCLI));
        AliasCatalog withProject = bundled.withProjectOverrides(Map.of(
                "a", new AliasCatalog.Module("project", "a")));
        assertThat(withProject.layerNames()).containsExactly("project", "test");
    }
}
