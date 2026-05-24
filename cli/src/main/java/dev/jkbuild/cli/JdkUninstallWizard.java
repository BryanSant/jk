// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Choice;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.jdk.InstalledJdk;
import org.jline.terminal.Terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * TUI for {@code jk jdk uninstall} when no {@code <identifier>} arg is
 * supplied. One wizard, two steps:
 *
 * <ol>
 *   <li>Vertical checkbox over the installed JDKs ("Select the JDKs to
 *       uninstall"). The current global default carries a {@code (default)}
 *       annotation so the user sees what they're about to demote.</li>
 *   <li>Vertical radio over the <em>survivors</em> ("Choose a JDK to be
 *       the default"). Only fires when (a) the current default is among
 *       the victims and (b) more than one JDK will remain. The survivor
 *       list is computed dynamically from step 1's answer via {@code
 *       choicesFn} — that's why both prompts live in the same wizard
 *       frame instead of two separate runs.</li>
 * </ol>
 */
final class JdkUninstallWizard {

    private static final String TITLE = "Jk - Uninstall Java Development Kits";

    static final String VICTIMS_KEY = "victims";
    static final String NEW_DEFAULT_KEY = "newDefault";

    private JdkUninstallWizard() {}

    public record Result(List<InstalledJdk> victims, Optional<InstalledJdk> newDefault) {}

    /**
     * Run the wizard against {@code installed} and return the user's
     * decisions. Empty {@link Optional} = wizard cancelled. The result's
     * victim list may be empty if the user committed without checking
     * anything (treat as no-op).
     */
    static Optional<Result> run(
            List<InstalledJdk> installed,
            Optional<String> currentDefault,
            Terminal terminal) {
        Map<String, InstalledJdk> byId = new LinkedHashMap<>();
        for (InstalledJdk j : installed) {
            byId.put(j.identifier(), j);
        }

        WizardStep.MultiSelectStep.Builder victims = WizardStep.MultiSelectStep
                .vertical(VICTIMS_KEY, "Select the JDKs to uninstall");
        for (InstalledJdk j : installed) {
            victims.choice(j.identifier(), labelFor(j, currentDefault));
        }

        WizardStep.RadioStep newDefaultStep = WizardStep.RadioStep
                .vertical(NEW_DEFAULT_KEY, "Choose a JDK to be the default")
                .choicesFn(answers -> survivorChoices(installed, answers))
                .when(answers -> shouldPromptForNewDefault(installed, currentDefault, answers))
                .build();

        Wizard wizard = Wizard.builder()
                .title(TITLE)
                .step(victims.build())
                .step(newDefaultStep)
                .build();

        Optional<Answers> outcome = wizard.run(terminal);
        if (outcome.isEmpty()) return Optional.empty();
        Answers a = outcome.get();

        List<InstalledJdk> picked = a.getList(VICTIMS_KEY).stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();

        Optional<InstalledJdk> chosen = a.has(NEW_DEFAULT_KEY)
                ? Optional.ofNullable(byId.get(a.get(NEW_DEFAULT_KEY)))
                : Optional.empty();

        return Optional.of(new Result(picked, chosen));
    }

    /**
     * Show step 2 only when the current default is among the victims AND
     * at least two JDKs would survive (one-survivor case is auto-promoted
     * without prompting; zero survivors leaves the default cleared).
     */
    static boolean shouldPromptForNewDefault(
            List<InstalledJdk> installed,
            Optional<String> currentDefault,
            Answers answers) {
        if (currentDefault.isEmpty()) return false;
        List<String> victims = answers.getList(VICTIMS_KEY);
        boolean defaultIsVictim = victims.contains(currentDefault.get());
        if (!defaultIsVictim) return false;
        long survivors = installed.stream()
                .filter(j -> !victims.contains(j.identifier()))
                .count();
        return survivors > 1;
    }

    /** Survivors derived from the step 1 multi-select, in catalog order. */
    static List<Choice> survivorChoices(List<InstalledJdk> installed, Answers answers) {
        List<String> victims = answers.getList(VICTIMS_KEY);
        List<Choice> out = new ArrayList<>();
        for (InstalledJdk j : installed) {
            if (!victims.contains(j.identifier())) {
                out.add(new Choice(j.identifier(), j.identifier()));
            }
        }
        return out;
    }

    private static String labelFor(InstalledJdk j, Optional<String> currentDefault) {
        if (currentDefault.isPresent() && currentDefault.get().equals(j.identifier())) {
            return j.identifier() + "  (default)";
        }
        return j.identifier();
    }
}
