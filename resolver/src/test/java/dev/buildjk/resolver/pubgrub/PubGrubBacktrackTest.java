// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver.pubgrub;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PubGrubBacktrackTest {

    @Test
    void downgrades_a_dep_when_higher_version_is_incompatible() throws Exception {
        // root depends on a + b.
        // a-1.0 requires shared in [1.0, 2.0).
        // shared has versions {2.0, 1.5, 1.0}. PubGrub will first pick shared=2.0
        // (highest), find it conflicts with a's range, backtrack, and try shared=1.5.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "2.0")
                .version("shared", "1.5")
                .version("shared", "1.0")
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.between("1.0", true, "2.0", false)))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve("root", "1.0",
                List.of(Term.positive("a", VersionSet.exact("1.0"))));

        assertThat(solution).containsEntry("shared", "1.5");
    }

    @Test
    void resolves_incompatible_exact_pins_as_unsatisfiable() {
        // a and b both ask for shared, but at incompatible exact versions.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("2.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(() -> solver.solve("root", "1.0", List.of(
                Term.positive("a", VersionSet.exact("1.0")),
                Term.positive("b", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void downgrades_through_deeper_chain() throws Exception {
        // root -> a (any 1.x) -> shared (any).
        // root -> b -> shared >= 2.0
        // a-2.0 requires shared >= 2.0
        // a-1.0 requires shared 1.0 (incompatible with b)
        // Expected: pick a-2.0 + shared-2.0
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "2.0")
                .version("shared", "1.0")
                .version("a", "2.0", deps -> deps
                        .require("shared", VersionSet.atLeast("2.0", true)))
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps
                        .require("shared", VersionSet.atLeast("2.0", true)))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve("root", "1.0", List.of(
                Term.positive("a", VersionSet.atLeast("1.0", true)),
                Term.positive("b", VersionSet.exact("1.0"))));

        assertThat(solution)
                .containsEntry("a", "2.0")
                .containsEntry("b", "1.0")
                .containsEntry("shared", "2.0");
    }

    @Test
    void unsatisfiable_when_no_compatible_versions_anywhere() {
        // a requires shared 1.0, but only shared 2.0 exists.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("1.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(() -> solver.solve("root", "1.0",
                List.of(Term.positive("a", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
    }
}
