// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.command.*;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkConfigLoader;
import java.util.List;
import java.util.Map;

/** jk CLI entrypoint — routes commands through {@link CommandDispatch}. */
public final class Jk {

    /** Alias of {@link cc.jumpkick.model.JkVersion#VERSION} for CLI-side callers. */
    public static final String VERSION = cc.jumpkick.model.JkVersion.VERSION;

    /**
     * Hidden command aliases for ergonomic migration from other build tools. Documented in {@code
     * docs/aliases.md}. Keys are alias names; values are the canonical command path (one or more
     * positionals). These are not registered commands (they stay out of {@code --help} and
     * shell completion); instead we rewrite the first positional arg before parsing — possibly
     * expanding it into multiple positionals.
     */
    static final Map<String, List<String>> VERB_ALIASES = Map.ofEntries(
            Map.entry("generate", List.of("new")), // Maven mvn archetype:generate
            Map.entry("dependencies", List.of("tree")), // Gradle gradle dependencies
            Map.entry("package", List.of("build")), // Maven mvn package
            Map.entry("deploy", List.of("publish")), // Maven mvn deploy
            Map.entry("upgrade", List.of("update")), // npm/yarn/apt vocabulary
            Map.entry("sh", List.of("shell")),
            Map.entry("bash", List.of("shell")),
            Map.entry("nativeCompile", List.of("native")), // Gradle :nativeCompile task
            Map.entry("verify-target", List.of("verify")), // Maven's `verify` step output naming
            Map.entry("why-rebuilt", List.of("explain"))); // early-roadmap name for the cache-diff report

    /**
     * Internal, hidden flag that re-invokes this same binary as the engine server loop instead of a
     * normal client command — mirrors how {@code jk cache prune --background} reuses the binary as a
     * detached one-shot worker. Not a registered command; never appears in {@code --help} or shell
     * completion. This is the JVM-dist path and the fallback when no engine artifact is installed;
     * the native dist ships the engine as a fat jar ({@code ~/.jk/versions/<version>/lib/jk-engine.jar},
     * from {@code :cli-engine:shadowJar}) that the client runs on the jk-managed JDK — the engine
     * is a JVM app, never a native image. Both routes run the exact same {@code EngineMain.run} — reached here through the
     * {@link cc.jumpkick.cli.engine.InProcessEngine} ServiceLoader seam, because the slim client
     * module no longer links the engine (Stage 5): on the JVM dist the seam finds the engine; the
     * client native image (which physically has no engine code) reports it plainly instead. See
     * {@code docs/engine.md} ("Two artifacts").
     */
    private static final String ENGINE_SERVER_FLAG = "--engine-server";

    public static void main(String[] args) {
        // The internal --engine-server re-invocation is checked BEFORE the jkx
        // argv[0] dispatch: the engine spawn re-execs this same binary, and when
        // the user's command was `jkx …` that child can inherit the jkx identity
        // (hardlinked argv[0], propagated JAVA_OPTS). Rewriting it to `tool run
        // --engine-server` would kill the engine before it starts.
        if (args.length == 0 || !ENGINE_SERVER_FLAG.equals(args[0])) {
            // Invoked as `jkx` (hardlink/link to this binary): behave exactly like
            // `jk tool run …` in every case — including --help — so the alias has
            // one mental model.
            args = rewriteForProgramName(args, Argv0.programName());
        }
        if (args.length > 0 && ENGINE_SERVER_FLAG.equals(args[0])) {
            // Engine role: deliberately NOT GlobalCancel — its SIGINT handler halts the process,
            // and a Ctrl-C aimed at the client that spawned us lands on the whole foreground
            // process group. EngineMain.run installs the engine's own signal policy instead.
            var engine = cc.jumpkick.cli.engine.InProcessEngine.find().orElse(null);
            if (engine == null) {
                System.err.println("jk: this binary does not include the engine; "
                        + "materialize jk " + VERSION + " (`jk self update` or the project wrapper), or set JK_ENGINE_EXE");
                System.exit(70);
                return;
            }
            System.exit(engine.engineServerMain());
            return;
        }
        cc.jumpkick.cli.tui.GlobalCancel.install();
        System.exit(execute(args));
    }

