// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.jdk.JdkCatalog;
import build.jumpkick.jdk.JdkVendor;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class NewJdkCandidateTest {

    private static final int LTS = 25;
    private static final String OS = "linux";
    private static final String ARCH = "x86_64";

    @Test
    void filter_with_native_keeps_only_graalvm_at_lts() {
        var temurin25 = installed("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var graal25 = installable("graalvm-jdk-25.0.3", 25, "Oracle", "GraalVM");
        var graalCe25 = installable("graalvm-ce-25.0.3", 25, "GraalVM Community", "GraalVM CE");
        var temurin21 = installed("temurin-21.0.5", 21, JdkVendor.TEMURIN);
        var graal17 = installable("graalvm-jdk-17", 17, "Oracle", "GraalVM");

        var filtered = NewJdkCandidate.filter(List.of(temurin25, graal25, graalCe25, temurin21, graal17), true, LTS);

        assertThat(filtered)
                .extracting(NewJdkCandidate::id)
                .containsExactlyInAnyOrder("graalvm-jdk-25.0.3", "graalvm-ce-25.0.3");
    }

    @Test
    void filter_without_native_promotes_temurin_lts_to_top() {
        var corretto25 = installed("corretto-25.0.3", 25, JdkVendor.CORRETTO);
        var temurin25 = installed("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var graal25 = installable("graalvm-jdk-25.0.3", 25, "Oracle", "GraalVM");

        // Temurin sits in the middle of the input; filter should bubble it
        // to position 0 and leave the rest in their original order.
        var filtered = NewJdkCandidate.filter(List.of(corretto25, temurin25, graal25), false, LTS);

        assertThat(filtered)
                .extracting(NewJdkCandidate::id)
                .containsExactly("temurin-25.0.3", "corretto-25.0.3", "graalvm-jdk-25.0.3");
    }

    @Test
    void filter_without_native_uses_lts_temurin_not_a_newer_one() {
        // Temurin at a non-LTS major (e.g. 26) shouldn't get promoted —
        // we want the latest *LTS*, which here is 25.
        var temurin26 = installed("temurin-26.0.1", 26, JdkVendor.TEMURIN);
        var temurin25 = installable("temurin-25.0.3", 25, "Eclipse", "Temurin");
        var corretto25 = installed("corretto-25.0.3", 25, JdkVendor.CORRETTO);

        var filtered = NewJdkCandidate.filter(List.of(temurin26, corretto25, temurin25), false, LTS);

        assertThat(filtered.getFirst().id()).isEqualTo("temurin-25.0.3");
        assertThat(filtered.getFirst().major()).isEqualTo(LTS);
    }

    @Test
    void filter_without_native_passes_through_when_no_temurin_lts_present() {
        var corretto25 = installed("corretto-25.0.3", 25, JdkVendor.CORRETTO);
        var graal25 = installable("graalvm-jdk-25.0.3", 25, "Oracle", "GraalVM");
        var filtered = NewJdkCandidate.filter(List.of(corretto25, graal25), false, LTS);
        assertThat(filtered).extracting(NewJdkCandidate::id).containsExactly("corretto-25.0.3", "graalvm-jdk-25.0.3");
    }

    @Test
    void filter_native_with_no_graalvm_returns_empty() {
        var temurin25 = installed("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var corretto21 = installed("corretto-21", 21, JdkVendor.CORRETTO);

        assertThat(NewJdkCandidate.filter(List.of(temurin25, corretto21), true, LTS))
                .isEmpty();
    }

    @Test
    void build_with_no_installed_and_catalog_yields_lts_installable_rows() {
        var catalog = new JdkCatalog(List.of(
                entry("Eclipse", "Temurin", 25, "25.0.3"),
                entry("Oracle", "GraalVM", 25, "25.0.3"),
                entry("GraalVM Community", "GraalVM CE", 25, "25.0.3"),
                // Older majors / wrong os should be filtered out
                entry("Eclipse", "Temurin", 21, "21.0.5"),
                entryOnDifferentOs("Eclipse", "Temurin", 25, "25.0.3")));
        var fx = new Fixture();
        var candidates = fx.build(List.of(), Optional.of(catalog));

        assertThat(candidates).hasSize(3);
        assertThat(candidates).allMatch(c -> !c.installed());
        assertThat(candidates)
                .extracting(NewJdkCandidate::id)
                .contains("temurin-25.0.3", "graalvm-25.0.3", "graalvm-ce-25.0.3");
    }

    @Test
    void build_dedupes_installable_rows_already_on_disk() {
        // Same vendor-product-major appears both installed AND in the catalog.
        var fx = new Fixture();
        var temurinOpt = fx.option("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var catalog = new JdkCatalog(List.of(entry("Eclipse", "Temurin", 25, "25.0.3")));

        var candidates = fx.build(List.of(temurinOpt), Optional.of(catalog));

        // Only one entry for (Eclipse, Temurin, 25) — the installed one wins.
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().installed()).isTrue();
        assertThat(candidates.getFirst().id()).isEqualTo("temurin-25.0.3");
    }

    @Test
    void build_without_catalog_returns_installed_only() {
        var fx = new Fixture();
        var opt = fx.option("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var candidates = fx.build(List.of(opt), Optional.empty());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().installed()).isTrue();
    }

    @Test
    void hint_is_will_install_or_identifier() {
        var installable = installable("graalvm-jdk-25", 25, "Oracle", "GraalVM");
        var installedC = installed("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        assertThat(installable.hint()).isEqualTo("(will install)");
        // Installed rows surface their install id in dark gray after the label.
        assertThat(installedC.hint()).isEqualTo("(temurin-25.0.3)");
    }

    @Test
    void label_uses_vendor_product_major_format() {
        var temurin = installed("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var graal = installable("graalvm-25", 25, "Oracle", "GraalVM");
        assertThat(temurin.label()).isEqualTo("JDK 25 - Eclipse Temurin");
        assertThat(graal.label()).isEqualTo("JDK 25 - Oracle GraalVM");
    }

    @Test
    void build_dedupes_sdkman_and_catalog_entries_pointing_at_the_same_jdk() {
        var fx = new Fixture();
        var sdkmanGraal = fx.option("25.0.3-graal", 25, JdkVendor.ORACLE_GRAALVM);
        var catalog = new JdkCatalog(List.of(entry("Oracle", "GraalVM", 25, "25.0.3", "graalvm-jdk-25.0.3")));

        var candidates = fx.build(List.of(sdkmanGraal), Optional.of(catalog));

        // Only the installed entry survives — the catalog row collapses
        // because (Oracle, GraalVM, 25) is already covered.
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().id()).isEqualTo("25.0.3-graal");
        assertThat(candidates.getFirst().installed()).isTrue();
    }

    @Test
    void build_dedupes_two_installed_entries_of_the_same_vendor_keeping_first() {
        var fx = new Fixture();
        // Two installs of Eclipse Temurin 25 from different sources — jk-managed
        // ~/.jdks listed first (matching discover()'s priority order).
        var jkManaged = fx.option("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var sdkmanTem = fx.option("25.0.3-tem", 25, JdkVendor.TEMURIN);

        var candidates = fx.build(List.of(jkManaged, sdkmanTem), Optional.empty());

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().id()).isEqualTo("temurin-25.0.3");
    }

    @Test
    void build_keeps_distinct_vendors_separate() {
        var fx = new Fixture();
        var temurin = fx.option("temurin-25.0.3", 25, JdkVendor.TEMURIN);
        var graal = fx.option("25.0.3-graal", 25, JdkVendor.ORACLE_GRAALVM);
        var liberica = fx.option("25.0.3-librca", 25, JdkVendor.LIBERICA);

        var candidates = fx.build(List.of(temurin, graal, liberica), Optional.empty());

        // Three different vendors at the same major → three candidates.
        assertThat(candidates).hasSize(3);
        assertThat(candidates)
                .extracting(NewJdkCandidate::id)
                .containsExactly("temurin-25.0.3", "25.0.3-graal", "25.0.3-librca");
    }

    @Test
    void build_does_not_collapse_unknown_vendor_entries() {
        var fx = new Fixture();
        // Two unknown-vendor JDKs at the same major should both appear —
        // we can't safely call them duplicates.
        var unknownA = fx.option("custom-jdk-a", 25, JdkVendor.UNKNOWN);
        var unknownB = fx.option("custom-jdk-b", 25, JdkVendor.UNKNOWN);

        var candidates = fx.build(List.of(unknownA, unknownB), Optional.empty());

        assertThat(candidates).hasSize(2);
    }

    /**
     * Builds Options with a side-channel vendor map so {@link Fixture#build} can hand the correct
     * {@link JdkVendor} back to {@link NewJdkCandidate} without writing real release files on disk.
     */
    private static final class Fixture {
        private final Map<NewJdkOptions.Option, JdkVendor> vendors = new HashMap<>();

        NewJdkOptions.Option option(String id, int major, JdkVendor vendor) {
            var opt = new NewJdkOptions.Option(id, id + "  (JDK " + major + ")", Path.of("/fake/" + id), major, "jk");
            vendors.put(opt, vendor);
            return opt;
        }

        Function<NewJdkOptions.Option, JdkVendor> resolver() {
            return o -> vendors.getOrDefault(o, JdkVendor.UNKNOWN);
        }

        List<NewJdkCandidate> build(List<NewJdkOptions.Option> installed, Optional<JdkCatalog> catalog) {
            return NewJdkCandidate.build(installed, catalog, LTS, OS, ARCH, resolver());
        }
    }

    /** Convenience for standalone-candidate tests that don't go through {@code build()}. */
    private static NewJdkCandidate installed(String id, int major, JdkVendor vendor) {
        var opt = new NewJdkOptions.Option(id, id + "  (JDK " + major + ")", Path.of("/fake/" + id), major, "jk");
        return new NewJdkCandidate.Installed(opt, vendor);
    }

    private static NewJdkCandidate installable(String id, int major, String vendor, String product) {
        return new NewJdkCandidate.Installable(entry(vendor, product, major, major + ".0.3", id));
    }

    private static JdkCatalog.Entry entry(String vendor, String product, int major, String version) {
        return entry(vendor, product, major, version, product.toLowerCase().replace(" ", "-") + "-" + version);
    }

    private static JdkCatalog.Entry entry(
            String vendor, String product, int major, String version, String installFolder) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                "test",
                major,
                version,
                /* defaultForMajor */ true, /* preview */
                false,
                List.of(),
                OS,
                ARCH,
                "tar.gz",
                URI.create("https://example.com/" + installFolder + ".tar.gz"),
                "sha256:abc",
                1L,
                installFolder,
                "");
    }

    private static JdkCatalog.Entry entryOnDifferentOs(String vendor, String product, int major, String version) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                "test",
                major,
                version,
                true,
                false,
                List.of(),
                "darwin",
                "aarch64",
                "tar.gz",
                URI.create("https://example.com/foo.tar.gz"),
                "sha256:abc",
                1L,
                "foo-" + version + "-mac",
                "");
    }
}
