// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-shell syntax for environment-modification commands emitted by {@code jk hook-env}. Each
 * {@link Shell} returns a single newline-terminated line that the shell can {@code eval} / {@code
 * source} verbatim.
 *
 * <p>Modelled on mise's {@code Shell} trait but trimmed to the four shells we target: bash, zsh,
 * fish, PowerShell. Other shells (xonsh, nushell, elvish) are intentionally absent — adding them is
 * the obvious extension.
 */
public sealed interface Shell permits BashShell, ZshShell, FishShell, PwshShell {

    /**
     * Canonical short name used both as the picocli value and in serialized state (e.g. {@code
     * __JK_SHELL=bash}).
     */
    String name();

    /** Render {@code export FOO=bar} (or the shell's equivalent). */
    String setEnv(String key, String value);

    /** Render the {@code unset FOO} statement (or the shell's equivalent). */
    String unsetEnv(String key);

    /**
     * Render the activation hook script (the long output of {@code jk activate}). {@code jkExe} is
     * the resolved absolute path to the {@code jk} binary, pre-quoted for the target shell.
     */
    String activateScript(String jkExe);

    /** Render the deactivation script (undoes {@link #activateScript}). */
    String deactivateScript();

    /**
     * RC file the activation line should be appended to. This is the file the user's interactive
     * shell sources at startup — {@code .zshrc} (not {@code .zshenv}), {@code .bashrc}, {@code
     * config/fish/config.fish}, {@code $PROFILE} on PowerShell.
     */
    Path rcFile(Path home);

    /** {@code ~}-prefixed display form of {@link #rcFile} for user-facing prompts. */
    String rcFileDisplay();

    /**
     * Line to append to {@link #rcFile} so the shell sources the activation script on startup. Each
     * shell has its own idiom — {@code eval} for POSIX shells, pipe-to-source for fish, {@code
     * Invoke-Expression} for PowerShell.
     */
    String activationLine(String jkExe);

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

    /** Detect the user's shell from {@code $SHELL}. */
    static Optional<Shell> detect() {
        return detect(System.getenv("SHELL"));
    }

    /** Test seam — caller supplies the raw {@code $SHELL} value. */
    static Optional<Shell> detect(String rawShell) {
        if (rawShell == null || rawShell.isBlank()) return Optional.empty();
        int slash = rawShell.lastIndexOf('/');
        String name = slash >= 0 ? rawShell.substring(slash + 1) : rawShell;
        return byName(name);
    }
}
