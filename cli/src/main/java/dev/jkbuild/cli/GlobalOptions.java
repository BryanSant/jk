// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.nio.file.Path;
import java.util.List;

/**
 * Global flags that apply to every {@code jk} subcommand. Populated from a
 * parsed {@link dev.jkbuild.model.command.Invocation} via {@link #from(dev.jkbuild.model.command.Invocation)}.
 *
 * <p>Precedence for resolving each setting: explicit flag &gt; env var
 * &gt; project {@code jk.toml} &gt; {@code ~/.config/jk/jk.toml} &gt;
 * {@code /etc/jk/jk.toml}.
 */
public final class GlobalOptions {
    public boolean quiet;
    public boolean verbose;
    public String color;
    public boolean offline;
    public boolean noCache;
    public boolean noProgress;

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
    public Path configFile;
    public boolean noConfig;
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
    public boolean help;
    public boolean version;

    /** {@code --max-ram-percent}: per-JVM heap cap for jk's worker JVMs, or null. */
    public Double maxRamPercent;
    /** {@code --jvm-arg}: extra raw flags for jk's worker JVMs (repeatable). */
    public List<String> jvmArgs = List.of();

    /**
     * The CLI-supplied JVM tuning as the highest-precedence
     * {@link dev.jkbuild.worker.JvmOptions.Settings} layer. {@code gc} /
     * {@code string-dedup} are left unset here — those come from env /
     * {@code jk.toml}; the CLI exposes only the two most common knobs.
     */
    public dev.jkbuild.worker.JvmOptions.Settings jvmCli() {
        return new dev.jkbuild.worker.JvmOptions.Settings(maxRamPercent, null, null, jvmArgs);
    }

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
        g.maxRamPercent = in.value("max-ram-percent").map(s -> {
            try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
        }).orElse(null);
        g.jvmArgs = in.values("jvm-arg");
        // Resolve JVM tuning (flag > env > jk.toml > default) once for the whole
        // invocation and stash it process-wide, so every worker fork the build
        // spawns picks it up — not just the JK_* env layer. (With no host JVM to
        // carry it across a process boundary, this static is the channel.)
        dev.jkbuild.worker.JvmOptions.setProcessSettings(
                dev.jkbuild.worker.JvmOptions.resolve(g.jvmCli(), g.workingDir()));
        return g;
    }

    /**
     * The global options as {@link dev.jkbuild.model.command.Opt} data. The
     * dispatcher merges these into every command's option set so global flags
     * are accepted everywhere and shown in the "Global options" help section.
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
                Opt.value("<PCT>", "Max heap as a percentage of RAM for jk's worker JVMs "
                        + "(divided across parallel test workers). Default 50.", "--max-ram-percent"),
                Opt.value("<ARG>", "Extra JVM flag for jk's worker JVMs (repeatable)", "--jvm-arg").repeat(),
                Opt.flag("Show this help message and exit", "-h", "--help"),
                Opt.flag("Print version information and exit", "-V", "--version"));
    }
}
