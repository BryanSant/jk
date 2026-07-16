// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Shell detection + rc-file paths shared by {@code jk jdk install} (advice text) and {@code jk jdk
 * update-shell} (actual rc edits).
 *
 * <p>Detection reads {@code $SHELL} and maps its basename to one of the supported shells (bash /
 * zsh / fish). Anything else returns {@link Optional#empty()} so the caller can fall back to a
 * generic message.
 */
enum JdkShell {
    BASH,
    ZSH,
    FISH;

    /** Lower-case shell name, as it appears on the {@code jk hook} command line. */
    String shellName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Concrete rc-file path under the given home directory. */
    Path rcFile(Path home) {
        return switch (this) {
            case BASH -> home.resolve(".bashrc");
            case ZSH -> home.resolve(".zshenv");
            case FISH ->
                home.resolve(".config").resolve("fish").resolve("conf.d").resolve("jk.fish");
        };
    }

    /** {@code ~}-prefixed display form for messages shown to users. */
    String rcFileDisplay() {
        return switch (this) {
            case BASH -> "~/.bashrc";
            case ZSH -> "~/.zshenv";
            case FISH -> "~/.config/fish/conf.d/jk.fish";
        };
    }

    /**
     * The one-line shell command a user can paste to wire {@code jk hook} into their rc — e.g. {@code
     * jk hook zsh >> ~/.zshenv}. Fish gets {@code >} (its hook lives in a dedicated file) instead of
     * {@code >>}.
     */
    String hookInstallCommand() {
        String redirect = this == FISH ? ">" : ">>";
        return "jk hook " + shellName() + " " + redirect + " " + rcFileDisplay();
    }

    /** Detect from {@code $SHELL}. Returns empty when the env var is unset or unsupported. */
    static Optional<JdkShell> detect() {
        return detect(System.getenv("SHELL"));
    }

    /** Detect from an explicit value (test seam or {@code --shell} override). */
    static Optional<JdkShell> detect(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        int slash = raw.lastIndexOf('/');
        String name = (slash >= 0 ? raw.substring(slash + 1) : raw).toLowerCase(Locale.ROOT);
        return switch (name) {
            case "bash" -> Optional.of(BASH);
            case "zsh" -> Optional.of(ZSH);
            case "fish" -> Optional.of(FISH);
            default -> Optional.empty();
        };
    }
}
