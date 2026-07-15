// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionSelectorTest {

    @Test
    void plain_version_is_exact_by_default() {
        VersionSelector s = VersionSelector.parse("2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void caret_prefix_is_caret() {
        VersionSelector s = VersionSelector.parse("^2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void equals_prefix_is_still_exact_for_back_compat() {
        VersionSelector s = VersionSelector.parse("=2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
        assertThat(((VersionSelector.Exact) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void tilde_prefix_is_tilde() {
        VersionSelector s = VersionSelector.parse("~2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Tilde.class);
        assertThat(((VersionSelector.Tilde) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void range_with_comparators_is_range() {
        VersionSelector s = VersionSelector.parse(">=2.18, <3");
        assertThat(s).isInstanceOf(VersionSelector.Range.class);
        assertThat(s.raw()).isEqualTo(">=2.18, <3");
    }

    @Test
    void latest_keyword() {
        VersionSelector s = VersionSelector.parse("latest");
        assertThat(s).isInstanceOf(VersionSelector.Latest.class);
    }

    @Test
    void parseFloating_bare_version_is_caret() {
        VersionSelector s = VersionSelector.parseFloating("2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Caret.class);
        assertThat(((VersionSelector.Caret) s).version()).isEqualTo("2.18.2");
    }

    @Test
    void parseFloating_explicit_equals_is_exact() {
        VersionSelector s = VersionSelector.parseFloating("=2.18.2");
        assertThat(s).isInstanceOf(VersionSelector.Exact.class);
    }

    @Test
    void parseFloating_honors_other_decorations() {
        assertThat(VersionSelector.parseFloating("~2.18.2")).isInstanceOf(VersionSelector.Tilde.class);
        assertThat(VersionSelector.parseFloating(">=2.18,<3")).isInstanceOf(VersionSelector.Range.class);
        assertThat(VersionSelector.parseFloating("latest")).isInstanceOf(VersionSelector.Latest.class);
    }
}
