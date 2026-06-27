// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.util.ArrayList;
import java.util.List;

public final class WizardBuilder {

    private String title = "";
    private String verb = "";
    private String subtitle = "";
    private final List<WizardStep> steps = new ArrayList<>();

    WizardBuilder() {}

    /** Legacy: full title shown on a plain badge (no verb/subtitle split). */
    public WizardBuilder title(String title) {
        this.title = title;
        return this;
    }

    /**
     * Short action verb displayed in the goal chip, e.g. {@code "New"} or {@code "Import"}.
     * When set (with {@link #subtitle}), the header renders as a chip line matching the build TUI
     * style instead of the legacy indigo badge.
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
        return new Wizard(title, verb, subtitle, List.copyOf(steps));
    }
}
