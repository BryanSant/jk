// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.activate;

import java.util.Locale;
import java.util.Optional;

/**
 * Per-shell syntax for environment-modification commands emitted by
 * {@code jk hook-env}. Each {@link Shell} returns a single newline-terminated
 * line that the shell can {@code eval} / {@code source} verbatim.
 *
 * <p>Modelled on mise's {@code Shell} trait but trimmed to the four shells we
 * target: bash, zsh, fish, PowerShell. Other shells (xonsh, nushell, elvish)
 * are intentionally absent — adding them is the obvious extension.
 */
public sealed interface Shell permits BashShell, ZshShell, FishShell, PwshShell {

    /**
     * Canonical short name used both as the picocli value and in serialized
     * state (e.g. {@code __JK_SHELL=bash}).
     */
    String name();

    /** Render {@code export FOO=bar} (or the shell's equivalent). */
    String setEnv(String key, String value);

    /** Render the {@code unset FOO} statement (or the shell's equivalent). */
    String unsetEnv(String key);

    /**
     * Render the activation hook script (the long output of {@code jk activate}).
     * {@code jkExe} is the resolved absolute path to the {@code jk} binary,
     * pre-quoted for the target shell.
     */
    String activateScript(String jkExe);

    /** Render the deactivation script (undoes {@link #activateScript}). */
    String deactivateScript();

    /** Resolve a shell from its name (case-insensitive). */
    static Optional<Shell> byName(String name) {
        if (name == null) return Optional.empty();
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "bash", "sh" -> Optional.of(new BashShell());
            case "zsh" -> Optional.of(new ZshShell());
            case "fish" -> Optional.of(new FishShell());
            case "pwsh", "powershell" -> Optional.of(new PwshShell());
            default -> Optional.empty();
        };
    }
}
