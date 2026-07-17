// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitVersionTest {

    private static final Instant T = Instant.parse("2026-06-01T13:47:52Z");

    @Test
    void tag_coercion() {
        assertThat(GitVersion.fromTag("v1.2.3")).isEqualTo("1.2.3");
        assertThat(GitVersion.fromTag("1.2.3")).isEqualTo("1.2.3");
        assertThat(GitVersion.fromTag("1.2")).isEqualTo("1.2.0");
        assertThat(GitVersion.fromTag("v2")).isEqualTo("2.0.0");
        assertThat(GitVersion.fromTag("release-1.4.0")).isEqualTo("1.4.0");
        assertThat(GitVersion.fromTag("widgets-1.2.3")).isEqualTo("1.2.3");
        assertThat(GitVersion.fromTag("1.2.3-rc1")).isEqualTo("1.2.3-rc1");
        assertThat(GitVersion.fromTag("v1.2.3+build.5")).isEqualTo("1.2.3+build.5");
        assertThat(GitVersion.fromTag("v01.02.03")).isEqualTo("1.2.3"); // leading zeros normalized
        assertThat(GitVersion.fromTag("r6.1.0")).isEqualTo("6.1.0");
        assertThat(GitVersion.fromTag("r6.1.11")).isEqualTo("6.1.11");
    }

    @Test
    void tag_coercion_falls_back_to_raw_when_not_versionlike() {
        assertThat(GitVersion.fromTag("nightly")).isEqualTo("nightly");
        assertThat(GitVersion.fromTag("latest")).isEqualTo("latest");
    }

    @Test
    void branch_becomes_snapshot() {
        assertThat(GitVersion.forBranch("main")).isEqualTo("main-SNAPSHOT");
        assertThat(GitVersion.forBranch("feature/x")).isEqualTo("feature-x-SNAPSHOT");
    }

    private static final String SHA = "3f2a9c1b4d5e";

    @Test
    void pseudo_attaches_timestamp_and_sha_to_the_nearest_tag() {
        assertThat(GitVersion.pseudo(Optional.of("v1.2.3"), T, SHA)).isEqualTo("1.2.3-20260601.134752-3f2a9c1b4d5e");
    }

    @Test
    void pseudo_from_prerelease_tag_keeps_the_prerelease_base() {
        assertThat(GitVersion.pseudo(Optional.of("v1.2.4-rc1"), T, SHA))
                .isEqualTo("1.2.4-rc1-20260601.134752-3f2a9c1b4d5e");
    }

    @Test
    void pseudo_without_tag_uses_zero() {
        assertThat(GitVersion.pseudo(Optional.empty(), T, SHA)).isEqualTo("0.0.0-20260601.134752-3f2a9c1b4d5e");
    }
}
