// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import java.nio.file.Path;
import java.util.List;

/**
 * Global flags that apply to every {@code jk} subcommand. Populated from a parsed {@link
 * dev.jkbuild.model.command.Invocation} via {@link #from(dev.jkbuild.model.command.Invocation)}.
 *
 * <p>Precedence for resolving each setting: explicit flag &gt; env var &gt; project {@code jk.toml}
 * &gt; user-global {@code ~/.jk/config.toml}. There is no {@code /etc/jk} system layer and jk never
 * reads {@code ~/.config} — see {@link dev.jkbuild.config.ConfigSources}.
 */
public final class GlobalOptions {
    public boolean quiet;
    public boolean verbose;
    public String color;
    public boolean offline;

    /** {@code --force}: bypass all of jk's caching for this invocation. */
    public boolean force;

    public boolean noProgress;

    /** {@code --jdk <spec>} / {@code --graal <spec>}: the top JDK / GraalVM resolution tier. */
    public String jdk;

    public String graal;

    public String output;

    /**
     * True when the user asked for {@code --output json} (or set {@code JK_OUTPUT=json}). Commands
     * may use this to suppress their own human-readable summary lines so the NDJSON stream stays
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
     * Resolve the working directory: explicit {@code --directory} if set (either on this mixin or via
     * {@link dev.jkbuild.config.SessionContext}, which captures {@code -C} placed before the
     * subcommand), otherwise the current working directory. Always returns an absolute normalised
     * path so callers can pass it into IO without worrying about whether {@code -C} was supplied.
     */
    public Path workingDir() {
        Path raw = directory;
        if (raw == null) {
            raw = dev.jkbuild.config.SessionContext.current().config().directory().orElse(Path.of(""));
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
     * The CLI-supplied JVM tuning as the highest-precedence {@link dev.jkbuild.config.WorkerTuning}
     * layer. {@code gc} / {@code string-dedup} are left unset here — those come from env / {@code
     * jk.toml}; the CLI exposes only the two most common knobs.
     */
    public dev.jkbuild.config.WorkerTuning jvmCli() {
        return new dev.jkbuild.config.WorkerTuning(maxRamPercent, null, null, jvmArgs);
    }

    /**
     * Populate a {@code GlobalOptions} from a parsed {@link Invocation} — the picocli-free
     * counterpart to the {@code @Mixin}. A ported command's {@code run(Invocation)} replaces its
     * {@code @Mixin GlobalOptions global} field with {@code GlobalOptions.from(in)}; the rest of the
     * body ({@code global.workingDir()}, {@code global.offline}, …) is unchanged.
     */
    public static GlobalOptions from(Invocation in) {
        GlobalOptions g = new GlobalOptions();
        g.quiet = in.isSet("quiet");
        g.verbose = in.isSet("verbose");
        g.color = in.value("color").orElse(null);
        g.offline = in.isSet("offline");
        g.force = in.isSet("force");
        g.noProgress = in.isSet("no-progress");
        g.output = in.value("output").orElse(null);
        g.configFile = in.value("config-file").map(Path::of).orElse(null);
        g.noConfig = in.isSet("no-config");
        g.directory = in.value("directory").map(Path::of).orElse(null);
        g.maxRamPercent = in.value("max-ram-percent")
                .map(s -> {
                    try {
                        return Double.valueOf(s.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
        g.jvmArgs = in.values("jvm-arg");
        g.jdk = in.value("jdk").orElse(null);
        g.graal = in.value("graal").orElse(null);
        // Carry the top-tier JDK/GraalVM selection and the resolved JVM tuning on the request-scoped
        // Session, so the toolchain resolvers (JdkResolution / GraalResolver) and every worker fork
        // read them from the request instead of process-global system properties / static channels.
        dev.jkbuild.config.SessionContext.install(dev.jkbuild.config.SessionContext.current()
                .withToolchainSpecs(g.jdk, g.graal)
                .withJvm(dev.jkbuild.worker.JvmOptions.resolve(g.jvmCli(), g.workingDir())));
        return g;
    }

    /**
     * The global options as {@link dev.jkbuild.model.command.Opt} data. The dispatcher merges these
     * into every command's option set so global flags are accepted everywhere and shown in the
     * "Global options" help section.
     */
    public static List<Opt> globalOpts() {
        return List.of(
                Opt.flag("Suppress informational output", "-q", "--quiet"),
                Opt.flag("Print additional diagnostic output", "-v", "--verbose"),
                Opt.value("<WHEN>", "When to colorize output: auto, always, never", "--color"),
                Opt.flag("Disable network access for this run", "--offline"),
                Opt.flag("Bypass jk's caching and redo this operation", "--force"),
                Opt.flag("Disable all progress bars and spinners", "--no-progress"),
                Opt.flag("Disable all ANSI/color/Unicode; ASCII-only output", "--no-ansi"),
                Opt.value("<FORMAT>", "Output format: text (default) or json", "--output"),
                Opt.value("<FILE>", "Use this jk.toml for configuration", "--config-file"),
                Opt.flag("Skip jk.toml discovery; use defaults", "--no-config"),
                Opt.value("<DIR>", "Change to this directory before running", "-C", "--directory"),
                Opt.value("<PCT>", "Worker-JVM max heap as % of RAM", "--max-ram-percent"),
                Opt.value("<ARG>", "Extra worker-JVM flag (repeatable)", "--jvm-arg")
                        .repeat(),
                Opt.value("<spec>", "JDK for this run; overrides project pins", "--jdk"),
                Opt.value("<spec>", "GraalVM for jk native / GRAALVM_HOME", "--graal"),
                Opt.flag("Show this help message and exit", "-h", "--help"),
                Opt.flag("Print version information and exit", "-V", "--version"));
    }
}
