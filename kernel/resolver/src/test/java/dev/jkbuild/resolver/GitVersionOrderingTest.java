// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.model.GitVersion;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The load-bearing test for git-source version derivation: the derived
 * pseudo-versions must order correctly under jk's actual comparator
 * ({@link Versions#compare}, Maven {@code ComparableVersion}) — that's what the
 * resolver, lockfile dedup, and range checks use. If this fails, the
 * {@link GitVersion} format is wrong, not the test.
 */
class GitVersionOrderingTest {

    private static final String SHA = "3f2a9c1b4d5e";
    private static final Instant EARLY = Instant.parse("2026-06-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-01T13:47:52Z");

    private static void assertOrder(String lower, String higher) {
        assertThat(Versions.compare(lower, higher))
                .as("%s should sort below %s", lower, higher)
                .isLessThan(0);
    }

    @Test
    void pseudo_sits_between_the_base_tag_and_the_next_release() {
        String pseudo = GitVersion.pseudo(Optional.of("v1.2.3"), LATER, SHA); // 1.2.3-<ts>-<sha>
        assertOrder("1.2.3", pseudo);
        assertOrder(pseudo, "1.2.4");
    }

    @Test
    void pseudo_sorts_below_the_next_releases_pre_releases() {
        String pseudo = GitVersion.pseudo(Optional.of("v1.2.3"), LATER, SHA); // core 1.2.3
        assertOrder(pseudo, "1.2.4-rc1"); // lower core → below any 1.2.4 pre-release
    }

    @Test
    void prerelease_base_sorts_above_the_prerelease_below_the_release() {
        String pseudo = GitVersion.pseudo(Optional.of("v1.2.4-rc1"), LATER, SHA);
        assertOrder("1.2.4-rc1", pseudo);
        assertOrder(pseudo, "1.2.4");
    }

    @Test
    void pseudo_versions_sort_chronologically() {
        String early = GitVersion.pseudo(Optional.of("v1.2.3"), EARLY, SHA);
        String later = GitVersion.pseudo(Optional.of("v1.2.3"), LATER, SHA);
        assertOrder(early, later);
    }

    @Test
    void no_tag_pseudo_sorts_below_first_release() {
        String pseudo = GitVersion.pseudo(Optional.empty(), LATER, SHA); // 0.0.0-<ts>-<sha>
        assertOrder(pseudo, "0.0.1");
        assertOrder(pseudo, "0.1.0");
    }
}
