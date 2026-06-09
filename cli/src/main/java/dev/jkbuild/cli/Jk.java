// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.command.*;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkConfigLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jk CLI entrypoint. Verbs are registered as subcommands; each one is a
 * {@link java.util.concurrent.Callable} returning a {@code sysexits.h}-style
 * exit code (PRD §6).
 */
@Command(
        name = "jk",
        // -h/--help and -V/--version come from GlobalOptions; mixinStandardHelpOptions stays off
        // so picocli doesn't try to register them twice.
        version = "jk " + Jk.VERSION,
        description = "A fast build tool and package manager for Java & Kotlin",
        subcommands = {
                // All commands ported to jk's own CliCommand model (run via
                // CommandDispatch, listed in `jk --help` by newCommandLine()).
        })
public final class Jk implements Runnable {

    /** Alias of {@link dev.jkbuild.util.JkVersion#VERSION} for CLI-side callers. */
    public static final String VERSION = dev.jkbuild.util.JkVersion.VERSION;

    /**
     * Hidden verb aliases for ergonomic migration from other build tools.
     * Documented in {@code docs/aliases.md}. Keys are alias names; values
     * are the canonical verb path (one or more positionals). We don't
     * register these with picocli (so they stay out of {@code --help} and
     * shell completion); instead we rewrite the first positional arg
     * before parsing — possibly expanding it into multiple positionals
     * (e.g. {@code install} → {@code tool install}).
     */
    static final Map<String, List<String>> VERB_ALIASES = Map.ofEntries(
            Map.entry("generate", List.of("new")),            // Maven mvn archetype:generate
            Map.entry("dependencies", List.of("tree")),       // Gradle gradle dependencies
            Map.entry("package", List.of("build")),           // Maven mvn package
            Map.entry("deploy", List.of("publish")),          // Maven mvn deploy
            Map.entry("upgrade", List.of("update")),          // npm/yarn/apt vocabulary
            Map.entry("sh", List.of("shell")),
            Map.entry("bash", List.of("shell")),
            Map.entry("nativeCompile", List.of("native")),    // Gradle :nativeCompile task
            Map.entry("verify-target", List.of("verify")),    // Maven's `verify` phase output naming
            Map.entry("verify-build", List.of("verify")),     // renamed verb; verify-build kept for back-compat
            Map.entry("check", List.of("compile")));          // renamed verb; check kept for back-compat

    public static void main(String[] args) {
        dev.jkbuild.cli.tui.GlobalCancel.install();
        System.exit(execute(args));
    }

    /** Run jk with the given argv. The first positional is rewritten if it's a known alias. */
    public static int execute(String... args) {
        // `--list` is an undocumented synonym for `--help`. Rewrite it before any
        // arg scan so both the config loader and picocli only ever see `--help`.
        args = rewriteListToHelp(args);
        // Resolve configuration first — picocli's subsequent option parsing only
        // determines explicit flag values; defaults still need to come from the
        // env / project jk.toml / user / system layers via JkConfigLoader.
        loadAndInstallConfig(args);
        // Fold the explicit-CLI layer in last so the rest of the runtime sees the
        // fully-resolved JkConfig before picocli even dispatches a subcommand.
        applyCliOverrides(args);
        // -q/--quiet must take effect before any println happens. Apply it now
        // based on the resolved config (which already knows about env/file/CLI layers).
        dev.jkbuild.config.Quietable.applyIfQuiet(ActiveConfig.get());
        String[] rewritten = rewriteAlias(args);
        // Coexistence (Phase 3): verbs ported off picocli to jk's own Command
        // model are parsed + run by CommandDispatch; everything else still
        // flows through picocli. Returns null when the verb isn't ported.
        Integer ported = CommandDispatch.tryDispatch(rewritten);
        if (ported != null) return ported;
        CommandLine cmd = newCommandLine();
        HelpLayout.applyColorScheme(cmd);
        return cmd.execute(rewritten);
    }

