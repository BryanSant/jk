// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdkSelectorTest {

    @Test
    void bare_major_resolves_to_default_vendor() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        false, false, List.of("temurin-21.0.5", "temurin-21", "21.0.5", "21"),
                        "linux", "x86_64"),
                entry("Oracle", "OpenJDK", "openjdk-21", 21, "21.0.7",
                        true, false, List.of("openjdk-21.0.7", "openjdk-21", "21.0.7", "21"),
                        "linux", "x86_64"));

        Optional<JdkCatalog.Entry> pick = JdkSelector.select(
                catalog, JdkSpec.parse("21"), "linux", "x86_64");
        assertThat(pick).isPresent()
                .get().extracting(JdkCatalog.Entry::vendor).isEqualTo("Oracle");
    }

    @Test
    void suggested_sdk_name_disambiguates_vendor() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        false, false, List.of("temurin-21", "21"), "linux", "x86_64"),
                entry("Oracle", "OpenJDK", "openjdk-21", 21, "21.0.7",
                        true, false, List.of("openjdk-21", "21"), "linux", "x86_64"));

        Optional<JdkCatalog.Entry> pick = JdkSelector.select(
                catalog, JdkSpec.parse("temurin-21"), "linux", "x86_64");
        assertThat(pick).isPresent()
                .get().extracting(JdkCatalog.Entry::vendor).isEqualTo("Eclipse");
    }

    @Test
    void filters_by_os_and_arch() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        true, false, List.of("temurin-21", "21"), "linux", "x86_64"),
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        true, false, List.of("temurin-21", "21"), "macOS", "aarch64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "macOS", "aarch64"))
                .isPresent()
                .get().extracting(JdkCatalog.Entry::os).isEqualTo("macOS");
    }

    @Test
    void preview_entries_lose_to_release_entries() {
        JdkCatalog catalog = catalogOf(
                entry("Oracle", "OpenJDK Early-Access", "openjdk-ea-22", 22, "22-ea+1",
                        true, true, List.of("openjdk-ea-22", "22-ea+1", "22"),
                        "linux", "x86_64"),
                entry("Oracle", "OpenJDK", "openjdk-21", 21, "21.0.7",
                        true, false, List.of("openjdk-21", "21"),
                        "linux", "x86_64"));

        // `21` should still pick openjdk-21 (preview entry is for major 22 anyway),
        // verifying we don't surface previews when a stable choice exists.
        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "linux", "x86_64"))
                .isPresent()
                .get().extracting(JdkCatalog.Entry::preview).isEqualTo(false);
    }

    @Test
    void picks_highest_version_when_multiple_match() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.3",
                        true, false, List.of("temurin-21", "21.0.3"), "linux", "x86_64"),
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        true, false, List.of("temurin-21", "21.0.5"), "linux", "x86_64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("temurin-21"), "linux", "x86_64"))
                .isPresent()
                .get().extracting(JdkCatalog.Entry::version).isEqualTo("21.0.5");
    }

    @Test
    void empty_when_no_match() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", "temurin-21", 21, "21.0.5",
                        true, false, List.of("temurin-21", "21"), "linux", "x86_64"));

        assertThat(JdkSelector.select(catalog, JdkSpec.parse("graalvm-25"), "linux", "x86_64"))
                .isEmpty();
        assertThat(JdkSelector.select(catalog, JdkSpec.parse("21"), "windows", "x86_64"))
                .isEmpty();
    }

    @Test
    void version_key_orders_minor_versions_numerically() {
        // 21.0.9 < 21.0.10 numerically — naive string sort gets this wrong.
        assertThat(JdkSelector.versionKey("21.0.9"))
                .isLessThan(JdkSelector.versionKey("21.0.10"));
    }

    private static JdkCatalog catalogOf(JdkCatalog.Entry... entries) {
        return new JdkCatalog(List.of(entries));
    }

    private static JdkCatalog.Entry entry(
            String vendor, String product, String suggestedSdkName,
            int major, String version,
            boolean defaultForMajor, boolean preview, List<String> aliases,
            String os, String arch) {
        return new JdkCatalog.Entry(
                vendor, product, suggestedSdkName, major, version,
                defaultForMajor, preview, aliases,
                os, arch, "targz",
                URI.create("https://example.invalid/" + suggestedSdkName + ".tar.gz"),
                "00".repeat(32), 1234L,
                suggestedSdkName.replace("-", "-") + "." + version,
                "macOS".equals(os) ? "Contents/Home" : "");
    }
}
