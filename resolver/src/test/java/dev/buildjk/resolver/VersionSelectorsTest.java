// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.model.VersionSelector;
import dev.buildjk.resolver.pubgrub.VersionSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionSelectorsTest {

    @Test
    void caret_with_nonzero_major_bumps_major() {
        VersionSet set = VersionSelectors.caretRange("1.2.3");
        assertThat(set.contains("1.2.3")).isTrue();
        assertThat(set.contains("1.99.99")).isTrue();
        assertThat(set.contains("2.0.0")).isFalse();
        assertThat(set.contains("1.2.2")).isFalse();
    }

    @Test
    void caret_with_zero_major_bumps_minor() {
        VersionSet set = VersionSelectors.caretRange("0.2.3");
        assertThat(set.contains("0.2.5")).isTrue();
        assertThat(set.contains("0.3.0")).isFalse();
    }

    @Test
    void caret_with_zero_major_and_minor_bumps_patch() {
        VersionSet set = VersionSelectors.caretRange("0.0.3");
        assertThat(set.contains("0.0.3")).isTrue();
        assertThat(set.contains("0.0.4")).isFalse();
    }

    @Test
    void exact_selector_maps_to_exact_versionset() {
        VersionSet set = VersionSelectors.toVersionSet(
                new VersionSelector.Exact("=1.0", "1.0"));
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.1")).isFalse();
    }

    @Test
    void tilde_locks_minor() {
        VersionSet set = VersionSelectors.tildeRange("1.2.3");
        assertThat(set.contains("1.2.5")).isTrue();
        assertThat(set.contains("1.3.0")).isFalse();
    }

    @Test
    void latest_maps_to_all() {
        VersionSet set = VersionSelectors.toVersionSet(new VersionSelector.Latest("latest"));
        assertThat(set.isAll()).isTrue();
    }
}
