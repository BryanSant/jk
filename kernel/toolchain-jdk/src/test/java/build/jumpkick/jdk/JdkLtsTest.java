// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class JdkLtsTest {

    @Test
    void legacy_pre_17_majors_are_not_lts() {
        // jk intentionally drops 8 and 11 — they're below the supported floor.
        assertThat(JdkLts.isLtsMajor(8)).isFalse();
        assertThat(JdkLts.isLtsMajor(11)).isFalse();
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
        // Snapshot of what the JetBrains feed publishes as of mid-2026,
        // already filtered to jk's supported range (>= 17).
        var feedMajors = List.of(17, 21, 24, 25, 26);
        assertThat(JdkLts.latestLtsIn(feedMajors)).hasValue(25);
    }

    @Test
    void latest_lts_advances_when_29_ships() {
        var futureFeed = List.of(17, 21, 25, 27, 28, 29);
        assertThat(JdkLts.latestLtsIn(futureFeed)).hasValue(29);
    }

    @Test
    void latest_lts_empty_when_no_candidates_are_lts() {
        var nonLts = List.of(22, 23, 26);
        assertThat(JdkLts.latestLtsIn(nonLts)).isEqualTo(OptionalInt.empty());
    }
}
