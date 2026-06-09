// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

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
            description = "Bypass build caches for this run")
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

    /**
     * Populate a {@code GlobalOptions} from a parsed {@link Invocation} — the
     * picocli-free counterpart to the {@code @Mixin}. A ported command's
     * {@code run(Invocation)} replaces its {@code @Mixin GlobalOptions global}
     * field with {@code GlobalOptions.from(in)}; the rest of the body
     * ({@code global.workingDir()}, {@code global.offline}, …) is unchanged.
     */
    public static GlobalOptions from(Invocation in) {
        GlobalOptions g = new GlobalOptions();
        g.quiet = in.isSet("quiet");
        g.verbose = in.isSet("verbose");
        g.color = in.value("color").orElse(null);
        g.offline = in.isSet("offline");
        g.noCache = in.isSet("no-cache");
        g.noProgress = in.isSet("no-progress");
        g.output = in.value("output").orElse(null);
        g.configFile = in.value("config-file").map(Path::of).orElse(null);
        g.noConfig = in.isSet("no-config");
        g.directory = in.value("directory").map(Path::of).orElse(null);
        return g;
    }

    /**
     * The same options as data, for commands parsed by jk's own
     * {@link dev.jkbuild.cli.args.ArgParser} (the picocli-free path). The
     * dispatcher merges these into every command's option set so global flags
     * are accepted everywhere and shown in the "Global options" help section.
     * Mirrors the {@code @Option} fields above; kept in lockstep until picocli
     * is removed, after which this becomes the single source.
     */
    public static List<Opt> globalOpts() {
        return List.of(
                Opt.flag("Suppress informational output", "-q", "--quiet"),
                Opt.flag("Print additional diagnostic output", "-v", "--verbose"),
                Opt.value("<WHEN>", "When to colorize output: auto, always, never", "--color"),
                Opt.flag("Disable network access for this run", "--offline"),
                Opt.flag("Bypass build caches for this run", "--no-cache"),
                Opt.flag("Disable all progress bars and spinners", "--no-progress"),
                Opt.value("<FORMAT>", "Output format: text (default) or json", "--output"),
                Opt.value("<FILE>", "Use this jk.toml for configuration", "--config-file"),
                Opt.flag("Skip jk.toml discovery; use built-in defaults only", "--no-config"),
                Opt.value("<DIR>", "Change to this directory before running the command", "-C", "--directory"),
                Opt.flag("Show this help message and exit", "-h", "--help"),
                Opt.flag("Print version information and exit", "-V", "--version"));
    }
}
