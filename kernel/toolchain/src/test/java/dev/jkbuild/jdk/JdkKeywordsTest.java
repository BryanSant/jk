// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class JdkKeywordsTest {

    @Test
    void non_keyword_returns_empty() {
        assertThat(JdkKeywords.isKeyword("25")).isFalse();
        assertThat(JdkKeywords.isKeyword("temurin-25")).isFalse();
        assertThat(JdkKeywords.resolveToMajorSpec(catalog(), "25", "linux", "x86_64"))
                .isEmpty();
    }

    @Test
    void lts_resolves_to_highest_lts_major_present() {
        // Feed carries 25 (LTS) and 26 (GA, non-LTS) → lts picks 25.
        assertThat(JdkKeywords.isKeyword("lts")).isTrue();
        assertThat(JdkKeywords.resolveToMajorSpec(catalog(), "lts", "linux", "x86_64"))
                .hasValue("temurin-25");
        assertThat(JdkKeywords.resolveToMajorSpec(catalog(), "stable", "linux", "x86_64"))
                .hasValue("temurin-25");
    }

    @Test
    void latest_resolves_to_highest_major() {
        assertThat(JdkKeywords.resolveToMajorSpec(catalog(), "latest", "linux", "x86_64"))
                .hasValue("temurin-26");
    }

    @Test
    void preview_majors_are_ignored() {
        JdkCatalog withPreview = new JdkCatalog(
                List.of(entry(25, "25.0.3", false, "linux", "x86_64"), entry(27, "27-ea+1", true, "linux", "x86_64")));
        // latest skips the 27 preview and lands on the 25 GA.
        assertThat(JdkKeywords.resolveToMajorSpec(withPreview, "latest", "linux", "x86_64"))
                .hasValue("temurin-25");
    }

    @Test
    void empty_when_no_lts_major_present() {
        JdkCatalog noLts = new JdkCatalog(List.of(entry(26, "26.0.1", false, "linux", "x86_64")));
        assertThat(JdkKeywords.resolveToMajorSpec(noLts, "lts", "linux", "x86_64"))
                .isEmpty();
    }

    @Test
    void native_is_a_keyword_and_resolves_to_latest_oracle_graalvm() {
        JdkCatalog c = new JdkCatalog(List.of(
                graal("Oracle", "GraalVM", "graalvm-jdk-24", 24, "24.0.2"),
                graal("Oracle", "GraalVM", "graalvm-jdk-25", 25, "25"),
                graal("GraalVM", "Community Edition", "graalvm-ce-25", 25, "25.0.2"),
                entry(26, "26.0.1", false, "linux", "x86_64"))); // Temurin distractor
        assertThat(JdkKeywords.isKeyword("native")).isTrue();
        // Highest Oracle GraalVM (25) wins; the community line and Temurin are ignored.
        assertThat(JdkKeywords.resolveToMajorSpec(c, "native", "linux", "x86_64"))
                .hasValue("graalvm-jdk-25");
    }

    @Test
    void native_empty_when_only_community_graalvm_present() {
        JdkCatalog onlyCe =
                new JdkCatalog(List.of(graal("GraalVM", "Community Edition", "graalvm-ce-25", 25, "25.0.2")));
        assertThat(JdkKeywords.resolveToMajorSpec(onlyCe, "native", "linux", "x86_64"))
                .isEmpty();
    }

    @Test
    void satisfaction_hints_scope_native_to_graalvm_only() {
        assertThat(JdkKeywords.satisfactionHints("native")).containsExactly("graalvm");
        assertThat(JdkKeywords.satisfactionHints("lts")).isEmpty();
        assertThat(JdkKeywords.satisfactionHints("latest")).isEmpty();
        assertThat(JdkKeywords.satisfactionHints("25")).isEmpty();
    }

    private static JdkCatalog.Entry graal(String vendor, String product, String sdkName, int major, String version) {
        return new JdkCatalog.Entry(
                vendor,
                product,
                sdkName,
                major,
                version,
                false,
                false,
                List.of(sdkName, version, String.valueOf(major)),
                "linux",
                "x86_64",
                "targz",
                URI.create("https://example.invalid/" + sdkName + ".tar.gz"),
                "00".repeat(32),
                1234L,
                sdkName,
                "");
    }

    private static JdkCatalog catalog() {
        return new JdkCatalog(
                List.of(entry(25, "25.0.3", false, "linux", "x86_64"), entry(26, "26.0.1", false, "linux", "x86_64")));
    }

    private static JdkCatalog.Entry entry(int major, String version, boolean preview, String os, String arch) {
        return new JdkCatalog.Entry(
                "Eclipse",
                "Temurin",
                "temurin-" + major,
                major,
                version,
                true,
                preview,
                List.of("temurin-" + major, version, String.valueOf(major)),
                os,
                arch,
                "targz",
                URI.create("https://example.invalid/temurin-" + version + ".tar.gz"),
                "00".repeat(32),
                1234L,
                "temurin-" + version,
                "");
    }
}
