// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FeaturesTest {

    @Test
    void empty_features_resolve_to_nothing() {
        Features features = Features.empty();
        assertThat(features.activate(Set.of(), true)).isEmpty();
        assertThat(features.requestedDepNames(Set.of())).isEmpty();
    }

    @Test
    void activate_returns_requested_plus_defaults() {
        Features features = new Features(
                Map.of(
                        "postgres", Feature.of("postgres"),
                        "mysql", Feature.of("mysql"),
                        "jackson", Feature.of("jackson")),
                List.of("postgres", "jackson"));

        Set<String> withDefaults = features.activate(Set.of("mysql"), true);
        assertThat(withDefaults).containsExactlyInAnyOrder("postgres", "jackson", "mysql");

        Set<String> withoutDefaults = features.activate(Set.of("mysql"), false);
        assertThat(withoutDefaults).containsExactly("mysql");
    }

    @Test
    void nested_feature_activates_transitively() {
        // Feature deps are dependency NAMES (the [dependencies.*] short-name
        // keys), not coords — the resolver maps them to declared optional deps.
        Feature full = new Feature("full", List.of(), List.of("postgres", "jackson"));
        Feature postgres = new Feature("postgres", List.of("postgresql"), List.of());
        Feature jackson = new Feature("jackson", List.of("jackson-databind"), List.of());

        Features features = new Features(Map.of("full", full, "postgres", postgres, "jackson", jackson), List.of());

        Set<String> activated = features.activate(Set.of("full"), false);
        assertThat(activated).containsExactlyInAnyOrder("full", "postgres", "jackson");

        assertThat(features.requestedDepNames(activated)).containsExactlyInAnyOrder("postgresql", "jackson-databind");
    }

    @Test
    void unknown_feature_throws() {
        Features features = new Features(Map.of(), List.of());
        assertThatThrownBy(() -> features.activate(Set.of("nope"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown feature");
    }
}
