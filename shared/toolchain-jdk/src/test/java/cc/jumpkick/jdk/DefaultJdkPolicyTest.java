// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultJdkPolicyTest {

    private static final int LATEST_LTS = 25;

    @Test
    void empty_when_nothing_installed() {
        assertThat(DefaultJdkPolicy.choose(List.of(), LATEST_LTS)).isEmpty();
    }

    @Test
    void single_install_is_the_default() {
        JdkHit only = hit("17.0.13", JdkVendor.TEMURIN);
        assertThat(DefaultJdkPolicy.choose(List.of(only), LATEST_LTS)).contains(only);
    }

    @Test
    void picks_latest_when_the_latest_lts_is_not_installed() {
        // {17,21,26}: latest LTS (25) absent → newest installed = 26.
        JdkHit j17 = hit("17.0.13", JdkVendor.TEMURIN);
        JdkHit j21 = hit("21.0.5", JdkVendor.TEMURIN);
        JdkHit j26 = hit("26.0.1", JdkVendor.TEMURIN);
        assertThat(DefaultJdkPolicy.choose(List.of(j17, j21, j26), LATEST_LTS)).contains(j26);
    }

    @Test
    void prefers_the_latest_lts_over_a_newer_non_lts() {
        // {24,25,26}: latest LTS (25) installed → 25, even though 26 is newer.
        JdkHit j24 = hit("24.0.2", JdkVendor.TEMURIN);
        JdkHit j25 = hit("25.0.3", JdkVendor.TEMURIN);
        JdkHit j26 = hit("26.0.1", JdkVendor.TEMURIN);
        assertThat(DefaultJdkPolicy.choose(List.of(j24, j25, j26), LATEST_LTS)).contains(j25);
    }

    @Test
    void vendor_preference_breaks_ties_at_the_same_version() {
        JdkHit corretto = hit("25.0.3", JdkVendor.CORRETTO);
        JdkHit temurin = hit("25.0.3", JdkVendor.TEMURIN);
        assertThat(DefaultJdkPolicy.choose(List.of(corretto, temurin), LATEST_LTS))
                .contains(temurin);
    }

    private static JdkHit hit(String version, JdkVendor vendor) {
        return new JdkHit(Path.of("/jdks/" + vendor.name().toLowerCase() + "-" + version), version, vendor, "jk");
    }
}
