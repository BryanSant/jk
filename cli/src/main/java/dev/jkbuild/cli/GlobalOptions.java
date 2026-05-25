// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * Flags that apply to every {@code jk} subcommand. Surfaced under a
 * separate "Global options:" section in every help screen so the
 * command-specific {@code Options:} block stays focused on what's unique
 * to that verb.
 *
 * <p>Installed programmatically as a picocli mixin (see
 * {@code Jk.installGlobalOptions}). Behavior wiring is incremental: the
 * options are recognised everywhere on day one, and individual commands
 * read the values they care about over time.
 *
 * <p>Precedence for resolving each setting: explicit flag &gt; env var
 * &gt; project {@code jk.toml} &gt; {@code ~/.config/jk/jk.toml} &gt;
 * {@code /etc/jk/jk.toml}.
 */
public final class GlobalOptions {

    @Option(names = {"-q", "--quiet"},
            description = "Suppress informational output.")
    public boolean quiet;

    @Option(names = {"-v", "--verbose"},
            description = "Print additional diagnostic output.")
    public boolean verbose;

    @Option(names = "--color", paramLabel = "<COLOR_CHOICE>",
            description = "When to colorize output: auto, always, never. "
                    + "[env: NO_COLOR, JK_COLOR]")
    public String color;

    @Option(names = "--offline",
            description = "Disable network access for this run. [env: JK_OFFLINE]")
    public boolean offline;

    @Option(names = "--no-progress",
            description = "Disable all progress bars and spinners. [env: JK_NO_PROGRESS]")
    public boolean noProgress;

    @Option(names = "--config-file", paramLabel = "<CONFIG_FILE>",
            description = "Use this jk.toml for configuration. [env: JK_CONFIG_FILE]")
    public Path configFile;

    @Option(names = "--no-config",
            description = "Skip jk.toml discovery; use built-in defaults only. [env: JK_NO_CONFIG]")
    public boolean noConfig;

    @Option(names = {"-C", "--directory"}, paramLabel = "<DIRECTORY>",
            description = "Change to this directory before running the command. [env: JK_WORKING_DIR]")
    public Path directory;

    /**
     * Resolve the working directory: explicit {@code --directory} if set
     * (either on this mixin or via {@link dev.jkbuild.config.ActiveConfig},
     * which captures {@code -C} placed before the subcommand), otherwise
     * the current working directory. Always returns an absolute normalised
     * path so callers can pass it into IO without worrying about whether
     * {@code -C} was supplied.
     */
    public Path workingDir() {
        Path raw = directory;
        if (raw == null) {
            raw = dev.jkbuild.config.ActiveConfig.get().directory().orElse(Path.of(""));
        }
        return raw.toAbsolutePath().normalize();
    }

    @Option(names = {"-h", "--help"}, usageHelp = true,
            description = "Show this help message and exit.")
    public boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true,
            description = "Print version information and exit.")
    public boolean version;
}
