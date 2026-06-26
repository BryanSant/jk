// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Orientation;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.jdk.JdkCatalog;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JdkInstallWizardTest {

    @Test
    void show_all_returns_every_supported_vendor_filtered_to_host() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", 21, "21.0.5", false, "linux", "x86_64"),
                entry("Oracle", "OpenJDK", 25, "25.0.1", false, "linux", "x86_64"),
                entry("Oracle", "GraalVM", 25, "25", false, "linux", "x86_64"),
                // Wrong os/arch — must be filtered out.
                entry("BellSoft", "Liberica JDK", 21, "21.0.5", false, "macOS", "aarch64"),
                // Preview — must be filtered out.
                entry("Oracle", "OpenJDK Early-Access", 26, "26-ea+1", true, "linux", "x86_64"),
                // Unsupported major — must be filtered out.
                entry("Eclipse", "Temurin", 11, "11.0.20", false, "linux", "x86_64"));

        List<JdkInstallWizard.VendorOption> vendors = JdkInstallWizard.vendorsFor(catalog, "linux", "x86_64", true);
        assertThat(vendors)
                .extracting(JdkInstallWizard.VendorOption::label)
                .containsExactly("Eclipse Temurin", "Oracle GraalVM", "Oracle OpenJDK");
        assertThat(vendors)
                .extracting(JdkInstallWizard.VendorOption::id)
                .containsExactly("Eclipse|Temurin", "Oracle|GraalVM", "Oracle|OpenJDK");
    }

    @Test
    void default_vendor_list_is_curated_set_in_fixed_order() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", 25, "25.0.1", false, "linux", "x86_64"),
                entry("Oracle", "GraalVM", 25, "25", false, "linux", "x86_64"),
                entry("Amazon", "Corretto", 25, "25.0.1", false, "linux", "x86_64"),
                entry("BellSoft", "Liberica JDK", 21, "21.0.5", false, "linux", "x86_64"),
                // Non-curated — surfaced only with --show-all.
                entry("Oracle", "OpenJDK", 25, "25.0.1", false, "linux", "x86_64"),
                entry("Azul", "Zulu Community™", 25, "25", false, "linux", "x86_64"));

        List<JdkInstallWizard.VendorOption> curated = JdkInstallWizard.vendorsFor(catalog, "linux", "x86_64", false);
        assertThat(curated)
                .extracting(JdkInstallWizard.VendorOption::label)
                .containsExactly("Eclipse Temurin", "Oracle GraalVM", "Amazon Corretto", "BellSoft Liberica JDK");

        List<JdkInstallWizard.VendorOption> all = JdkInstallWizard.vendorsFor(catalog, "linux", "x86_64", true);
        assertThat(all)
                .extracting(JdkInstallWizard.VendorOption::label)
                .contains("Oracle OpenJDK", "Azul Zulu Community™");
    }

    @Test
    void default_vendor_list_skips_curated_entries_missing_from_catalog() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", 25, "25.0.1", false, "linux", "x86_64"),
                entry("Amazon", "Corretto", 25, "25.0.1", false, "linux", "x86_64"));

        List<JdkInstallWizard.VendorOption> curated = JdkInstallWizard.vendorsFor(catalog, "linux", "x86_64", false);
        assertThat(curated)
                .extracting(JdkInstallWizard.VendorOption::label)
                .containsExactly("Eclipse Temurin", "Amazon Corretto");
    }

    @Test
    void vendor_option_tracks_install_folder_per_supported_major() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", 17, "17.0.10", false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", 21, "21.0.5", false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", 25, "25.0.1", false, "linux", "x86_64"));

        List<JdkInstallWizard.VendorOption> vendors = JdkInstallWizard.vendorsFor(catalog, "linux", "x86_64", false);
        assertThat(vendors).singleElement().satisfies(v -> {
            assertThat(v.hintFor(17)).isEqualTo("temurin-17.0.10");
            assertThat(v.hintFor(21)).isEqualTo("temurin-21.0.5");
            assertThat(v.hintFor(25)).isEqualTo("temurin-25.0.1");
        });
    }

    @Test
    void vendor_choice_hint_reflects_selected_version_at_render_time() {
        var temurin = vendorOption(
                "Eclipse",
                "Temurin",
                Map.of(
                        17, "temurin-17.0.10",
                        21, "temurin-21.0.5",
                        25, "temurin-25.0.1"));
        var graalvm = vendorOption(
                "Oracle",
                "GraalVM",
                Map.of(
                        17, "graalvm-jdk-17.0.12",
                        21, "graalvm-jdk-21.0.5",
                        25, "graalvm-jdk-25"));
        Wizard w = JdkInstallWizard.buildWizard(List.of(25, 21, 17), List.of(temurin, graalvm), "Eclipse|Temurin");
        WizardStep.RadioStep vendor = (WizardStep.RadioStep) w.steps().get(1);

        // User picks "17" → hints update to the 17 line.
        assertThat(vendor.choices().get(0).hintFor(Answers.of(Map.of("version", "17"))))
                .isEqualTo("temurin-17.0.10");
        assertThat(vendor.choices().get(1).hintFor(Answers.of(Map.of("version", "17"))))
                .isEqualTo("graalvm-jdk-17.0.12");

        // User picks "25" → hints update to the 25 line.
        assertThat(vendor.choices().get(0).hintFor(Answers.of(Map.of("version", "25"))))
                .isEqualTo("temurin-25.0.1");
        assertThat(vendor.choices().get(1).hintFor(Answers.of(Map.of("version", "25"))))
                .isEqualTo("graalvm-jdk-25");
    }

    @Test
    void vendor_default_prefers_temurin() {
        var azul = vendorOption("Azul", "Zulu", Map.of(25, "zulu-25"));
        var temurin = vendorOption("Eclipse", "Temurin", Map.of(25, "temurin-25"));
        var openjdk = vendorOption("Oracle", "OpenJDK", Map.of(25, "openjdk-26"));
        var corretto = vendorOption("Amazon", "Corretto", Map.of(25, "corretto-25"));

        assertThat(JdkInstallWizard.pickVendorDefault(List.of(azul, temurin, openjdk)))
                .isEqualTo("Eclipse|Temurin");
        // Falls back to the first when no Temurin option present.
        assertThat(JdkInstallWizard.pickVendorDefault(List.of(corretto, azul))).isEqualTo("Amazon|Corretto");
    }

    @Test
    void pick_latest_returns_highest_version_for_vendor_major_pair() {
        JdkCatalog catalog = catalogOf(
                entry("Eclipse", "Temurin", 21, "21.0.3", false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", 21, "21.0.5", false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", 21, "21.0.9", false, "linux", "x86_64"),
                entry("Eclipse", "Temurin", 25, "25.0.1", false, "linux", "x86_64"));

        Optional<JdkCatalog.Entry> e = JdkInstallWizard.pickLatest(catalog, "linux", "x86_64", "Eclipse|Temurin", 21);
        assertThat(e).isPresent().get().extracting(JdkCatalog.Entry::version).isEqualTo("21.0.9");
    }

    @Test
    void pick_latest_disambiguates_by_product_under_same_vendor() {
        JdkCatalog catalog = catalogOf(
                entry("Oracle", "OpenJDK", 25, "25.0.1", false, "linux", "x86_64"),
                entry("Oracle", "GraalVM", 25, "25", false, "linux", "x86_64"));

        assertThat(JdkInstallWizard.pickLatest(catalog, "linux", "x86_64", "Oracle|GraalVM", 25))
                .isPresent()
                .get()
                .extracting(JdkCatalog.Entry::product)
                .isEqualTo("GraalVM");
        assertThat(JdkInstallWizard.pickLatest(catalog, "linux", "x86_64", "Oracle|OpenJDK", 25))
                .isPresent()
                .get()
                .extracting(JdkCatalog.Entry::product)
                .isEqualTo("OpenJDK");
    }

    @Test
    void pick_latest_empty_when_no_match() {
        JdkCatalog catalog = catalogOf(entry("Eclipse", "Temurin", 21, "21.0.5", false, "linux", "x86_64"));
        assertThat(JdkInstallWizard.pickLatest(catalog, "linux", "x86_64", "Eclipse|Temurin", 25))
                .isEmpty();
    }

    @Test
    void wizard_builds_three_steps_with_expected_shape() {
        var temurin = vendorOption("Eclipse", "Temurin", Map.of(25, "temurin-25.0.1"));
        var openjdk = vendorOption("Oracle", "OpenJDK", Map.of(25, "openjdk-26"));
        Wizard w = JdkInstallWizard.buildWizard(List.of(25, 21, 17), List.of(temurin, openjdk), "Eclipse|Temurin");
        List<WizardStep> steps = w.steps();
        assertThat(steps).hasSize(3);

        WizardStep.RadioStep version = (WizardStep.RadioStep) steps.get(0);
        assertThat(version.key()).isEqualTo("version");
        assertThat(version.orientation()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(version.choices()).extracting("id").containsExactly("25", "21", "17");
        assertThat(version.defaultChoice()).isEqualTo("25");

        WizardStep.RadioStep vendor = (WizardStep.RadioStep) steps.get(1);
        assertThat(vendor.key()).isEqualTo("vendor");
        assertThat(vendor.orientation()).isEqualTo(Orientation.VERTICAL);
        assertThat(vendor.choices()).extracting("id").containsExactly("Eclipse|Temurin", "Oracle|OpenJDK");
        assertThat(vendor.choices()).extracting("label").containsExactly("Eclipse Temurin", "Oracle OpenJDK");
        assertThat(vendor.defaultChoice()).isEqualTo("Eclipse|Temurin");

        WizardStep.RadioStep makeDefault = (WizardStep.RadioStep) steps.get(2);
        assertThat(makeDefault.key()).isEqualTo("default");
        assertThat(makeDefault.orientation()).isEqualTo(Orientation.HORIZONTAL);
        assertThat(makeDefault.choices()).extracting("id").containsExactly("yes", "no");
        assertThat(makeDefault.defaultChoice()).isEqualTo("no");

        assertThat(w.title()).isEqualTo("Jk - Install a Java Development Kit");
    }

    private static JdkInstallWizard.VendorOption vendorOption(
            String vendor, String product, Map<Integer, String> installFolderByMajor) {
        return new JdkInstallWizard.VendorOption(
                vendor + "|" + product, vendor + " " + product, vendor, product, installFolderByMajor);
    }

    private static JdkCatalog catalogOf(JdkCatalog.Entry... entries) {
        return new JdkCatalog(List.of(entries));
    }

    private static JdkCatalog.Entry entry(
            String vendor, String product, int major, String version, boolean preview, String os, String arch) {
        String slug = product.toLowerCase().replace(' ', '-');
        return new JdkCatalog.Entry(
                vendor,
                product,
                slug + "-" + major,
                major,
                version,
                false,
                preview,
                List.of(),
                os,
                arch,
                "targz",
                URI.create("https://example.invalid/" + slug + "-" + version + ".tar.gz"),
                "00".repeat(32),
                1234L,
                slug + "-" + version,
                "macOS".equals(os) ? "Contents/Home" : "");
    }
}
