// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LibraryCatalogLayeringTest {

    private static final LibraryCatalog.Module BUNDLED_PICOCLI = new LibraryCatalog.Module("info.picocli", "picocli");

    private static final LibraryCatalog.Module FORK_PICOCLI = new LibraryCatalog.Module("io.fork", "picocli");

    @Test
    void project_overrides_shadow_bundled_entries() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        LibraryCatalog effective = bundled.withProjectOverrides(Map.of("picocli", FORK_PICOCLI));

        assertThat(effective.lookup("picocli")).contains(FORK_PICOCLI);
        assertThat(effective.source("picocli"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("project");
    }

    @Test
    void project_layer_extends_bundled_without_collisions() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        LibraryCatalog effective = bundled.withProjectOverrides(
                Map.of("internal-thing", new LibraryCatalog.Module("com.acme", "internal-thing")));

        assertThat(effective.lookup("picocli")).contains(BUNDLED_PICOCLI);
        assertThat(effective.lookup("internal-thing"))
                .get()
                .extracting(LibraryCatalog.Module::moduleKey)
                .isEqualTo("com.acme:internal-thing");
        assertThat(effective.source("picocli"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("test");
        assertThat(effective.source("internal-thing"))
                .get()
                .extracting(LibraryCatalog.Source::layer)
                .isEqualTo("project");
    }

    @Test
    void empty_project_overrides_return_the_same_catalog() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("picocli", BUNDLED_PICOCLI));
        assertThat(bundled.withProjectOverrides(Map.of())).isSameAs(bundled);
        assertThat(bundled.withProjectOverrides(null)).isSameAs(bundled);
    }

    @Test
    void layer_names_walk_in_lookup_order() {
        LibraryCatalog bundled = LibraryCatalog.of(Map.of("a", BUNDLED_PICOCLI));
        LibraryCatalog withProject =
                bundled.withProjectOverrides(Map.of("a", new LibraryCatalog.Module("project", "a")));
        assertThat(withProject.layerNames()).containsExactly("project", "test");
    }
}
