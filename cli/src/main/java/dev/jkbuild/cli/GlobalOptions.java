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
            description = "Suppress informational output")
    public boolean quiet;

    @Option(names = {"-v", "--verbose"},
            description = "Print additional diagnostic output")
    public boolean verbose;

    @Option(names = "--color", paramLabel = "<WHEN>",
            description = "When to colorize output: auto, always, never")
    public String color;

    @Option(names = "--offline",
            description = "Disable network access for this run")
    public boolean offline;

    @Option(names = "--no-cache",
            description = "Bypass build caches for this run (deps, action cache, "
                    + "git checkouts, tool downloads). JDKs are managed via "
                    + "`jk jdk` and are not affected by this flag.")
    public boolean noCache;

    @Option(names = "--no-progress",
            description = "Disable all progress bars and spinners")
    public boolean noProgress;

    @Option(names = "--output", paramLabel = "<FORMAT>",
            description = "Output format: text (default) or json")
    public String output;

    /**
     * True when the user asked for {@code --output json} (or set
     * {@code JK_OUTPUT=json}). Commands may use this to suppress their
     * own human-readable summary lines so the NDJSON stream stays
     * machine-parseable.
     */
    public boolean outputIsJson() {
        String resolved = output;
        if (resolved == null) {
            resolved = System.getenv("JK_OUTPUT");
        }
        return resolved != null && resolved.equalsIgnoreCase("json");
    }

    @Option(names = "--config-file", paramLabel = "<FILE>",
            description = "Use this jk.toml for configuration")
    public Path configFile;

    @Option(names = "--no-config",
            description = "Skip jk.toml discovery; use built-in defaults only")
    public boolean noConfig;

    @Option(names = {"-C", "--directory"}, paramLabel = "<DIR>",
            description = "Change to this directory before running the command")
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
            description = "Show this help message and exit")
    public boolean help;

    @Option(names = {"-V", "--version"}, versionHelp = true,
            description = "Print version information and exit")
    public boolean version;
}
