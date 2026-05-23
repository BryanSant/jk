// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionSetTest {

    @Test
    void empty_is_subset_of_everything() {
        assertThat(VersionSet.EMPTY.subsetOf(VersionSet.ALL)).isTrue();
        assertThat(VersionSet.EMPTY.subsetOf(VersionSet.exact("1.0"))).isTrue();
    }

    @Test
    void all_contains_anything() {
        assertThat(VersionSet.ALL.contains("anything")).isTrue();
        assertThat(VersionSet.ALL.complement()).isSameAs(VersionSet.EMPTY);
    }

    @Test
    void exact_contains_only_that_version() {
        VersionSet v = VersionSet.exact("1.2.3");
        assertThat(v.contains("1.2.3")).isTrue();
        assertThat(v.contains("1.2.4")).isFalse();
        assertThat(v.contains("1.2.2")).isFalse();
    }

    @Test
    void range_inclusive_exclusive_endpoints() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        assertThat(r.contains("1.0")).isTrue();
        assertThat(r.contains("1.5")).isTrue();
        assertThat(r.contains("2.0")).isFalse();
        assertThat(r.contains("0.9")).isFalse();
    }

    @Test
    void intersect_of_overlapping_ranges() {
        VersionSet a = VersionSet.between("1.0", true, "3.0", false);
        VersionSet b = VersionSet.between("2.0", true, "4.0", false);
        VersionSet ab = a.intersect(b);
        assertThat(ab.contains("2.5")).isTrue();
        assertThat(ab.contains("1.5")).isFalse();
        assertThat(ab.contains("3.0")).isFalse();
    }

    @Test
    void intersect_of_disjoint_ranges_is_empty() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("3.0", true, "4.0", false);
        assertThat(a.intersect(b).isEmpty()).isTrue();
    }

    @Test
    void union_of_overlapping_ranges_merges() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("1.5", true, "3.0", false);
        VersionSet ab = a.union(b);
        assertThat(ab.contains("1.0")).isTrue();
        assertThat(ab.contains("2.5")).isTrue();
        assertThat(ab.contains("3.0")).isFalse();
        // Should fuse into a single range, not a Union.
        assertThat(ab).isInstanceOf(VersionSet.Range.class);
    }

    @Test
    void union_of_disjoint_ranges_is_a_union() {
        VersionSet a = VersionSet.between("1.0", true, "2.0", false);
        VersionSet b = VersionSet.between("3.0", true, "4.0", false);
        VersionSet ab = a.union(b);
        assertThat(ab).isInstanceOf(VersionSet.Union.class);
        assertThat(ab.contains("1.5")).isTrue();
        assertThat(ab.contains("2.5")).isFalse();
        assertThat(ab.contains("3.5")).isTrue();
    }

    @Test
    void complement_of_range_is_two_disjoint_ranges() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        VersionSet c = r.complement();
        assertThat(c.contains("0.5")).isTrue();
        assertThat(c.contains("1.0")).isFalse();
        assertThat(c.contains("1.5")).isFalse();
        assertThat(c.contains("2.0")).isTrue();
        assertThat(c.contains("3.0")).isTrue();
    }

    @Test
    void complement_of_complement_is_self() {
        VersionSet r = VersionSet.between("1.0", true, "2.0", false);
        assertThat(r.complement().complement().contains("1.0")).isTrue();
        assertThat(r.complement().complement().contains("2.0")).isFalse();
    }

    @Test
    void subset_holds_for_nested_ranges() {
        VersionSet inner = VersionSet.between("1.5", true, "1.8", false);
        VersionSet outer = VersionSet.between("1.0", true, "2.0", false);
        assertThat(inner.subsetOf(outer)).isTrue();
        assertThat(outer.subsetOf(inner)).isFalse();
    }

    @Test
    void inverted_range_at_construction_is_rejected() {
        assertThatThrownBy(() -> VersionSet.between("2.0", true, "1.0", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
