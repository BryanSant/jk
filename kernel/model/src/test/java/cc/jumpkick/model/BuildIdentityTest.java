// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildIdentityTest {

    @Test
    void snapshots_fold_the_build_id_in_releases_do_not() {
        assertThat(BuildIdentity.compose("1.0.0", "abc123def456")).isEqualTo("1.0.0");
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT", "abc123def456")).isEqualTo("1.0.0-SNAPSHOT+abc123def456");
        // No derivable identity -> the version-string rule stands (release behavior).
        assertThat(BuildIdentity.compose("1.0.0-SNAPSHOT", "")).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void unit_tests_run_from_a_classes_dir_and_get_no_jar_identity() {
        assertThat(BuildIdentity.buildId()).isEmpty();
        assertThat(BuildIdentity.cacheKeyVersion()).isEqualTo(JkVersion.VERSION);
    }
}
