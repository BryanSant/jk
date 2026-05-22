// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void numeric_segments_compare_numerically() {
        assertThat(Versions.compare("2.18.10", "2.18.2")).isPositive();
        assertThat(Versions.compare("2.18.2", "2.18.10")).isNegative();
        assertThat(Versions.compare("2.18.2", "2.18.2")).isZero();
    }

    @Test
    void shorter_pads_with_zero() {
        assertThat(Versions.compare("2.18", "2.18.0")).isZero();
        assertThat(Versions.compare("2.18.0", "2.18")).isZero();
        assertThat(Versions.compare("2.18", "2.18.1")).isNegative();
    }

    @Test
    void numeric_beats_non_numeric_segment() {
        assertThat(Versions.compare("1.0.0", "1.0.0-rc1")).isPositive();
    }

    @Test
    void treats_dash_and_dot_as_separators() {
        assertThat(Versions.compare("1.0-1", "1.0.0")).isPositive();
    }
}
