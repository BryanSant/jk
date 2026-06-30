// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.util.ArrayList;
import java.util.List;

public final class WizardBuilder {

    private String verb = "";
    private String subtitle = "";
    private final List<WizardStep> steps = new ArrayList<>();

    WizardBuilder() {}

    /**
     * Short action verb displayed in the goal chip, e.g. {@code "New"} or {@code "Import"}.
     */
    public WizardBuilder verb(String verb) {
        this.verb = verb;
        return this;
    }

    /**
     * Descriptive subtitle shown after the chip, e.g. {@code "Create a new Project"}. May contain
     * pre-styled ANSI sequences (callers use {@link dev.jkbuild.cli.theme.Theme#colorize} to
     * highlight a project name or other key term in bright-cyan).
     */
    public WizardBuilder subtitle(String subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    public WizardBuilder step(WizardStep step) {
        this.steps.add(step);
        return this;
    }

    public Wizard build() {
        return new Wizard(verb, subtitle, List.copyOf(steps));
    }
}
