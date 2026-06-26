// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SupportedJdkTest {

    @Test
    void floor_rejects_anything_below_17() {
        assertThat(SupportedJdk.isSupported(8)).isFalse();
        assertThat(SupportedJdk.isSupported(11)).isFalse();
        assertThat(SupportedJdk.isSupported(16)).isFalse();
        assertThat(SupportedJdk.isSupported(17)).isTrue();
        assertThat(SupportedJdk.isSupported(21)).isTrue();
        assertThat(SupportedJdk.isSupported(25)).isTrue();
        assertThat(SupportedJdk.isSupported(26)).isTrue();
    }

    @Test
    void first_class_keeps_lts_plus_latest_drops_interim_non_lts() {
        // Feed publishes 17, 21, 23, 24, 25, 26 — keep {17, 21, 25, 26}.
        assertThat(SupportedJdk.firstClassMajors(List.of(17, 21, 23, 24, 25, 26)))
                .containsExactly(17, 21, 25, 26);
    }

    @Test
    void first_class_keeps_latest_even_when_it_is_lts() {
        // No non-LTS in the set — the single-latest pass folds into the LTS set.
        assertThat(SupportedJdk.firstClassMajors(List.of(17, 21, 25))).containsExactly(17, 21, 25);
    }

    @Test
    void first_class_strips_pre_17_majors() {
        // 8 and 11 are dropped even though they were LTS pre-cadence.
        assertThat(SupportedJdk.firstClassMajors(List.of(8, 11, 17, 21, 25, 26)))
                .containsExactly(17, 21, 25, 26);
    }

    @Test
    void empty_input_yields_empty_set() {
        assertThat(SupportedJdk.firstClassMajors(List.of())).isEmpty();
    }
}