    /**
     * Argv-scan pass that folds explicit CLI flags into {@link ActiveConfig}.
     * This is intentionally a small scan rather than reusing picocli's parser:
     * the highest-precedence layer needs to be available <em>before</em>
     * picocli runs (e.g. so {@link dev.jkbuild.config.Quietable} can mute
     * stdout before the first subcommand println). Only flags that affect
     * global behavior are read here; everything else flows through picocli.
     */
    private static void applyCliOverrides(String[] args) {
        java.util.Optional<JkConfig.ColorChoice> color = java.util.Optional.empty();
        java.util.Optional<Boolean> offline = java.util.Optional.empty();
        java.util.Optional<Boolean> noCache = java.util.Optional.empty();
        java.util.Optional<Boolean> noProgress = java.util.Optional.empty();
        java.util.Optional<Boolean> quiet = java.util.Optional.empty();
        java.util.Optional<Boolean> verbose = java.util.Optional.empty();
        java.util.Optional<java.nio.file.Path> directory = java.util.Optional.empty();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-q", "--quiet" -> quiet = java.util.Optional.of(true);
                case "-v", "--verbose" -> verbose = java.util.Optional.of(true);
                case "--offline" -> offline = java.util.Optional.of(true);
                case "--no-cache" -> noCache = java.util.Optional.of(true);
                case "--no-progress" -> noProgress = java.util.Optional.of(true);
                case "--color" -> {
                    if (i + 1 < args.length) color = JkConfig.ColorChoice.parse(args[++i]);
                }
                case "-C", "--directory" -> {
                    if (i + 1 < args.length) directory = java.util.Optional.of(java.nio.file.Path.of(args[++i]));
                }
                default -> {
                    if (a.startsWith("--color=")) {
                        color = JkConfig.ColorChoice.parse(a.substring("--color=".length()));
                    } else if (a.startsWith("--directory=")) {
                        directory = java.util.Optional.of(java.nio.file.Path.of(a.substring("--directory=".length())));
                    }
                }
            }
        }
        JkConfig cli = new JkConfig(color, offline, noCache, noProgress, quiet, verbose, directory);
        ActiveConfig.install(ActiveConfig.get().mergedWith(cli));
    }

    /**
     * Read {@code --config-file} / {@code --no-config} out of raw argv with a
     * cheap linear scan, then ask {@link JkConfigLoader} to build the merged
     * {@link JkConfig} and stash it in {@link ActiveConfig}. This runs before
     * picocli parsing so the rest of the CLI sees an already-resolved config.
     *
     * <p>CLI flag values (the highest layer) are folded in lazily as each
     * command's {@link GlobalOptions} mixin reads them after parsing.
     */
    private static void loadAndInstallConfig(String[] args) {
        boolean noConfig = false;
        java.util.Optional<java.nio.file.Path> explicit = java.util.Optional.empty();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--no-config".equals(a)) {
                noConfig = true;
            } else if ("--config-file".equals(a) && i + 1 < args.length) {
                explicit = java.util.Optional.of(java.nio.file.Path.of(args[++i]));
            } else if (a.startsWith("--config-file=")) {
                explicit = java.util.Optional.of(java.nio.file.Path.of(a.substring("--config-file=".length())));
            }
        }
        try {
            JkConfig resolved = JkConfigLoader.load(
                    java.nio.file.Path.of("").toAbsolutePath(), noConfig, explicit);
            ActiveConfig.install(resolved);
        } catch (java.io.IOException e) {
            // Best-effort — a broken user/system config shouldn't kill the CLI.
            System.err.println("jk: warning: could not load config (" + e.getMessage() + "); using defaults.");
            ActiveConfig.install(JkConfig.empty());
        }
    }

    /**
     * Rewrite any {@code --list} occurrence to {@code --help}. {@code --list} is
     * an undocumented alias so muscle memory from tools like {@code rustup} /
     * {@code cargo} keeps working; downstream code never sees it.
     */
    static String[] rewriteListToHelp(String[] args) {
        String[] out = null;
        for (int i = 0; i < args.length; i++) {
            if ("--list".equals(args[i])) {
                if (out == null) out = args.clone();
                out[i] = "--help";
            }
        }
        return out != null ? out : args;
    }

    static String[] rewriteAlias(String[] args) {
        if (args.length == 0) return args;
        List<String> mapped = VERB_ALIASES.get(args[0]);
        if (mapped == null) return args;
        String[] out = new String[mapped.size() + args.length - 1];
        for (int i = 0; i < mapped.size(); i++) out[i] = mapped.get(i);
        System.arraycopy(args, 1, out, mapped.size(), args.length - 1);
        return out;
    }

    /** Subcommands whose unmatched options forward to a wrapped tool — `--help` must pass through. */
    private static final Set<String> PASSTHROUGH_COMMANDS = Set.of("mvn", "gradle");

    /** Picocli root, configured for jk's passthrough semantics. */
    public static CommandLine newCommandLine() {
        CommandLine cmd = new CommandLine(new Jk());
        // mvn/gradle are passthroughs: jk owns flags listed before the tool's
        // own args, everything else (including unknown `-X` style flags) gets
        // forwarded as positional to the child process.
        for (String name : PASSTHROUGH_COMMANDS) {
            CommandLine sub = cmd.getSubcommands().get(name);
            if (sub != null) {
                sub.setUnmatchedOptionsArePositionalParams(true);
            }
        }
        // Install jk's help system: the GlobalOptions mixin on every command, the
        // styled parent/leaf section layouts, and the cargo-style error renderers.
        HelpLayout.install(cmd);
        // List commands already ported off picocli (run via CommandDispatch) so
        // they still appear in `jk --help`. Metadata-only specs — picocli never
        // executes them (the dispatcher intercepts those verbs first).
        for (dev.jkbuild.model.command.CliCommand c : CommandDispatch.commands()) {
            if (c.hidden() || cmd.getSubcommands().containsKey(c.name())) continue;
            CommandSpec sub = CommandSpec.create().name(c.name());
            sub.usageMessage().description(c.description());
            cmd.getCommandSpec().addSubcommand(c.name(), new CommandLine(sub));
        }
        return cmd;
    }

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // No subcommand: print the curated short-help screen. The full screen
        // (every verb, every global option) is one keystroke away via --help.
        boolean ansi = spec.commandLine().getColorScheme().ansi().enabled();
        HelpRenderer.printShortHelp(spec, System.out, ansi);
    }
}
