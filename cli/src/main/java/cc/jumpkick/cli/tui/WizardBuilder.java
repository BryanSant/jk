// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.ArrayList;
import java.util.List;

public final class WizardBuilder {

    private String command = "";
    private String subtitle = "";
    private final List<WizardStep> steps = new ArrayList<>();

    WizardBuilder() {}

    /**
     * Short action command displayed in the pipeline chip, e.g. {@code "New"} or {@code "Import"}.
     */
    public WizardBuilder command(String command) {
        this.command = command;
        return this;
    }

    /**
     * Descriptive subtitle shown after the chip, e.g. {@code "Create a new Project"}. May contain
     * pre-styled ANSI sequences (callers use {@link cc.jumpkick.cli.theme.Theme#colorize} to
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
        return new Wizard(command, subtitle, List.copyOf(steps));
    }
}
