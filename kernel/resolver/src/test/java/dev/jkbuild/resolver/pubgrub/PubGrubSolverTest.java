// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PubGrubSolverTest {

    @Test
    void root_only_no_deps() throws Exception {
        PackageSource src = InMemoryPackageSource.builder().build();
        PubGrubSolver solver = new PubGrubSolver(src);

        Map<String, String> solution = solver.solve("root", "1.0", List.of());
        assertThat(solution).containsExactly(Map.entry("root", "1.0"));
    }

    @Test
    void single_dep_chain() throws Exception {
        PackageSource src =
                InMemoryPackageSource.builder().version("leaf", "1.0").build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("leaf", VersionSet.exact("1.0"))));

        assertThat(solution).containsOnly(Map.entry("root", "1.0"), Map.entry("leaf", "1.0"));
    }

    @Test
    void transitive_chain() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("leaf", "1.0")
                .version("middle", "1.0", deps -> deps.require("leaf", VersionSet.exact("1.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("middle", VersionSet.exact("1.0"))));

        assertThat(solution).containsOnlyKeys("root", "middle", "leaf");
    }

    @Test
    void picks_highest_satisfying_version() throws Exception {
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .version("widget", "2.0")
                .version("widget", "3.0")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve(
                "root", "1.0", List.of(Term.positive("widget", VersionSet.between("1.0", true, "3.0", false))));

        assertThat(solution).containsEntry("widget", "2.0");
    }

    @Test
    void skips_a_higher_pre_release_for_a_floating_constraint() throws Exception {
        // 3.0.0-RC1 is the highest version, but a floating >=1.0 constraint
        // must resolve to the highest *stable* version, 2.0.0.
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0.0")
                .version("widget", "2.0.0")
                .version("widget", "3.0.0-RC1")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0.0", true))));

        assertThat(solution).containsEntry("widget", "2.0.0");
    }

    @Test
    void selects_a_pre_release_when_no_stable_version_satisfies() throws Exception {
        // Only pre-releases exist — the solver must still resolve.
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "3.0.0-RC1")
                .version("widget", "3.0.0-RC2")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution =
                solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.atLeast("1.0.0", true))));

        assertThat(solution).containsEntry("widget", "3.0.0-RC2");
    }

    @Test
    void diamond_with_compatible_versions() throws Exception {
        // root -> a -> shared >= 1.0
        // root -> b -> shared <= 2.0
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.5")
                .version("shared", "1.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.atLeast("1.0", true)))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.lessThan("2.0", false)))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        Map<String, String> solution = solver.solve(
                "root",
                "1.0",
                List.of(Term.positive("a", VersionSet.exact("1.0")), Term.positive("b", VersionSet.exact("1.0"))));

        // shared must be in [1.0, 2.0). Highest version we have is 1.5.
        assertThat(solution).containsEntry("shared", "1.5");
    }

    @Test
    void unsatisfiable_throws() throws Exception {
        // Two declared deps with incompatible exact pins on the same module.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps.require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps.require("shared", VersionSet.exact("2.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(() -> solver.solve(
                        "root",
                        "1.0",
                        List.of(
                                Term.positive("a", VersionSet.exact("1.0")),
                                Term.positive("b", VersionSet.exact("1.0")))))
                .isInstanceOf(UnsatisfiableException.class);
    }

    @Test
    void no_version_satisfies_constraint() {
        PackageSource src =
                InMemoryPackageSource.builder().version("widget", "1.0").build();

        PubGrubSolver solver = new PubGrubSolver(src);
        assertThatThrownBy(
                        () -> solver.solve("root", "1.0", List.of(Term.positive("widget", VersionSet.exact("9.9.9")))))
                .isInstanceOf(UnsatisfiableException.class);
    }
}
