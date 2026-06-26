// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The branch-tip freshness-window policy parsing. */
class GitFetcherFreshnessTest {

    @Test
    void window_millis_parses_policies() {
        assertThat(GitFetcher.windowMillis(null)).isEqualTo(12L * 60 * 60 * 1000); // default 12h
        assertThat(GitFetcher.windowMillis("")).isEqualTo(12L * 60 * 60 * 1000);
        assertThat(GitFetcher.windowMillis("always")).isEqualTo(-1); // re-resolve every build
        assertThat(GitFetcher.windowMillis("0")).isEqualTo(-1);
        assertThat(GitFetcher.windowMillis("45s")).isEqualTo(45_000L);
        assertThat(GitFetcher.windowMillis("30m")).isEqualTo(30L * 60_000);
        assertThat(GitFetcher.windowMillis("12h")).isEqualTo(12L * 3_600_000);
        assertThat(GitFetcher.windowMillis("3d")).isEqualTo(3L * 86_400_000L);
    }
}
