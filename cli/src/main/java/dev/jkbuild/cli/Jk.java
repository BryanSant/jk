// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.command.*;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkConfigLoader;
import java.util.List;
import java.util.Map;

/** jk CLI entrypoint — routes verbs through {@link CommandDispatch}. */
public final class Jk {

    /** Alias of {@link dev.jkbuild.util.JkVersion#VERSION} for CLI-side callers. */
    public static final String VERSION = dev.jkbuild.util.JkVersion.VERSION;

    /**
     * Hidden verb aliases for ergonomic migration from other build tools. Documented in {@code
     * docs/aliases.md}. Keys are alias names; values are the canonical verb path (one or more
     * positionals). We don't register these with picocli (so they stay out of {@code --help} and
     * shell completion); instead we rewrite the first positional arg before parsing — possibly
     * expanding it into multiple positionals (e.g. {@code install} → {@code tool install}).
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
            Map.entry("verify-target", List.of("verify")), // Maven's `verify` phase output naming
            Map.entry("verify-build", List.of("verify")), // renamed verb; verify-build kept for back-compat
            Map.entry("check", List.of("compile"))); // renamed verb; check kept for back-compat

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
        // Every verb is now on the CliCommand model; CommandDispatch handles all
        // dispatch. The fallback below handles bare `jk` + --help + --version.
        Integer ported = CommandDispatch.tryDispatch(rewritten);
        if (ported != null) return ported;
        // No verb: check for --help / --version / bare invocation.
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
        // Bare `jk` (no verb, no flags): curated short-help screen.
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
     * Argv-scan pass that folds explicit CLI flags into {@link ActiveConfig}. This is intentionally a
     * small scan rather than reusing picocli's parser: the highest-precedence layer needs to be
     * available <em>before</em> picocli runs (e.g. so {@link dev.jkbuild.config.Quietable} can mute
     * stdout before the first subcommand println). Only flags that affect global behavior are read
     * here; everything else flows through picocli.
     */
    private static void applyCliOverrides(String[] args) {
        java.util.Optional<JkConfig.ColorChoice> color = java.util.Optional.empty();
        java.util.Optional<Boolean> offline = java.util.Optional.empty();
        java.util.Optional<Boolean> rerun = java.util.Optional.empty();   // legacy
        java.util.Optional<Boolean> refresh = java.util.Optional.empty(); // legacy
        java.util.Optional<Boolean> force = java.util.Optional.empty();
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
                case "--force" -> force = java.util.Optional.of(true);
                // Legacy aliases — still accepted, fold into force.
                case "--rerun", "--refresh" -> force = java.util.Optional.of(true);
                case "--no-progress" -> noProgress = java.util.Optional.of(true);
                // --no-ansi: no ANSI escape codes, no Unicode glyphs, no animations.
                // Equivalent to --color never + --no-progress (color=never → isAnsi()=false
                // → GoalWedge ASCII output, nerdfont()=false; no-progress → QUIET mode →
                // animate=false, no cursor-movement redraws).
                case "--no-ansi" -> {
                    color = java.util.Optional.of(JkConfig.ColorChoice.NEVER);
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
        JkConfig cli = new JkConfig(color, offline, rerun, refresh, noProgress, quiet, verbose, directory, force);
        ActiveConfig.install(ActiveConfig.get().mergedWith(cli));
    }

    /**
     * Read {@code --config-file} / {@code --no-config} out of raw argv with a cheap linear scan, then
     * ask {@link JkConfigLoader} to build the merged {@link JkConfig} and stash it in {@link
     * ActiveConfig}. This runs before picocli parsing so the rest of the CLI sees an already-resolved
     * config.
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
            ActiveConfig.install(resolved);
        } catch (java.io.IOException e) {
            // Best-effort — a broken user/project config shouldn't kill the CLI.
            System.err.println("jk: warning: could not load config (" + e.getMessage() + "); using defaults.");
            ActiveConfig.install(JkConfig.empty());
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