    /** Run jk with the given argv. The first positional is rewritten if it's a known alias. */
    public static int execute(String... args) {
        // `--list` is an undocumented synonym for `--help`. Rewrite it before any
        // arg scan so both the config loader and the dispatcher only ever see `--help`.
        args = rewriteListToHelp(args);
        // Resolve configuration first — the dispatcher's subsequent option parsing only
        // determines explicit flag values; defaults still need to come from the
        // env / project jk.toml / user / system layers via JkConfigLoader.
        loadAndInstallConfig(args);
        // Fold the explicit-CLI layer in last so the rest of the runtime sees the
        // fully-resolved JkConfig before any subcommand dispatches.
        applyCliOverrides(args);
        // -q/--quiet must take effect before any println happens. Apply it now
        // based on the resolved config (which already knows about env/file/CLI layers).
        Quietable.applyIfQuiet(cc.jumpkick.config.SessionContext.current().config());
        String[] rewritten = rewriteAlias(args);
        // Every command is now on the CliCommand model; CommandDispatch handles all
        // dispatch. The fallback below handles bare `jk` + --help + --version.
        Integer ported = CommandDispatch.tryDispatch(rewritten);
        if (ported != null) return ported;
        // No command: check for --help / --version / bare invocation.
        boolean ansi = CommandDispatch.ansiEnabled();
        java.util.List<String> argList = java.util.List.of(rewritten);
        if (argList.contains("-V") || argList.contains("--version")) {
            System.out.println("jk " + VERSION);
            return 0;
        }
        if (argList.contains("-h") || argList.contains("--help")) {
            // Full help: all command groups + global options.
            System.out.print(fullHelp(ansi));
            return 0;
        }
        // Bare `jk` (no command, no flags): curated short-help screen.
        HelpRenderer.printShortHelp(
                CommandDispatch.commands(),
                "A fast build tool and package manager for Java & Kotlin",
                "jk",
                System.out,
                ansi);
        return 0;
    }

