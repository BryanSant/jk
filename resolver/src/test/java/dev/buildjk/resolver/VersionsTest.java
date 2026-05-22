// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void numeric_segments_compare_numerically() {
        assertThat(Versions.compare("2.18.10", "2.18.2")).isPositive();
        assertThat(Versions.compare("1.10", "1.2")).isPositive();
        assertThat(Versions.compare("1.10.0", "1.10")).isZero();
    }

    @Test
    void trailing_zero_normalizes() {
        assertThat(Versions.compare("1.0", "1.0.0")).isZero();
        assertThat(Versions.compare("1.0.0", "1.0")).isZero();
        assertThat(Versions.compare("1.0", "1.0.1")).isNegative();
    }

    @Test
    void qualifier_order_matches_maven() {
        // alpha < beta < milestone < rc < snapshot < "" (release) < sp
        assertThat(Versions.compare("1.0-alpha", "1.0-beta")).isNegative();
        assertThat(Versions.compare("1.0-beta", "1.0-milestone")).isNegative();
        assertThat(Versions.compare("1.0-milestone", "1.0-rc")).isNegative();
        assertThat(Versions.compare("1.0-rc", "1.0-snapshot")).isNegative();
        assertThat(Versions.compare("1.0-snapshot", "1.0")).isNegative();
        assertThat(Versions.compare("1.0", "1.0-sp1")).isNegative();
    }

    @Test
    void qualifier_aliases() {
        // m = milestone, cr = rc, ga / final / release = ""
        assertThat(Versions.compare("1.0-m1", "1.0-milestone-1")).isZero();
        assertThat(Versions.compare("1.0-cr1", "1.0-rc-1")).isZero();
        assertThat(Versions.compare("1.0-ga", "1.0")).isZero();
        assertThat(Versions.compare("1.0-final", "1.0")).isZero();
        assertThat(Versions.compare("1.0-release", "1.0")).isZero();
    }

    @Test
    void release_beats_snapshot() {
        assertThat(Versions.compare("1.0", "1.0-SNAPSHOT")).isPositive();
        assertThat(Versions.compare("1.0-SNAPSHOT", "1.0")).isNegative();
    }

    @Test
    void unknown_qualifier_ranks_after_sp() {
        assertThat(Versions.compare("1.0-sp1", "1.0-xyzzy")).isNegative();
        // Two unknowns compare lexicographically.
        assertThat(Versions.compare("1.0-aab", "1.0-aac")).isNegative();
    }

    @Test
    void numeric_beats_non_numeric_at_same_position() {
        // 1.0 vs 1.0-rc1: trailing -rc1 is a qualifier section, ranks < ""
        assertThat(Versions.compare("1.0.0", "1.0.0-rc1")).isPositive();
    }

    @Test
    void case_insensitive_qualifier_match() {
        assertThat(Versions.compare("1.0-Alpha", "1.0-ALPHA")).isZero();
        assertThat(Versions.compare("1.0-SNAPSHOT", "1.0-snapshot")).isZero();
    }
}
