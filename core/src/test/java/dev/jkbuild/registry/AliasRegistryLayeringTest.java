// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.registry;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AliasRegistryLayeringTest {

    private static final AliasRegistry.Module BUNDLED_PICOCLI =
            new AliasRegistry.Module("info.picocli", "picocli");

    private static final AliasRegistry.Module FORK_PICOCLI =
            new AliasRegistry.Module("io.fork", "picocli");

    @Test
    void project_overrides_shadow_bundled_entries() {
        AliasRegistry bundled = AliasRegistry.of(Map.of("picocli", BUNDLED_PICOCLI));
        AliasRegistry effective = bundled.withProjectOverrides(Map.of("picocli", FORK_PICOCLI));

        assertThat(effective.lookup("picocli")).contains(FORK_PICOCLI);
        assertThat(effective.source("picocli"))
                .get().extracting(AliasRegistry.Source::layer).isEqualTo("project");
    }

    @Test
    void project_layer_extends_bundled_without_collisions() {
        AliasRegistry bundled = AliasRegistry.of(Map.of("picocli", BUNDLED_PICOCLI));
        AliasRegistry effective = bundled.withProjectOverrides(Map.of(
                "internal-thing", new AliasRegistry.Module("com.acme", "internal-thing")));

        assertThat(effective.lookup("picocli")).contains(BUNDLED_PICOCLI);
        assertThat(effective.lookup("internal-thing"))
                .get().extracting(AliasRegistry.Module::moduleKey).isEqualTo("com.acme:internal-thing");
        assertThat(effective.source("picocli"))
                .get().extracting(AliasRegistry.Source::layer).isEqualTo("test");
        assertThat(effective.source("internal-thing"))
                .get().extracting(AliasRegistry.Source::layer).isEqualTo("project");
    }

    @Test
    void empty_project_overrides_return_the_same_registry() {
        AliasRegistry bundled = AliasRegistry.of(Map.of("picocli", BUNDLED_PICOCLI));
        assertThat(bundled.withProjectOverrides(Map.of())).isSameAs(bundled);
        assertThat(bundled.withProjectOverrides(null)).isSameAs(bundled);
    }

    @Test
    void layer_names_walk_in_lookup_order() {
        AliasRegistry bundled = AliasRegistry.of(Map.of("a", BUNDLED_PICOCLI));
        AliasRegistry withProject = bundled.withProjectOverrides(Map.of(
                "a", new AliasRegistry.Module("project", "a")));
        assertThat(withProject.layerNames()).containsExactly("project", "test");
    }
}
