// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdkSelectorTest {

    @Test
    void bare_major_resolves_to_default_vendor() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        false,
                        false,
                        List.of("temurin-21.0.5", "temurin-21", "21.0.5", "21"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-21",
                        21,
                        "21.0.7",
                        true,
                        false,
                        List.of("openjdk-21.0.7", "openjdk-21", "21.0.7", "21"),
                        "linux",
                        "x86_64"));

        Optional<JdkCatalog.Entry> pick = JdkSelector.select(catalog, JdkSpec.parse("21"), "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::vendor).isEqualTo("Oracle");
    }

    @Test
    void suggested_sdk_name_disambiguates_vendor() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        false,
                        false,
                        List.of("temurin-21", "21"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-21",
                        21,
                        "21.0.7",
                        true,
                        false,
                        List.of("openjdk-21", "21"),
                        "linux",
                        "x86_64"));

        Optional<JdkCatalog.Entry> pick = JdkSelector.select(catalog, JdkSpec.parse("temurin-21"), "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::vendor).isEqualTo("Eclipse");
    }

    @Test
    void filters_by_os_and_arch() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        true,
                        false,
                        List.of("temurin-21", "21"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        true,
                        false,
                        List.of("temurin-21", "21"),
                        "macOS",
                        "aarch64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "macOS", "aarch64"))
                .isPresent()
                .get()
                .extracting(JdkCatalog.Entry::os)
                .isEqualTo("macOS");
    }

    @Test
    void preview_entries_lose_to_release_entries() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Oracle",
                        "OpenJDK Early-Access",
                        "openjdk-ea-22",
                        22,
                        "22-ea+1",
                        true,
                        true,
                        List.of("openjdk-ea-22", "22-ea+1", "22"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-21",
                        21,
                        "21.0.7",
                        true,
                        false,
                        List.of("openjdk-21", "21"),
                        "linux",
                        "x86_64"));

        // `21` should still pick openjdk-21 (preview entry is for major 22 anyway),
        // verifying we don't surface previews when a stable choice exists.
        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "linux", "x86_64"))
                .isPresent()
                .get()
                .extracting(JdkCatalog.Entry::preview)
                .isEqualTo(false);
    }

    @Test
    void picks_highest_version_when_multiple_match() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.3",
                        true,
                        false,
                        List.of("temurin-21", "21.0.3"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        true,
                        false,
                        List.of("temurin-21", "21.0.5"),
                        "linux",
                        "x86_64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("temurin-21"), "linux", "x86_64"))
                .isPresent()
                .get()
                .extracting(JdkCatalog.Entry::version)
                .isEqualTo("21.0.5");
    }

    @Test
    void empty_when_no_match() {
        JdkCatalog catalog = catalogOf(entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of("temurin-21", "21"),
                "linux",
                "x86_64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("graalvm-25"), "linux", "x86_64"))
                .isEmpty();
        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "windows", "x86_64"))
                .isEmpty();
    }

    @Test
    void version_key_orders_minor_versions_numerically() {
        // 21.0.9 < 21.0.10 numerically — naive string sort gets this wrong.
        assertThat(JdkSelector.versionKey("21.0.9")).isLessThan(JdkSelector.versionKey("21.0.10"));
    }

    // -- Flexible selector ---------------------------------------------------

    @Test
    void parse_flexible_extracts_major_and_hints() {
        var q = JdkSelector.parseFlexible("25-graal");
        assertThat(q.major()).contains(25);
        assertThat(q.hints()).containsExactly("graal");
        assertThat(q.exactVersion()).isEmpty();
    }

    @Test
    void parse_flexible_drops_java_noise_word() {
        var q = JdkSelector.parseFlexible("java-17-openjdk");
        assertThat(q.major()).contains(17);
        assertThat(q.hints()).containsExactly("openjdk");
    }

    @Test
    void parse_flexible_captures_dotted_version() {
        var q = JdkSelector.parseFlexible("17.0.19");
        assertThat(q.major()).contains(17);
        assertThat(q.exactVersion()).contains("17.0.19");
        assertThat(q.hints()).isEmpty();
    }

    @Test
    void parse_flexible_handles_vendor_prefix() {
        var q = JdkSelector.parseFlexible("temurin-25");
        assertThat(q.major()).contains(25);
        assertThat(q.hints()).containsExactly("temurin");
    }

    @Test
    void parse_flexible_handles_dropped_jdk_token() {
        var q = JdkSelector.parseFlexible("jdk-21");
        assertThat(q.major()).contains(21);
        assertThat(q.hints()).isEmpty();
    }

    @Test
    void select_flexible_finds_graal_at_major() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-25",
                        25,
                        "25.0.3",
                        true,
                        false,
                        List.of("temurin-25.0.3", "temurin-25", "25.0.3", "25"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "GraalVM",
                        "graalvm-25",
                        25,
                        "25.0.3",
                        false,
                        false,
                        List.of("graalvm-25.0.3", "graalvm-25", "25.0.3"),
                        "linux",
                        "x86_64"));

        var pick = JdkSelector.selectFlexible(catalog, "25-graal", "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::product).isEqualTo("GraalVM");
    }

    @Test
    void select_flexible_finds_openjdk_via_linux_package_form() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-17",
                        17,
                        "17.0.13",
                        false,
                        false,
                        List.of("temurin-17", "17"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-17",
                        17,
                        "17.0.7",
                        true,
                        false,
                        List.of("openjdk-17", "17"),
                        "linux",
                        "x86_64"));

        var pick = JdkSelector.selectFlexible(catalog, "java-17-openjdk", "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::product).isEqualTo("OpenJDK");
    }

    @Test
    void select_flexible_falls_through_from_strict_select() {
        // 25-graal isn't in any alias list. Strict select returns empty, but
        // the public select() should now find the graal entry anyway.
        JdkCatalog catalog = catalogOf(entry(
                "Oracle",
                "GraalVM",
                "graalvm-25",
                25,
                "25.0.3",
                false,
                false,
                List.of("graalvm-25.0.3", "graalvm-25", "25.0.3", "25"),
                "linux",
                "x86_64"));

        var pick = JdkSelector.select(catalog, JdkSpec.parse("25-graal"), "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::product).isEqualTo("GraalVM");
    }

    @Test
    void select_flexible_picks_highest_version_when_hints_tied() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-25-old",
                        25,
                        "25.0.1",
                        false,
                        false,
                        List.of("temurin-25"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-25-new",
                        25,
                        "25.0.3",
                        false,
                        false,
                        List.of("temurin-25"),
                        "linux",
                        "x86_64"));

        var pick = JdkSelector.selectFlexible(catalog, "temurin-25", "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::version).isEqualTo("25.0.3");
    }

    @Test
    void select_flexible_bare_graalvm_hint_prefers_oracle_over_community_edition() {
        // Both vendors' feed metadata contain "graalvm" (Oracle: vendor "Oracle" /
        // product "GraalVM"; CE: vendor "GraalVM" / product "Community Edition"), so
        // the hint alone ties them. CE also publishes a more specific version string
        // (25.0.2 vs Oracle's bare "25"), which would otherwise win the version
        // tie-break — vendor preference must be checked first.
        JdkCatalog catalog = catalogOf(
                entry(
                        "GraalVM",
                        "Community Edition",
                        "graalvm-ce-25",
                        25,
                        "25.0.2",
                        false,
                        false,
                        List.of("graalvm-ce-25.0.2", "graalvm-ce-25"),
                        "linux",
                        "x86_64"),
                entry(
                        "Oracle",
                        "GraalVM",
                        "graalvm-jdk-25",
                        25,
                        "25",
                        false,
                        false,
                        List.of("graalvm-jdk-25"),
                        "linux",
                        "x86_64"));

        var pick = JdkSelector.selectFlexible(catalog, "graalvm", "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::vendor).isEqualTo("Oracle");
    }

    @Test
    void select_flexible_returns_empty_when_no_match() {
        JdkCatalog catalog = catalogOf(entry(
                "Eclipse",
                "Temurin",
                "temurin-21",
                21,
                "21.0.5",
                true,
                false,
                List.of("temurin-21"),
                "linux",
                "x86_64"));
        // Wrong major → no entries with majorVersion=99.
        assertThat(JdkSelector.selectFlexible(catalog, "99", "linux", "x86_64")).isEmpty();
    }

    @Test
    void select_flexible_filters_by_exact_version_prefix() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-17", 17, "17.0.19", true, false, List.of("17"), "linux", "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-17-older",
                        17,
                        "17.0.13",
                        false,
                        false,
                        List.of("17"),
                        "linux",
                        "x86_64"));

        var pick = JdkSelector.selectFlexible(catalog, "17.0.19", "linux", "x86_64");
        assertThat(pick).isPresent().get().extracting(JdkCatalog.Entry::version).isEqualTo("17.0.19");
    }

    @Test
    void select_preferred_biases_to_temurin_over_feed_default() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-26",
                        26,
                        "26.0.1",
                        true,
                        false,
                        List.of("openjdk-26", "26", "26.0.1"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-26",
                        26,
                        "26.0.1",
                        false,
                        false,
                        List.of("temurin-26", "26", "26.0.1"),
                        "linux",
                        "x86_64"));

        // Plain select honors the feed's default-for-major (Oracle)…
        assertThat(JdkSelector.select(catalog, JdkSpec.parse("26"), "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("Oracle");
        // …selectPreferred prefers Temurin when no vendor was named.
        assertThat(JdkSelector.selectPreferred(catalog, "26", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("Eclipse");
    }

    @Test
    void select_preferred_full_version_prefers_temurin() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Amazon",
                        "Corretto",
                        "corretto-25",
                        25,
                        "25.0.3",
                        true,
                        false,
                        List.of("corretto-25", "25.0.3", "25"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-25",
                        25,
                        "25.0.3",
                        false,
                        false,
                        List.of("temurin-25", "temurin-25.0.3", "25.0.3", "25"),
                        "linux",
                        "x86_64"));

        assertThat(JdkSelector.selectPreferred(catalog, "25.0.3", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("Eclipse");
    }

    @Test
    void select_preferred_respects_an_explicit_vendor() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Oracle",
                        "OpenJDK",
                        "openjdk-26",
                        26,
                        "26.0.1",
                        true,
                        false,
                        List.of("openjdk-26", "26"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-26",
                        26,
                        "26.0.1",
                        false,
                        false,
                        List.of("temurin-26", "26"),
                        "linux",
                        "x86_64"));

        // The user named OpenJDK — no bias is applied.
        assertThat(JdkSelector.selectPreferred(catalog, "openjdk-26", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("Oracle");
    }

    @Test
    void select_preferred_falls_back_when_no_temurin_for_major() {
        JdkCatalog catalog = catalogOf(entry(
                "Oracle",
                "OpenJDK",
                "openjdk-26",
                26,
                "26.0.1",
                true,
                false,
                List.of("openjdk-26", "26"),
                "linux",
                "x86_64"));

        // No Temurin published for 26 on this host → fall back to the feed default.
        assertThat(JdkSelector.selectPreferred(catalog, "26", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("Oracle");
    }

    // -- Range operators (>N / >=N) -----------------------------------------

    @Test
    void parse_flexible_extracts_lower_bound() {
        var inclusive = JdkSelector.parseFlexible(">=21");
        assertThat(inclusive.lowerBound()).isPresent();
        assertThat(inclusive.lowerBound().get().major()).isEqualTo(21);
        assertThat(inclusive.lowerBound().get().inclusive()).isTrue();
        assertThat(inclusive.major()).isEmpty();

        var exclusive = JdkSelector.parseFlexible(">25");
        assertThat(exclusive.lowerBound().get().major()).isEqualTo(25);
        assertThat(exclusive.lowerBound().get().inclusive()).isFalse();

        var vendored = JdkSelector.parseFlexible("temurin->=21");
        assertThat(vendored.lowerBound().get().major()).isEqualTo(21);
        assertThat(vendored.hints()).containsExactly("temurin");
    }

    @Test
    void range_resolves_to_lowest_satisfying_major() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-17",
                        17,
                        "17.0.13",
                        true,
                        false,
                        List.of("temurin-17", "17"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-21",
                        21,
                        "21.0.5",
                        true,
                        false,
                        List.of("temurin-21", "21"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-25",
                        25,
                        "25.0.3",
                        true,
                        false,
                        List.of("temurin-25", "25"),
                        "linux",
                        "x86_64"),
                entry(
                        "Eclipse",
                        "Temurin",
                        "temurin-26",
                        26,
                        "26.0.1",
                        true,
                        false,
                        List.of("temurin-26", "26"),
                        "linux",
                        "x86_64"));

        // >=21 → the lowest major at-or-above 21 (i.e. 21), not the newest.
        assertThat(JdkSelector.select(catalog, JdkSpec.parse(">=21"), "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::majorVersion)
                .isEqualTo(21);
        // >25 (exclusive) → 26.
        assertThat(JdkSelector.selectPreferred(catalog, ">25", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::majorVersion)
                .isEqualTo(26);
    }

    @Test
    void range_ties_break_on_vendor_preference() {
        // Two vendors at the lowest satisfying major (21): Liberica should beat
        // Corretto per JdkVendor.PREFERENCE.
        JdkCatalog catalog = catalogOf(
                entry(
                        "Amazon",
                        "Corretto",
                        "corretto-21",
                        21,
                        "21.0.5",
                        true,
                        false,
                        List.of("corretto-21", "21"),
                        "linux",
                        "x86_64"),
                entry(
                        "BellSoft",
                        "Liberica",
                        "liberica-21",
                        21,
                        "21.0.5",
                        false,
                        false,
                        List.of("liberica-21", "21"),
                        "linux",
                        "x86_64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse(">=21"), "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("BellSoft");
    }

    // -- Vendor preference order --------------------------------------------

    @Test
    void select_preferred_prefers_liberica_over_corretto_when_no_temurin() {
        JdkCatalog catalog = catalogOf(
                entry(
                        "Amazon",
                        "Corretto",
                        "corretto-25",
                        25,
                        "25.0.3",
                        true,
                        false,
                        List.of("corretto-25", "25"),
                        "linux",
                        "x86_64"),
                entry(
                        "BellSoft",
                        "Liberica",
                        "liberica-25",
                        25,
                        "25.0.3",
                        false,
                        false,
                        List.of("liberica-25", "25"),
                        "linux",
                        "x86_64"));

        // No Temurin for 25 → next in JdkVendor.PREFERENCE that matches is Liberica.
        assertThat(JdkSelector.selectPreferred(catalog, "25", "linux", "x86_64"))
                .get()
                .extracting(JdkCatalog.Entry::vendor)
                .isEqualTo("BellSoft");
    }

    private static JdkCatalog catalogOf(JdkCatalog.Entry... entries) {
        return new JdkCatalog(List.of(entries));
    }

    private static JdkCatalog.Entry entry(
            String vendor,
            String product,
            String suggestedSdkName,
            int major,
            String version,
            boolean defaultForMajor,
            boolean preview,
            List<String> aliases,
            String os,
            String arch) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                suggestedSdkName,
                major,
                version,
                defaultForMajor,
                preview,
                aliases,
                os,
                arch,
                "targz",
                URI.create("https://example.invalid/" + suggestedSdkName + ".tar.gz"),
                "00".repeat(32),
                1234L,
                suggestedSdkName.replace("-", "-") + "." + version,
                "macOS".equals(os) ? "Contents/Home" : "");
    }
}
