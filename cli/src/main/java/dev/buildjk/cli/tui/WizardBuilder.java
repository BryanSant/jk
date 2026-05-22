// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli.tui;

import java.util.ArrayList;
import java.util.List;

public final class WizardBuilder {

    private String title = "";
    private final List<WizardStep> steps = new ArrayList<>();

    WizardBuilder() {}

    public WizardBuilder title(String title) {
        this.title = title;
        return this;
    }

    public WizardBuilder step(WizardStep step) {
        this.steps.add(step);
        return this;
    }

    public Wizard build() {
        return new Wizard(title, List.copyOf(steps));
    }
}
