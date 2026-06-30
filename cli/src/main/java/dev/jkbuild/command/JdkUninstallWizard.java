// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Answers;
import dev.jkbuild.cli.tui.Choice;
import dev.jkbuild.cli.tui.Wizard;
import dev.jkbuild.cli.tui.WizardStep;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/**
 * TUI for {@code jk jdk uninstall} when no argument is supplied.
 *
 * <p>One vertical checkbox step over every installed JDK. Each row renders as:
 *
 * <pre>
 *   {source}/{identifier} - {Vendor} {Product}
 * </pre>
 *
 * with {@code source} in bold-yellow, {@code /identifier} in bold-white, and the trailing vendor
 * metadata in dark gray. The currently-installed default JDK gets a {@code (default)} hint so the
 * user sees what they're about to remove.
 *
 * <p>Returns the {@link JdkHit}s the user checked. The caller does the confirmation prompt +
 * per-item deletion + default reconciliation — keeping the wizard's responsibility narrow.
 */
final class JdkUninstallWizard {

    static final String VICTIMS_KEY = "victims";

    private JdkUninstallWizard() {}

    /**
     * Show the wizard. Empty result means the user cancelled (Esc); a result with an empty list means
     * they committed without checking anything.
     */
    static Optional<List<JdkHit>> run(List<JdkHit> installed, Optional<String> currentDefault, Terminal terminal) {
        Map<String, JdkHit> byId = new LinkedHashMap<>();
        for (JdkHit hit : installed) {
            byId.put(choiceIdFor(hit), hit);
        }

        WizardStep.MultiSelectStep.Builder victims =
                WizardStep.MultiSelectStep.vertical(VICTIMS_KEY, "Select the JDKs to uninstall");
        for (JdkHit hit : installed) {
            String id = choiceIdFor(hit);
            String identifier = JdkRegistry.identifierFor(hit.home());
            String fallback = richLabel(hit, identifier, false).toString();
            String hint = currentDefault.isPresent() && currentDefault.get().equals(identifier) ? "(default)" : "";
            victims.choice(Choice.rich(id, fallback, hint, focused -> richLabel(hit, identifier, focused)));
        }

        Wizard wizard = Wizard.builder()
                .verb("Uninstall JDK")
                .subtitle("Remove installed Java Development Kits")
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
     * Render-only: {@code source/identifier - Vendor Product} as a mixed-style {@link
     * AttributedString}.
     *
     * <p>When {@code focused} is {@code true}: source bold-yellow, identifier bold-white. When {@code
     * false}: same colors with the bold dropped, so only the row the user's cursor is sitting on
     * stands out. The trailing vendor metadata is always dark-gray; vendor block omitted when
     * unknown.
     */
    static AttributedString richLabel(JdkHit hit, String identifier, boolean focused) {
        var sourceStyle =
                focused ? Theme.active().warning().bold() : Theme.active().warning();
        var idStyle = focused ? Theme.active().focused() : Theme.active().plainWhite();
        var sb = new AttributedStringBuilder();
        sb.append(hit.source(), sourceStyle);
        sb.append("/", idStyle);
        sb.append(identifier, idStyle);
        if (hit.vendor() != null && hit.vendor() != JdkVendor.UNKNOWN) {
            sb.append(" - ", Theme.active().darkGray());
            sb.append(hit.vendor().displayName(), Theme.active().darkGray());
        }
        return sb.toAttributedString();
    }

    /** Unique key for one row: {@code <source>/<identifier>}. */
    static String choiceIdFor(JdkHit hit) {
        return hit.source() + "/" + JdkRegistry.identifierFor(hit.home());
    }
}
