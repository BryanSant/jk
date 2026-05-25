// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Choice;
import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * TUI for {@code jk jdk uninstall} when no argument is supplied.
 *
 * <p>One vertical checkbox step over every installed JDK. Each row renders
 * as:
 * <pre>
 *   {source}/{identifier} - {Vendor} {Product}
 * </pre>
 * with {@code source} in bold-yellow, {@code /identifier} in bold-white,
 * and the trailing vendor metadata in dark gray. The currently-installed
 * default JDK gets a {@code (default)} hint so the user sees what they're
 * about to remove.
 *
 * <p>Returns the {@link JdkHit}s the user checked. The caller does the
 * confirmation prompt + per-item deletion + default reconciliation —
 * keeping the wizard's responsibility narrow.
 */
final class JdkUninstallWizard {

    private static final String TITLE = "Jk - Uninstall Java Development Kits";

    static final String VICTIMS_KEY = "victims";

    private JdkUninstallWizard() {}

    /**
     * Show the wizard. Empty result means the user cancelled (Esc); a result
     * with an empty list means they committed without checking anything.
     */
    static Optional<List<JdkHit>> run(
            List<JdkHit> installed,
            Optional<String> currentDefault,
            Terminal terminal) {
        Map<String, JdkHit> byId = new LinkedHashMap<>();
        for (JdkHit hit : installed) {
            byId.put(choiceIdFor(hit), hit);
        }

        WizardStep.MultiSelectStep.Builder victims = WizardStep.MultiSelectStep
                .vertical(VICTIMS_KEY, "Select the JDKs to uninstall");
        for (JdkHit hit : installed) {
            String id = choiceIdFor(hit);
            String identifier = JdkRegistry.identifierFor(hit.home());
            AttributedString rich = richLabel(hit, identifier);
            String hint = currentDefault.isPresent() && currentDefault.get().equals(identifier)
                    ? "(default)" : "";
            victims.choice(new Choice(id, rich.toString(), hint, null, rich));
        }

        Wizard wizard = Wizard.builder()
                .title(TITLE)
                .step(victims.build())
                .build();

        Optional<Answers> outcome = wizard.run(terminal);
        if (outcome.isEmpty()) return Optional.empty();
        Answers a = outcome.get();

        List<JdkHit> picked = a.getList(VICTIMS_KEY).stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
        return Optional.of(picked);
    }

    /**
     * Render-only: {@code source/identifier - Vendor Product} as a mixed-style
     * {@link AttributedString}. Source bold-yellow; identifier bold-white;
     * trailing vendor metadata dark-gray; vendor block omitted when unknown.
     */
    static AttributedString richLabel(JdkHit hit, String identifier) {
        var sb = new AttributedStringBuilder();
        sb.append(hit.source(), Theme.warning().bold());
        sb.append("/", Theme.focused());
        sb.append(identifier, Theme.focused());
        if (hit.vendor() != null && hit.vendor() != JdkVendor.UNKNOWN) {
            sb.append(" - ", Theme.darkGray());
            sb.append(hit.vendor().displayName(), Theme.darkGray());
        }
        return sb.toAttributedString();
    }

    /** Unique key for one row: {@code <source>/<identifier>}. */
    static String choiceIdFor(JdkHit hit) {
        return hit.source() + "/" + JdkRegistry.identifierFor(hit.home());
    }
}
