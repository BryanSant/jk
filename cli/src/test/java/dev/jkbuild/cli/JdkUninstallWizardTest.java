// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.jdk.InstalledJdk;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdkUninstallWizardTest {

    @Test
    void survivor_choices_are_installed_minus_victims_in_catalog_order() {
        List<InstalledJdk> installed = List.of(
                jdk("temurin-21.0.5"),
                jdk("corretto-17"),
                jdk("liberica-25"));
        Answers answers = Answers.of(Map.of(
                JdkUninstallWizard.VICTIMS_KEY, List.of("corretto-17")));

        var survivors = JdkUninstallWizard.survivorChoices(installed, answers);
        assertThat(survivors).extracting("id")
                .containsExactly("temurin-21.0.5", "liberica-25");
    }

    @Test
    void prompt_for_new_default_skipped_when_default_not_among_victims() {
        List<InstalledJdk> installed = List.of(
                jdk("temurin-21"), jdk("corretto-17"), jdk("liberica-25"));
        Answers answers = Answers.of(Map.of(
                JdkUninstallWizard.VICTIMS_KEY, List.of("corretto-17")));

        boolean prompt = JdkUninstallWizard.shouldPromptForNewDefault(
                installed, Optional.of("temurin-21"), answers);
        assertThat(prompt).isFalse();
    }

    @Test
    void prompt_for_new_default_skipped_when_only_one_survivor() {
        List<InstalledJdk> installed = List.of(jdk("temurin-21"), jdk("corretto-17"));
        Answers answers = Answers.of(Map.of(
                JdkUninstallWizard.VICTIMS_KEY, List.of("temurin-21")));

        // Default is being removed, but only one survivor — auto-promote, no prompt.
        boolean prompt = JdkUninstallWizard.shouldPromptForNewDefault(
                installed, Optional.of("temurin-21"), answers);
        assertThat(prompt).isFalse();
    }

    @Test
    void prompt_for_new_default_fires_when_default_removed_and_many_survivors() {
        List<InstalledJdk> installed = List.of(
                jdk("temurin-21"), jdk("corretto-17"), jdk("liberica-25"));
        Answers answers = Answers.of(Map.of(
                JdkUninstallWizard.VICTIMS_KEY, List.of("temurin-21")));

        boolean prompt = JdkUninstallWizard.shouldPromptForNewDefault(
                installed, Optional.of("temurin-21"), answers);
        assertThat(prompt).isTrue();
    }

    @Test
    void prompt_for_new_default_skipped_when_no_current_default() {
        List<InstalledJdk> installed = List.of(jdk("temurin-21"), jdk("corretto-17"));
        Answers answers = Answers.of(Map.of(
                JdkUninstallWizard.VICTIMS_KEY, List.of("temurin-21")));

        boolean prompt = JdkUninstallWizard.shouldPromptForNewDefault(
                installed, Optional.empty(), answers);
        assertThat(prompt).isFalse();
    }

    private static InstalledJdk jdk(String identifier) {
        return new InstalledJdk(identifier, Path.of("/tmp/jdks", identifier));
    }
}