    /** Full `jk --help` screen: commands grouped + global options. */
    private static String fullHelp(boolean ansi) {
        java.util.Map<String, SubcommandModel> byName = new java.util.LinkedHashMap<>();
        for (var c : CommandDispatch.commands()) {
            if (!c.hidden()) byName.put(c.name(), new SubcommandModel(c.name(), new String[] {c.description()}, false));
        }
        List<OptionModel> globals = GlobalOptions.globalOpts().stream()
                .filter(o -> !o.hidden())
                .map(CommandModels::option)
                .toList();
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("A fast build tool and package manager for Java & Kotlin")
                .append(nl)
                .append(nl);
        // Usage line
        if (ansi) {
            sb.append(HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true))
                    .append(" ")
                    .append(HelpRenderer.paint("jk", Theme.active().commandName(), true))
                    .append(HelpRenderer.paint(
                            " <COMMAND> [OPTIONS]", Theme.active().paramLabel(), true))
                    .append(nl);
        } else {
            sb.append("Usage: jk <COMMAND> [OPTIONS]").append(nl);
        }
        // Group by UsageGroups (same grouping as before)
        java.util.Set<String> placed = new java.util.LinkedHashSet<>();
        boolean firstGroup = true;
        for (CommandGroup group : UsageGroups.COMMAND_GROUPS) {
            List<String> visible =
                    group.names().stream().filter(byName::containsKey).toList();
            if (visible.isEmpty()) continue;
            if (!firstGroup) sb.append(nl);
            firstGroup = false;
            sb.append(nl)
                    .append(HelpRenderer.paint(group.heading(), Theme.active().sectionHeading(), ansi))
                    .append(nl);
            int width = visible.stream().mapToInt(String::length).max().orElse(0) + 4;
            for (String name : visible) {
                SubcommandModel sub = byName.get(name);
                String padding = " ".repeat(width - name.length());
                sb.append("  ")
                        .append(HelpRenderer.paint(name, Theme.active().commandName(), ansi))
                        .append(padding)
                        .append(sub.description().length > 0 ? sub.description()[0] : "")
                        .append(nl);
                placed.add(name);
            }
        }
        // Ungrouped leftover
        List<String> leftover = byName.keySet().stream()
                .filter(n -> !placed.contains(n))
                .sorted()
                .toList();
        if (!leftover.isEmpty()) {
            sb.append(nl)
                    .append(HelpRenderer.paint("Other commands:", Theme.active().sectionHeading(), ansi))
                    .append(nl);
            int width = leftover.stream().mapToInt(String::length).max().orElse(0) + 4;
            for (String name : leftover) {
                SubcommandModel sub = byName.get(name);
                String padding = " ".repeat(width - name.length());
                sb.append("  ")
                        .append(HelpRenderer.paint(name, Theme.active().commandName(), ansi))
                        .append(padding)
                        .append(sub.description().length > 0 ? sub.description()[0] : "")
                        .append(nl);
            }
        }
        // Global options
        sb.append(nl)
                .append(HelpRenderer.paint("Global options:", Theme.active().sectionHeading(), ansi))
                .append(nl);
        sb.append(HelpRenderer.renderOptionRows(globals, ansi));
        return sb.toString();
    }

    /**
     * Argv-scan pass that folds explicit CLI flags into {@link cc.jumpkick.config.SessionContext}. This is intentionally a
     * small scan rather than reusing ArgParser: the highest-precedence layer needs to be
     * available <em>before</em> dispatch (e.g. so {@link Quietable} can mute
     * stdout before the first subcommand println). Only flags that affect global behavior are read
     * here; everything else flows through the dispatcher.
     */
    private static void applyCliOverrides(String[] args) {
        java.util.Optional<JkConfig.ColorChoice> color = java.util.Optional.empty();
        java.util.Optional<Boolean> offline = java.util.Optional.empty();
        java.util.Optional<Boolean> force = java.util.Optional.empty();
        java.util.Optional<Boolean> rebuild = java.util.Optional.empty();
        java.util.Optional<Boolean> noProgress = java.util.Optional.empty();
        java.util.Optional<Boolean> noAnsi = java.util.Optional.empty();
        java.util.Optional<Boolean> quiet = java.util.Optional.empty();
        java.util.Optional<Boolean> verbose = java.util.Optional.empty();
        java.util.Optional<java.nio.file.Path> directory = java.util.Optional.empty();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-q", "--quiet" -> quiet = java.util.Optional.of(true);
                case "-v", "--verbose" -> verbose = java.util.Optional.of(true);
                case "--offline" -> offline = java.util.Optional.of(true);
                case "--force" -> force = java.util.Optional.of(true);
                case "--rebuild" -> rebuild = java.util.Optional.of(true);
                case "--no-progress" -> noProgress = java.util.Optional.of(true);
                // --no-ansi: strip ALL ANSI (color + bold/italic) and disable animations.
                // Sets noAnsi=true (→ isAnsi()=false → colorize() returns plain text) and
                // noProgress=true (→ QUIET mode, no cursor-movement redraws).
                // Distinct from --color never which strips color but preserves text attributes.
                case "--no-ansi" -> {
                    noAnsi = java.util.Optional.of(true);
                    noProgress = java.util.Optional.of(true);
                }
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
        JkConfig cli = new JkConfig(
                color, offline, rebuild, noProgress, quiet, verbose, directory, force, noAnsi);
        cc.jumpkick.config.SessionContext.installConfig(cc.jumpkick.config.SessionContext.current().config().mergedWith(cli));
    }

    /**
     * Read {@code --config-file} / {@code --no-config} out of raw argv with a cheap linear scan, then
     * ask {@link JkConfigLoader} to build the merged {@link JkConfig} and install it on the {@link
     * cc.jumpkick.config.SessionContext}. This runs before picocli parsing so the rest of the CLI
     * sees an already-resolved config.
     *
     * <p>CLI flag values (the highest layer) are folded in lazily as each command's {@link
     * GlobalOptions} mixin reads them after parsing.
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
            JkConfig resolved = JkConfigLoader.load(java.nio.file.Path.of("").toAbsolutePath(), noConfig, explicit);
            cc.jumpkick.config.SessionContext.installConfig(resolved);
        } catch (java.io.IOException e) {
            // Best-effort — a broken user/project config shouldn't kill the CLI.
            System.err.println("jk: warning: could not load config (" + e.getMessage() + "); using defaults.");
            cc.jumpkick.config.SessionContext.installConfig(JkConfig.empty());
        }
    }

    /**
     * Rewrite any {@code --list} occurrence to {@code --help}. {@code --list} is an undocumented
     * alias so muscle memory from tools like {@code rustup} / {@code cargo} keeps working; downstream
     * code never sees it.
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

    /**
     * When the binary was invoked as {@code jkx} (per {@link Argv0}), prepend {@code tool run} so
     * the whole invocation is exactly {@code jk tool run …}. Any other program name (including
     * null) passes through untouched.
     */
    static String[] rewriteForProgramName(String[] args, String programName) {
        if (!"jkx".equals(programName)) return args;
        String[] out = new String[args.length + 2];
        out[0] = "tool";
        out[1] = "run";
        System.arraycopy(args, 0, out, 2, args.length);
        return out;
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
}
