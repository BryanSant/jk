// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import org.junit.jupiter.api.Test;

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
        VersionSet set = VersionSelectors.toVersionSet(new VersionSelector.Exact("=1.0", "1.0"));
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

    @Test
    void parses_maven_bracket_range_inclusive_inclusive() {
        VersionSet set = VersionSelectors.parseRange("[1.0,2.0]");
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.5")).isTrue();
        assertThat(set.contains("2.0")).isTrue();
        assertThat(set.contains("2.1")).isFalse();
    }

    @Test
    void parses_maven_bracket_range_exclusive_upper() {
        VersionSet set = VersionSelectors.parseRange("[1.0,2.0)");
        assertThat(set.contains("2.0")).isFalse();
        assertThat(set.contains("1.9")).isTrue();
    }

    @Test
    void parses_maven_bracket_exact() {
        VersionSet set = VersionSelectors.parseRange("[1.0]");
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.1")).isFalse();
    }

    @Test
    void parses_open_lower_bound() {
        VersionSet set = VersionSelectors.parseRange("(,2.0)");
        assertThat(set.contains("0.1")).isTrue();
        assertThat(set.contains("1.99")).isTrue();
        assertThat(set.contains("2.0")).isFalse();
    }

    @Test
    void parses_comparator_list() {
        VersionSet set = VersionSelectors.parseRange(">=1.0, <2.0");
        assertThat(set.contains("1.0")).isTrue();
        assertThat(set.contains("1.5")).isTrue();
        assertThat(set.contains("2.0")).isFalse();
        assertThat(set.contains("0.9")).isFalse();
    }

    @Test
    void range_selector_maps_to_parsed_versionset() {
        VersionSet set = VersionSelectors.toVersionSet(new VersionSelector.Range(">=1.0, <2.0"));
        assertThat(set.contains("1.5")).isTrue();
        assertThat(set.contains("2.5")).isFalse();
    }
}
