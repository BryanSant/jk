// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
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

    // --- offerableMajors: the two-track `jk new` language-level list --------------------------

    @Test
    void isNativeImageCapable_matches_graalvm_sdk_names_only() {
        assertThat(SupportedJdk.isNativeImageCapable(entry("Oracle", "GraalVM", "graalvm-jdk-26", 26)))
                .isTrue();
        assertThat(SupportedJdk.isNativeImageCapable(entry("GraalVM", "Community Edition", "graalvm-ce-25", 25)))
                .isTrue();
        assertThat(SupportedJdk.isNativeImageCapable(entry("Eclipse", "Temurin", "temurin-26", 26)))
                .isFalse();
    }

    @Test
    void standard_track_includes_the_latest_non_lts_native_track_caps_at_graalvm() {
        // Temurin up to 26; GraalVM only up to 25 (the current shape of the real feed).
        JdkCatalog catalog = new JdkCatalog(new ArrayList<>(List.of(
                entry("Eclipse", "Temurin", "temurin-17", 17),
                entry("Eclipse", "Temurin", "temurin-21", 21),
                entry("Eclipse", "Temurin", "temurin-25", 25),
                entry("Eclipse", "Temurin", "temurin-26", 26),
                entry("Oracle", "GraalVM", "graalvm-jdk-17", 17),
                entry("Oracle", "GraalVM", "graalvm-jdk-21", 21),
                entry("Oracle", "GraalVM", "graalvm-jdk-25", 25))));
        assertThat(SupportedJdk.offerableMajors(catalog, false, "linux", "x86_64"))
                .containsExactly(25, 21, 17, 26);
        assertThat(SupportedJdk.offerableMajors(catalog, true, "linux", "x86_64"))
                .containsExactly(25, 21, 17);
    }

    @Test
    void native_track_gains_a_new_major_as_soon_as_a_graalvm_publishes_it() {
        // The moment GraalVM 26 appears in the feed, the native track offers it — no code change.
        JdkCatalog catalog = new JdkCatalog(new ArrayList<>(List.of(
                entry("Oracle", "GraalVM", "graalvm-jdk-17", 17),
                entry("Oracle", "GraalVM", "graalvm-jdk-21", 21),
                entry("Oracle", "GraalVM", "graalvm-jdk-25", 25),
                entry("Oracle", "GraalVM", "graalvm-jdk-26", 26))));
        assertThat(SupportedJdk.offerableMajors(catalog, true, "linux", "x86_64"))
                .containsExactly(25, 21, 17, 26);
    }

    @Test
    void offerable_majors_respect_host_os_arch_and_skip_previews() {
        JdkCatalog catalog = new JdkCatalog(new ArrayList<>(List.of(
                entry("Eclipse", "Temurin", "temurin-25", 25, false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", "temurin-26", 26, true, "linux", "x86_64"), // preview → excluded
                entry("Eclipse", "Temurin", "temurin-26", 26, false, "macOS", "aarch64")))); // other host → excluded
        // Only 25 is published for the host (26 is preview or a different platform), so only 25 is offered.
        assertThat(SupportedJdk.offerableMajors(catalog, false, "linux", "x86_64"))
                .containsExactly(25);
    }

    @Test
    void empty_or_null_catalog_yields_empty_so_caller_falls_back_offline() {
        assertThat(SupportedJdk.offerableMajors(new JdkCatalog(List.of()), false, "linux", "x86_64"))
                .isEmpty();
        assertThat(SupportedJdk.offerableMajors(null, true, "linux", "x86_64")).isEmpty();
    }

    private static JdkCatalog.Entry entry(String vendor, String product, String sdkName, int major) {
        return entry(vendor, product, sdkName, major, false, "linux", "x86_64");
    }

    private static JdkCatalog.Entry entry(
            String vendor, String product, String sdkName, int major, boolean preview, String os, String arch) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                sdkName,
                major,
                major + ".0.0",
                true,
                preview,
                List.of(sdkName, String.valueOf(major)),
                os,
                arch,
                "targz",
                URI.create("https://example.invalid/" + sdkName + ".tar.gz"),
                "00".repeat(32),
                1234L,
                sdkName,
                "");
    }
}
