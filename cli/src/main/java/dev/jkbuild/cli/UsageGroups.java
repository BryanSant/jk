// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import java.util.List;
import java.util.Set;

/**
 * Static help-content data shared across the help renderers: the top-level verb
 * groupings, the curated short-help groupings, and the canonical "Global
 * options" block. Moved verbatim from {@code Jk} so the rendering classes and
 * {@code Jk} all reference one source of truth.
 */
public final class UsageGroups {

    private UsageGroups() {}

    /**
     * Top-level verb groupings for --help. Order within each group is rough
     * lifecycle / workflow order (create → manage → build → distribute →
     * verify), not alphabetical. Any registered subcommand that doesn't
     * appear here is listed under "Shell integration commands:" — the
     * leftover bucket today is {@code activate} / {@code deactivate}.
     */
    public static final List<CommandGroup> COMMAND_GROUPS = List.of(
            new CommandGroup("Build commands:", List.of(
                    "build", "run", "clean", "image",
                    "test", "compile", "native",
                    "install", "publish")),
            new CommandGroup("Project commands:", List.of(
                    "new", "init",
                    "add", "remove",
                    "lock", "update", "sync",
                    "deny",
                    "tree", "why",
                    "explain",
                    "audit",
                    "verify")),
            new CommandGroup("Toolchain commands:", List.of(
                    "jdk", "tool", "shell", "activate", "deactivate")),
            new CommandGroup("Interop commands:", List.of(
                    "import", "mvn", "gradle", "export")),
            new CommandGroup("System commands:", List.of(
                    "doctor", "cache")));

    /**
     * Curated subset of verbs shown when the user runs bare {@code jk}.
     * Goal: cover the day-to-day verbs without overwhelming first-time users.
     * The full screen is still one keystroke away via {@code --help}.
     */
    public static final List<CommandGroup> SHORT_COMMAND_GROUPS = List.of(
            new CommandGroup("Build commands:", List.of(
                    "build", "run", "clean", "image", "native", "install", "publish")),
            new CommandGroup("Project commands:", List.of(
                    "new", "init", "add", "remove", "lock", "update")),
            new CommandGroup("Toolchain commands:", List.of(
                    "jdk", "tool", "shell", "activate")));

    /**
     * The long-name set every {@code GlobalOptions} option declares. Used to
     * partition the merged option list at help-render time. Picocli stores
     * mixin options inline with command options, so an instance check via
     * {@code userObject()} isn't reliable across all picocli builds — a
     * name-set check is the most portable way.
     */
    public static final Set<String> GLOBAL_OPTION_LONG_NAMES = Set.of(
            "--quiet", "--verbose", "--color", "--offline", "--no-cache", "--no-progress",
            "--output", "--config-file", "--no-config", "--directory",
            "--help", "--version");
}
