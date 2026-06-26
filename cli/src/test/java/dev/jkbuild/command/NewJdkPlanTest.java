// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkbuild.jdk.JdkVendor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewJdkPlanTest {

    private static final int LTS = 25;

    // --- parseExplicit: the vendor-in-the-pin policy --------------------

    @Test
    void bare_major_stays_bare() {
        var spec = NewJdkPlan.parseExplicit("25");
        assertThat(spec.major()).isEqualTo(25);
        assertThat(spec.pin()).isEqualTo("25");
    }

    @Test
    void explicit_vendor_is_preserved_lowercased() {
        var spec = NewJdkPlan.parseExplicit("Corretto-25");
        assertThat(spec.major()).isEqualTo(25);
        assertThat(spec.pin()).isEqualTo("corretto-25");
    }

    @Test
    void point_release_is_rejected() {
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("25.0.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point release");
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("temurin-25.0.3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("point release");
    }

    @Test
    void spec_without_a_major_is_rejected() {
        assertThatThrownBy(() -> NewJdkPlan.parseExplicit("temurin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("major version");
    }

    // --- shouldPrompt ---------------------------------------------------

    @Test
    void modules_and_default_jdk_never_prompt() {
        var two = List.of(
                installed("temurin-25", 25, JdkVendor.TEMURIN), installed("corretto-25", 25, JdkVendor.CORRETTO));
        assertThat(NewJdkPlan.shouldPrompt(true, false, two, 25)).isFalse(); // module
        assertThat(NewJdkPlan.shouldPrompt(false, true, two, 25)).isFalse(); // default set
    }

    @Test
    void prompt_only_when_more_than_one_eligible() {
        var one = List.of(installed("temurin-25", 25, JdkVendor.TEMURIN));
        var two = List.of(
                installed("temurin-25", 25, JdkVendor.TEMURIN), installed("corretto-25", 25, JdkVendor.CORRETTO));
        assertThat(NewJdkPlan.shouldPrompt(false, false, one, 25)).isFalse();
        assertThat(NewJdkPlan.shouldPrompt(false, false, two, 25)).isTrue();
        // floor filters out the older JDK, dropping back to a single choice.
        var mixed =
                List.of(installed("temurin-25", 25, JdkVendor.TEMURIN), installed("temurin-21", 21, JdkVendor.TEMURIN));
        assertThat(NewJdkPlan.shouldPrompt(false, false, mixed, 25)).isFalse();
    }

    // --- autoCandidate --------------------------------------------------

    @Test
    void auto_prefers_an_installed_match_for_the_preferred_major() {
        var candidates =
                List.of(installed("temurin-25", 25, JdkVendor.TEMURIN), installed("temurin-21", 21, JdkVendor.TEMURIN));
        var picked = NewJdkPlan.autoCandidate(candidates, 21, /* preferred */ 21, LTS);
        assertThat(picked).get().extracting(NewJdkCandidate::major).isEqualTo(21);
    }

    @Test
    void auto_uses_the_sole_eligible_install_when_no_preference() {
        var candidates = List.of(installed("temurin-21", 21, JdkVendor.TEMURIN));
        var picked = NewJdkPlan.autoCandidate(candidates, 17, /* preferred */ 0, LTS);
        assertThat(picked).get().extracting(NewJdkCandidate::major).isEqualTo(21);
    }

    private static NewJdkCandidate installed(String id, int major, JdkVendor vendor) {
        var opt = new NewJdkOptions.Option(id, id + "  (JDK " + major + ")", Path.of("/fake/" + id), major, "jk");
        return new NewJdkCandidate.Installed(opt, vendor);
    }
}
