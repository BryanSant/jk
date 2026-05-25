// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkVendor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JdkUninstallWizardTest {

    @Test
    void rich_label_format_matches_source_slash_identifier_with_vendor_trailer() {
        JdkHit hit = new JdkHit(Path.of("/home/u/.jdks/temurin-26.0.1"),
                "26.0.1", JdkVendor.TEMURIN, "intellij");

        var rich = JdkUninstallWizard.richLabel(hit, "temurin-26.0.1");
        // Stringified form drops styling; check that ordering + tokens match
        // the user's spec.
        assertThat(rich.toString())
                .isEqualTo("intellij/temurin-26.0.1 - Eclipse Temurin");
    }

    @Test
    void rich_label_omits_trailer_when_vendor_unknown() {
        JdkHit hit = new JdkHit(Path.of("/opt/custom-jdk-x"),
                "21", JdkVendor.UNKNOWN, "system");

        var rich = JdkUninstallWizard.richLabel(hit, "custom-jdk-x");
        assertThat(rich.toString()).isEqualTo("system/custom-jdk-x");
    }

    @Test
    void choice_id_is_source_slash_identifier() {
        JdkHit hit = new JdkHit(Path.of("/home/u/.jdks/temurin-26.0.1"),
                "26.0.1", JdkVendor.TEMURIN, "intellij");
        assertThat(JdkUninstallWizard.choiceIdFor(hit)).isEqualTo("intellij/temurin-26.0.1");
    }
}
