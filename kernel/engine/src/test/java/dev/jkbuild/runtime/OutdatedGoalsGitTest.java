// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure git tag → {latest stable, tip} selection used by {@code jk outdated}. */
class OutdatedGoalsGitTest {

    @Test
    void latest_is_highest_stable_tag_tip_is_prerelease_ahead() {
        String[] r = OutdatedGoals.gitLatestAndTip(List.of("v1.0.0", "v1.1.0", "v2.0.0-rc1"));
        assertThat(r[0]).isEqualTo("v1.1.0"); // newest stable
        assertThat(r[1]).isEqualTo("v2.0.0-rc1"); // prerelease ahead of latest stable
    }

    @Test
    void tip_falls_back_to_moving_head_when_no_prerelease_is_ahead() {
        String[] r = OutdatedGoals.gitLatestAndTip(List.of("v1.0.0", "v1.2.0"));
        assertThat(r[0]).isEqualTo("v1.2.0");
        assertThat(r[1]).isEqualTo("tip");
    }

    @Test
    void non_version_tags_are_ignored() {
        String[] r = OutdatedGoals.gitLatestAndTip(List.of("latest", "nightly", "v3.4.5"));
        assertThat(r[0]).isEqualTo("v3.4.5");
    }

    @Test
    void empty_when_no_version_like_tags() {
        String[] r = OutdatedGoals.gitLatestAndTip(List.of("main", "release"));
        assertThat(r[0]).isEmpty();
        assertThat(r[1]).isEqualTo("tip");
    }
}
