// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TermTest {

    @Test
    void invert_flips_polarity() {
        Term t = Term.positive("a", VersionSet.exact("1.0"));
        assertThat(t.invert().positive()).isFalse();
        assertThat(t.invert().invert()).isEqualTo(t);
    }

    @Test
    void contradicts_when_intersection_is_empty() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("a", VersionSet.exact("2.0"));
        assertThat(a.contradicts(b)).isTrue();
    }

    @Test
    void does_not_contradict_when_sets_overlap() {
        Term a = Term.positive("a", VersionSet.between("1.0", true, "3.0", false));
        Term b = Term.positive("a", VersionSet.between("2.0", true, "4.0", false));
        assertThat(a.contradicts(b)).isFalse();
    }

    @Test
    void terms_on_different_packages_never_contradict() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("b", VersionSet.exact("1.0"));
        assertThat(a.contradicts(b)).isFalse();
    }

    @Test
    void relation_satisfies_when_this_implies_other() {
        Term tighter = Term.positive("a", VersionSet.exact("1.5"));
        Term wider = Term.positive("a", VersionSet.between("1.0", true, "2.0", false));
        assertThat(tighter.relation(wider)).isEqualTo(Term.Relation.SATISFIES);
        // The wider doesn't necessarily satisfy the tighter.
        assertThat(wider.relation(tighter)).isNotEqualTo(Term.Relation.SATISFIES);
    }

    @Test
    void relation_contradicts_when_disjoint() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("a", VersionSet.exact("2.0"));
        assertThat(a.relation(b)).isEqualTo(Term.Relation.CONTRADICTS);
    }

    @Test
    void relation_overlaps_when_partial() {
        Term a = Term.positive("a", VersionSet.between("1.0", true, "3.0", false));
        Term b = Term.positive("a", VersionSet.between("2.0", true, "4.0", false));
        assertThat(a.relation(b)).isEqualTo(Term.Relation.OVERLAPS);
    }

    @Test
    void negative_term_effective_set_is_complement() {
        Term t = Term.negative("a", VersionSet.exact("1.0"));
        assertThat(t.effectiveVersions().contains("1.0")).isFalse();
        assertThat(t.effectiveVersions().contains("2.0")).isTrue();
    }

    @Test
    void intersecting_across_packages_throws() {
        Term a = Term.positive("a", VersionSet.exact("1.0"));
        Term b = Term.positive("b", VersionSet.exact("1.0"));
        assertThatThrownBy(() -> a.intersect(b)).isInstanceOf(IllegalArgumentException.class);
    }
}
