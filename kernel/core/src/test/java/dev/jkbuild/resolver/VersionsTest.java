// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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

    @Test
    void maven_timestamped_snapshot_ordering() {
        // Maven's publisher generates `<baseVersion>-<yyyyMMdd.HHmmss>-<buildNumber>`
        // for deployed snapshots; later timestamps compare greater.
        assertThat(Versions.compare("1.0-20260520.123456-7", "1.0-20260519.000000-1"))
                .isPositive();
        // ComparableVersion tokenises the timestamp as numeric segments rather
        // than recognising the snapshot pattern, so a timestamped snapshot
        // actually sorts ABOVE the bare `1.0` release. This is Maven's
        // documented behavior — code that needs to filter snapshots should
        // string-match `-SNAPSHOT` or the timestamp regex, not rely on order.
        assertThat(Versions.compare("1.0-20260520.123456-7", "1.0")).isPositive();
        // The bare `-SNAPSHOT` qualifier IS recognised and sorts below release.
        assertThat(Versions.compare("1.0-SNAPSHOT", "1.0")).isNegative();
    }

    @Test
    void four_segment_versions() {
        // Jib, JBoss, and friends ship 4-segment versions.
        assertThat(Versions.compare("3.0.4.1", "3.0.4")).isPositive();
        assertThat(Versions.compare("3.0.4.1", "3.0.4.2")).isNegative();
    }

    @Test
    void numeric_qualifier_components_are_numeric() {
        // -rc10 vs -rc2 — numeric tail compares numerically.
        assertThat(Versions.compare("1.0-rc10", "1.0-rc2")).isPositive();
        assertThat(Versions.compare("1.0-alpha10", "1.0-alpha2")).isPositive();
    }

    @Test
    void pre_releases_are_unstable() {
        for (String v : new String[] {
            "2.4.0-RC2",
            "2.4.0-rc1",
            "1.0-alpha",
            "1.0-beta",
            "2.0.0-M1",
            "1.0-milestone-3",
            "1.0-cr1",
            "1.0-pre",
            "1.0-SNAPSHOT",
            "1.0.0.RC2",
            "1.0.0-beta.1",
            "1.0-20260520.123456-7",
            "3.0.0-dev"
        }) {
            assertThat(Versions.isStable(v)).as(v).isFalse();
        }
    }

    @Test
    void releases_and_release_synonyms_are_stable() {
        for (String v : new String[] {
            "2.4.0",
            "1.0",
            "1.0.0",
            "1.10.5",
            "32.1.3-jre",
            "1.0-ga",
            "1.0.Final",
            "1.0-final",
            "1.0-release",
            "1.0-sp1",
            "1.0.0-android",
            "20260520"
        }) {
            assertThat(Versions.isStable(v)).as(v).isTrue();
        }
    }

    @Test
    void non_numeric_versions_are_unstable() {
        assertThat(Versions.isStable("RELEASE")).isFalse();
        assertThat(Versions.isStable("latest")).isFalse();
    }
}
