// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.jdk.JdkHit;
import build.jumpkick.jdk.JdkVendor;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JdkUninstallWizardTest {

    @Test
    void rich_label_format_matches_source_slash_identifier_with_vendor_trailer() {
        JdkHit hit = new JdkHit(Path.of("/home/u/.jdks/temurin-26.0.1"), "26.0.1", JdkVendor.TEMURIN, "intellij");

        // Both focused and unfocused renderings produce the same text; the
        // visual difference is bold-vs-plain on source + identifier.
        var focused = JdkUninstallWizard.richLabel(hit, "temurin-26.0.1", true);
        var plain = JdkUninstallWizard.richLabel(hit, "temurin-26.0.1", false);
        assertThat(focused.toString()).isEqualTo("intellij/temurin-26.0.1 - Eclipse Temurin");
        assertThat(plain.toString()).isEqualTo("intellij/temurin-26.0.1 - Eclipse Temurin");

        // Focused emits the BOLD SGR (\033[1m... or composed with color); plain
        // does not. Check the ANSI to make sure only the focused row is bold.
        assertThat(focused.toAnsi()).contains(";1m");
        assertThat(plain.toAnsi()).doesNotContain(";1m");
    }

    @Test
    void rich_label_omits_trailer_when_vendor_unknown() {
        JdkHit hit = new JdkHit(Path.of("/opt/custom-jdk-x"), "21", JdkVendor.UNKNOWN, "system");

        var rich = JdkUninstallWizard.richLabel(hit, "custom-jdk-x", true);
        assertThat(rich.toString()).isEqualTo("system/custom-jdk-x");
    }

    @Test
    void choice_id_is_source_slash_identifier() {
        JdkHit hit = new JdkHit(Path.of("/home/u/.jdks/temurin-26.0.1"), "26.0.1", JdkVendor.TEMURIN, "intellij");
        assertThat(JdkUninstallWizard.choiceIdFor(hit)).isEqualTo("intellij/temurin-26.0.1");
    }
}
