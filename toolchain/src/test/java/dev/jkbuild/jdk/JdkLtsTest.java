// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

class JdkLtsTest {

    @Test
    void legacy_lts_pair() {
        assertThat(JdkLts.isLtsMajor(8)).isTrue();
        assertThat(JdkLts.isLtsMajor(11)).isTrue();
    }

    @Test
    void cadence_lts_from_17() {
        assertThat(JdkLts.isLtsMajor(17)).isTrue();
        assertThat(JdkLts.isLtsMajor(21)).isTrue();
        assertThat(JdkLts.isLtsMajor(25)).isTrue();
        assertThat(JdkLts.isLtsMajor(29)).isTrue();
        assertThat(JdkLts.isLtsMajor(33)).isTrue();
    }

    @Test
    void interim_majors_are_not_lts() {
        for (int m : new int[] {9, 10, 12, 13, 14, 15, 16, 18, 19, 20, 22, 23, 24, 26, 27, 28}) {
            assertThat(JdkLts.isLtsMajor(m)).as("major %d should not be LTS", m).isFalse();
        }
    }

    @Test
    void latest_lts_in_today_feed_is_25() {
        // Snapshot of what the JetBrains feed publishes as of mid-2026.
        var feedMajors = List.of(8, 11, 17, 21, 24, 25, 26);
        assertThat(JdkLts.latestLtsIn(feedMajors)).hasValue(25);
    }

    @Test
    void latest_lts_advances_when_29_ships() {
        var futureFeed = List.of(11, 17, 21, 25, 27, 28, 29);
        assertThat(JdkLts.latestLtsIn(futureFeed)).hasValue(29);
    }

    @Test
    void latest_lts_empty_when_no_candidates_are_lts() {
        var weirdFeed = List.of(9, 10, 12, 22, 26);
        assertThat(JdkLts.latestLtsIn(weirdFeed)).isEqualTo(OptionalInt.empty());
    }
}
