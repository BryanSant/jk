// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class DiagnosticsTest {

    @Test
    void renders_dependency_conflict() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("2.0")))
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        try {
            solver.solve("root", "1.0", List.of(
                    Term.positive("a", VersionSet.exact("1.0")),
                    Term.positive("b", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("Cannot resolve dependencies");
            assertThat(rendered).contains("a 1.0 depends on shared 1.0");
            assertThat(rendered).contains("b 1.0 depends on shared 2.0");
            assertThat(rendered).contains("unsatisfiable");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void renders_missing_version() {
        PackageSource src = InMemoryPackageSource.builder()
                .version("widget", "1.0")
                .build();

        PubGrubSolver solver = new PubGrubSolver(src);
        try {
            solver.solve("root", "1.0",
                    List.of(Term.positive("widget", VersionSet.exact("9.9.9"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("no versions of widget");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void leaf_sentences_are_deduplicated() {
        // Two derived nodes whose subtrees share a dependency leaf should
        // not print the same sentence twice.
        Term root = Term.positive("root", VersionSet.exact("1.0"));
        Term dep = Term.positive("widget", VersionSet.exact("1.0"));
        Incompatibility shared = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Dependency(root, dep));

        Incompatibility a = shared;
        Incompatibility b = shared;
        Incompatibility derived = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Derived(a, b));

        String rendered = Diagnostics.render(derived);
        long bulletCount = rendered.lines().filter(l -> l.startsWith("  - ")).count();
        assertThat(bulletCount).isEqualTo(1);
    }

    @Test
    void renders_therefore_chain_for_derived_incompatibilities() {
        // A conflict between two declared deps produces a Derived inco at
        // some point in resolution. The diagnostic should emit a
        // "therefore:" sentence after stating the inputs.
        PackageSource src = InMemoryPackageSource.builder()
                .version("shared", "1.0")
                .version("shared", "2.0")
                .version("a", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("1.0")))
                .version("b", "1.0", deps -> deps
                        .require("shared", VersionSet.exact("2.0")))
                .build();

        try {
            new PubGrubSolver(src).solve("root", "1.0", List.of(
                    Term.positive("a", VersionSet.exact("1.0")),
                    Term.positive("b", VersionSet.exact("1.0"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(e.rootCause());
            assertThat(rendered).contains("therefore:");
            assertThat(rendered).contains("cannot all hold");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void shared_incompatibilities_get_back_references() {
        // A diamond: root inco = Derived(D1, D2) where D1 and D2 both
        // reference the same leaf. The leaf appears once and is numbered.
        Term root = Term.positive("root", VersionSet.exact("1.0"));
        Term dep = Term.positive("widget", VersionSet.exact("1.0"));
        Incompatibility leaf = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Dependency(root, dep));

        // Two distinct Derived nodes that both reference the leaf.
        Incompatibility branchA = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Derived(leaf, leaf));
        Incompatibility branchB = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Derived(leaf, leaf));
        Incompatibility top = new Incompatibility(
                List.of(root, dep.invert()),
                new Incompatibility.Cause.Derived(branchA, branchB));

        String rendered = Diagnostics.render(top);
        // The shared leaf should be numbered (it has >1 incoming edges) and
        // referenced by number on its second mention.
        assertThat(rendered).contains("(see #");
    }

    @Test
    void artifact_defaulting_hint_fires_when_unknown_package_matches_root_name() {
        // User wrote: postgres = { group = "org.postgresql", version = "42.7.4" }
        // The parser defaults artifact = "postgres", producing the module
        // "org.postgresql:postgres" — which doesn't exist on Maven Central.
        // The diagnostic should explain the defaulting and suggest setting
        // `artifact = "..."` explicitly.
        PackageSource src = InMemoryPackageSource.builder().build();   // no packages exist

        try {
            new PubGrubSolver(src).solve("root", "1.0",
                    List.of(Term.positive("org.postgresql:postgres", VersionSet.exact("42.7.4"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(
                    e.rootCause(),
                    Map.of("org.postgresql:postgres", "postgres"));
            assertThat(rendered).contains("Hint: the dep `postgres` resolves to `org.postgresql:postgres`");
            assertThat(rendered).contains("set\n`artifact` explicitly");
            assertThat(rendered).contains("postgres = { group = \"org.postgresql\", artifact = \"<correct-artifact>\"");
            assertThat(rendered).contains("docs/artifact-coord-design.md");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void artifact_defaulting_hint_skipped_when_artifact_was_explicit() {
        // User wrote: postgres-jdbc = { group = "org.postgresql", artifact = "postgresql", ... }
        // The artifact ("postgresql") doesn't match the name ("postgres-jdbc"),
        // so the hint is irrelevant — the user clearly typed the artifact on
        // purpose. We should NOT pester them with the defaulting note.
        PackageSource src = InMemoryPackageSource.builder().build();

        try {
            new PubGrubSolver(src).solve("root", "1.0",
                    List.of(Term.positive("org.postgresql:postgresql", VersionSet.exact("42.7.4"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(
                    e.rootCause(),
                    Map.of("org.postgresql:postgresql", "postgres-jdbc"));
            assertThat(rendered).doesNotContain("Hint: the dep");
            assertThat(rendered).doesNotContain("artifact-coord-design");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }

    @Test
    void artifact_defaulting_hint_skipped_when_versions_exist_but_constraint_unmet() {
        // The artifact exists; the user just asked for a version that
        // doesn't. This is a constraint problem, not an artifact-name
        // problem — the hint would mislead.
        PackageSource src = InMemoryPackageSource.builder()
                .version("org.postgresql:postgresql", "42.7.4")
                .build();

        try {
            new PubGrubSolver(src).solve("root", "1.0",
                    List.of(Term.positive("org.postgresql:postgresql", VersionSet.exact("99.9.9"))));
            fail("expected UnsatisfiableException");
        } catch (UnsatisfiableException e) {
            String rendered = Diagnostics.render(
                    e.rootCause(),
                    Map.of("org.postgresql:postgresql", "postgresql"));
            assertThat(rendered).doesNotContain("Hint: the dep");
        } catch (Exception e) {
            fail("expected UnsatisfiableException, got: " + e);
        }
    }
}
