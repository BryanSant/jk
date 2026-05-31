// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.config.JkConfigLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.UsageMessageSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UnmatchedArgumentException;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
                NewCommand.class,
                InitCommand.class,
                AddCommand.class,
                RemoveCommand.class,
                LockCommand.class,
                UpdateCommand.class,
                TreeCommand.class,
                WhyCommand.class,
                SyncCommand.class,
                CompileCommand.class,
                BuildCommand.class,
                TestCommand.class,
                CleanCommand.class,
                ExplainCommand.class,
                JdkCommand.class,
                dev.jkbuild.cli.activate.ActivateCommand.class,
                dev.jkbuild.cli.activate.HookEnvCommand.class,
                dev.jkbuild.cli.activate.DeactivateCommand.class,
                ShellCommand.class,
                MvnCommand.class,
                GradleCommand.class,
                ImportCommand.class,
                ExportCommand.class,
                InstallCommand.class,
                RunCommand.class,
                PublishCommand.class,
                AuditCommand.class,
                DenyCommand.class,
                ImageCommand.class,
                NativeCommand.class,
                VerifyBuildCommand.class,
                ToolCommand.class,
                RegistryCommand.class,
                AuthCommand.class,
                RepoCommand.class,
                DoctorCommand.class,
                CacheCommand.class,
        })
public final class Jk implements Runnable {

    public static final String VERSION = "0.1.0-SNAPSHOT";

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
        CommandLine cmd = newCommandLine();
        applyColorScheme(cmd);
        return cmd.execute(rewriteAlias(args));
    }

    /**
     * Force picocli's help renderer to honor the resolved {@code --color}
     * choice in {@link ActiveConfig}. Without this, picocli would do its
     * own auto-detection independently of the user's explicit flag.
     */
    private static void applyColorScheme(CommandLine cmd) {
        var choice = ActiveConfig.get().colorOr(JkConfig.ColorChoice.AUTO);
        picocli.CommandLine.Help.Ansi ansi = switch (choice) {
            case ALWAYS -> picocli.CommandLine.Help.Ansi.ON;
            case NEVER -> picocli.CommandLine.Help.Ansi.OFF;
            // AUTO matches Theme.colorEnabled(): on unless NO_COLOR is set.
            case AUTO -> {
                var nc = System.getenv("NO_COLOR");
                yield (nc == null || nc.isEmpty())
                        ? picocli.CommandLine.Help.Ansi.ON
                        : picocli.CommandLine.Help.Ansi.OFF;
            }
        };
        cmd.setColorScheme(picocli.CommandLine.Help.defaultColorScheme(ansi));
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

    /** Picocli's default section order — used to restore leaf commands after the parent layout leaks via spec inheritance. */
    private static final List<String> DEFAULT_SECTION_KEYS = List.of(
            UsageMessageSpec.SECTION_KEY_HEADER_HEADING,
            UsageMessageSpec.SECTION_KEY_HEADER,
            UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
            UsageMessageSpec.SECTION_KEY_SYNOPSIS,
            UsageMessageSpec.SECTION_KEY_DESCRIPTION_HEADING,
            UsageMessageSpec.SECTION_KEY_DESCRIPTION,
            UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_AT_FILE_PARAMETER,
            UsageMessageSpec.SECTION_KEY_PARAMETER_LIST,
            UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_OPTION_LIST,
            UsageMessageSpec.SECTION_KEY_END_OF_OPTIONS,
            UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
            UsageMessageSpec.SECTION_KEY_EXIT_CODE_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_EXIT_CODE_LIST,
            UsageMessageSpec.SECTION_KEY_FOOTER_HEADING,
            UsageMessageSpec.SECTION_KEY_FOOTER);

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
        // Inject GlobalOptions (-q, -v, --color, --offline, --no-progress, --config-file,
        // --no-config, -h, -V) as a mixin on every command at every depth. mvn/gradle
        // skip the mixin so their `--help` keeps forwarding to the wrapped tool.
        installGlobalOptionsEverywhere(cmd);
        // Apply the custom "description / Usage / commands" layout to every command that
        // has subcommands — top-level `jk` plus nested parents like `jk jdk`, `jk tool`,
        // `jk cache`. Leaf commands keep picocli's default formatting so their option /
        // parameter lists stay visible.
        applyParentLayout(cmd);
        applyParentLayoutRecursively(cmd);
        // Replace picocli's default "Unmatched argument …" error with a cargo-style
        // "error: / tip: / Usage: / For more information" block. Propagates to subcommands.
        cmd.setParameterExceptionHandler(Jk::handleParameterException);
        // Expected runtime errors (offline gate, missing files) should print one
        // clean stderr line instead of a Java stack trace.
        cmd.setExecutionExceptionHandler(Jk::handleExecutionException);
        return cmd;
    }

    /**
     * Render expected runtime errors as a single {@code error:} line rather
     * than letting picocli print the whole stack trace. Anything we don't
     * recognise still gets the full trace so we don't silently swallow bugs.
     */
    private static int handleExecutionException(
            Exception ex,
            CommandLine cmd,
            picocli.CommandLine.ParseResult parseResult) throws Exception {
        if (ex instanceof dev.jkbuild.http.OfflineException) {
            var err = cmd.getErr();
            boolean ansi = cmd.getColorScheme().ansi().enabled();
            String label = ansi ? "\033[1;91merror:\033[0m " : "error: ";
            err.println(label + ex.getMessage());
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        }
        throw ex;
    }

    /** Mixin key used to register {@link GlobalOptions} on every {@link CommandSpec}. */
    private static final String GLOBAL_OPTIONS_MIXIN_KEY = "global";

    private static void installGlobalOptionsEverywhere(CommandLine cmd) {
        if (!PASSTHROUGH_COMMANDS.contains(cmd.getCommandName())) {
            // Skip commands that already declared `@Mixin GlobalOptions` themselves
            // (so they can READ the values); picocli has already registered it for them.
            boolean alreadyHas = cmd.getCommandSpec().mixins().values().stream()
                    .anyMatch(spec -> spec.userObject() instanceof GlobalOptions);
            if (!alreadyHas) {
                // Fresh instance per command — picocli writes parsed values into the
                // mixin's fields, so sharing one instance would race during parsing.
                cmd.getCommandSpec().addMixin(GLOBAL_OPTIONS_MIXIN_KEY,
                        CommandSpec.forAnnotatedObject(new GlobalOptions()));
            }
        }
        for (CommandLine sub : cmd.getSubcommands().values()) {
            installGlobalOptionsEverywhere(sub);
        }
    }

    private static void applyParentLayoutRecursively(CommandLine parent) {
        for (CommandLine sub : parent.getSubcommands().values()) {
            if (!sub.getSubcommands().isEmpty()) {
                applyParentLayout(sub);
            } else if (PASSTHROUGH_COMMANDS.contains(sub.getCommandName())) {
                // Passthroughs (mvn, gradle) forward --help to the wrapped tool; don't
                // intercept their help layout.
                sub.setHelpSectionKeys(DEFAULT_SECTION_KEYS);
            } else {
                // Every non-passthrough leaf gets the styled "description / Usage /
                // Arguments / Options" layout that matches the parent help screens.
                applyLeafLayout(sub);
            }
            applyParentLayoutRecursively(sub);
        }
    }

    /** Custom section key for the global-options heading + list. */
    private static final String SECTION_KEY_GLOBAL_OPTIONS_HEADING = "jkGlobalOptionsHeading";
    private static final String SECTION_KEY_GLOBAL_OPTIONS = "jkGlobalOptions";

    /**
     * Layout for a styled leaf command: {description, Usage, Arguments,
     * Options, Global options}. Mirrors {@link #applyParentLayout} but
     * swaps the command-list section for parameter and option renderers
     * that match the styling of parent help screens.
     */
    private static void applyLeafLayout(CommandLine cmd) {
        cmd.setHelpSectionKeys(List.of(
                UsageMessageSpec.SECTION_KEY_DESCRIPTION,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS,
                UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING,
                UsageMessageSpec.SECTION_KEY_PARAMETER_LIST,
                UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING,
                UsageMessageSpec.SECTION_KEY_OPTION_LIST,
                SECTION_KEY_GLOBAL_OPTIONS_HEADING,
                SECTION_KEY_GLOBAL_OPTIONS));
        Map<String, CommandLine.IHelpSectionRenderer> sections = cmd.getHelpSectionMap();
        sections.put(UsageMessageSpec.SECTION_KEY_DESCRIPTION, Jk::renderDescription);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING, Jk::renderSynopsisHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS, Jk::renderSynopsis);
        sections.put(UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING, Jk::renderArgumentsHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_PARAMETER_LIST, Jk::renderStyledParameterList);
        sections.put(UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING, Jk::renderOptionsHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_OPTION_LIST, Jk::renderStyledOptionList);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS_HEADING, Jk::renderGlobalOptionsHeading);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS, Jk::renderStyledGlobalOptionList);
    }

    /**
     * Layout for any command that has subcommands. Reorders sections to
     * {description, Usage line, command list}, drops the option list, and wires
     * in bright-green / bright-cyan renderers. The top-level uses the
     * verb-grouped subcommand list (Project / Toolchain / …); nested parents
     * use a single "Commands:" heading over a flat list.
     */
    private static void applyParentLayout(CommandLine cmd) {
        boolean topLevel = cmd.getCommandSpec().parent() == null;
        List<String> keys = new ArrayList<>(List.of(
                UsageMessageSpec.SECTION_KEY_DESCRIPTION,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS));
        if (!topLevel) {
            keys.add(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING);
        }
        keys.add(UsageMessageSpec.SECTION_KEY_COMMAND_LIST);
        keys.add(SECTION_KEY_GLOBAL_OPTIONS_HEADING);
        keys.add(SECTION_KEY_GLOBAL_OPTIONS);
        cmd.setHelpSectionKeys(keys);

        Map<String, CommandLine.IHelpSectionRenderer> sections = cmd.getHelpSectionMap();
        sections.put(UsageMessageSpec.SECTION_KEY_DESCRIPTION, Jk::renderDescription);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING, Jk::renderSynopsisHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS, Jk::renderSynopsis);
        if (topLevel) {
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, Jk::renderGroupedSubcommands);
        } else {
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING, Jk::renderCommandsHeading);
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, Jk::renderFlatSubcommands);
        }
        sections.put(SECTION_KEY_GLOBAL_OPTIONS_HEADING, Jk::renderGlobalOptionsHeading);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS, Jk::renderStyledGlobalOptionList);
    }

    /**
     * Top-level verb groupings for --help. Order within each group is rough
     * lifecycle / workflow order (create → manage → build → distribute →
     * verify), not alphabetical. Any registered subcommand that doesn't
     * appear here is listed under "Shell integration commands:" — the
     * leftover bucket today is {@code activate} / {@code deactivate}.
     */
    private record CommandGroup(String heading, List<String> names) {}

    private static final List<CommandGroup> COMMAND_GROUPS = List.of(
            new CommandGroup("Build commands:", List.of(
                    "build", "run", "clean", "image",
                    "compile", "test", "native",
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
    private static final List<CommandGroup> SHORT_COMMAND_GROUPS = List.of(
            new CommandGroup("Build commands:", List.of(
                    "build", "run", "clean", "image", "native", "install", "publish")),
            new CommandGroup("Project commands:", List.of(
                    "new", "init", "add", "remove", "lock", "update")),
            new CommandGroup("Toolchain commands:", List.of(
                    "jdk", "tool", "shell", "activate")));

    private static String renderGroupedSubcommands(Help help) {
        // Help.subcommands() keys aliased entries as "name, alias" — index by
        // each subcommand's canonical name() so lookups in COMMAND_GROUPS work.
        Map<String, Help> byName = new LinkedHashMap<>();
        int width = 0;
        for (Help sub : help.subcommands().values()) {
            if (sub.commandSpec().usageMessage().hidden()) continue;
            String name = sub.commandSpec().name();
            byName.put(name, sub);
            width = Math.max(width, name.length());
        }
        width += 4;

        boolean ansi = help.colorScheme().ansi().enabled();
        Set<String> placed = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        // Separate this section from the preceding options block.
        out.append(nl);
        for (CommandGroup group : COMMAND_GROUPS) {
            List<String> visible = group.names().stream()
                    .filter(byName::containsKey)
                    .toList();
            if (visible.isEmpty()) continue;
            out.append(brightGreen(group.heading(), ansi)).append(nl);
            for (String name : visible) {
                appendCommandRow(out, name, byName.get(name), width, ansi);
                placed.add(name);
            }
        }

        List<String> leftover = byName.keySet().stream()
                .filter(n -> !placed.contains(n))
                .sorted()
                .toList();
        if (!leftover.isEmpty()) {
            out.append(brightGreen("Shell integration commands:", ansi)).append(nl);
            for (String name : leftover) {
                appendCommandRow(out, name, byName.get(name), width, ansi);
            }
        }
        return out.toString();
    }

    private static String renderDescription(Help help) {
        String[] desc = help.commandSpec().usageMessage().description();
        if (desc.length == 0) return "";
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        for (String line : desc) {
            out.append(line).append(nl);
        }
        // Blank line separating description from the "Usage:" line below.
        out.append(nl);
        return out.toString();
    }

    private static String renderSynopsisHeading(Help help) {
        return brightGreen("Usage:", help.colorScheme().ansi().enabled()) + " ";
    }

    /**
     * "{@code <name> [...args] [OPTIONS]}" — name is bold+bright-cyan, the rest is plain
     * bright-cyan. Replaces picocli's auto-generated synopsis (which would list every
     * registered flag) with a fixed, abstract form. Parents render as
     * {@code <name> <COMMAND> [OPTIONS]}; leaves include any visible positional
     * parameters in declaration order before {@code [OPTIONS]}.
     */
    private static String renderSynopsis(Help help) {
        boolean ansi = help.colorScheme().ansi().enabled();
        String name = help.commandSpec().qualifiedName();
        boolean isLeaf = help.commandSpec().subcommands().isEmpty();
        StringBuilder suffix = new StringBuilder();
        if (!isLeaf) {
            suffix.append(" <COMMAND>");
        } else {
            for (var p : help.commandSpec().positionalParameters()) {
                if (p.hidden()) continue;
                suffix.append(" ").append(p.paramLabel());
            }
        }
        suffix.append(" [OPTIONS]");
        String nl = System.lineSeparator();
        if (ansi) {
            return "\033[1;96m" + name + "\033[0m\033[96m" + suffix + "\033[0m" + nl;
        }
        return name + suffix + nl;
    }

    private static String renderCommandsHeading(Help help) {
        String nl = System.lineSeparator();
        return nl + brightGreen("Commands:", help.colorScheme().ansi().enabled()) + nl;
    }

    private static String renderOptionsHeading(Help help) {
        boolean anyVisible = help.commandSpec().options().stream()
                .anyMatch(o -> !o.hidden() && !isGlobal(o));
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + brightGreen("Options:", help.colorScheme().ansi().enabled()) + nl;
    }

    private static String renderGlobalOptionsHeading(Help help) {
        boolean anyVisible = help.commandSpec().options().stream()
                .anyMatch(o -> !o.hidden() && isGlobal(o));
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + brightGreen("Global options:", help.colorScheme().ansi().enabled()) + nl;
    }

    /**
     * The long-name set every {@link GlobalOptions} option declares. Used to
     * partition the merged option list at help-render time. Picocli stores
     * mixin options inline with command options, so an instance check via
     * {@code userObject()} isn't reliable across all picocli builds — a
     * name-set check is the most portable way.
     */
    private static final Set<String> GLOBAL_OPTION_LONG_NAMES = Set.of(
            "--quiet", "--verbose", "--color", "--offline", "--no-cache", "--no-progress",
            "--output", "--config-file", "--no-config", "--directory",
            "--help", "--version");

    /** True when this option came from the {@link GlobalOptions} mixin. */
    private static boolean isGlobal(picocli.CommandLine.Model.OptionSpec opt) {
        for (String name : opt.names()) {
            if (GLOBAL_OPTION_LONG_NAMES.contains(name)) return true;
        }
        return false;
    }

    private static String renderArgumentsHeading(Help help) {
        boolean anyVisible = help.commandSpec().positionalParameters().stream().anyMatch(p -> !p.hidden());
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + brightGreen("Arguments:", help.colorScheme().ansi().enabled()) + nl;
    }

    /**
     * Render visible positional parameters as `  <param>  description` rows.
     * Param labels are plain bright-cyan (matching the synopsis's `[OPTIONS]`).
     */
    private static String renderStyledParameterList(Help help) {
        boolean ansi = help.colorScheme().ansi().enabled();
        var params = help.commandSpec().positionalParameters().stream()
                .filter(p -> !p.hidden())
                .toList();
        if (params.isEmpty()) return "";
        int width = params.stream().mapToInt(p -> p.paramLabel().length()).max().orElse(0) + 2;
        var sb = new StringBuilder();
        String nl = System.lineSeparator();
        for (var p : params) {
            String label = p.paramLabel();
            String desc = p.description().length > 0 ? p.description()[0] : "";
            sb.append("  ")
                    .append(ansi ? "\033[96m" + label + "\033[0m" : label)
                    .append(" ".repeat(width - label.length()))
                    .append(desc)
                    .append(nl);
        }
        return sb.toString();
    }

    /**
     * Render command-specific options (those <em>not</em> from the
     * {@link GlobalOptions} mixin) as styled rows.
     */
    private static String renderStyledOptionList(Help help) {
        var options = help.commandSpec().options().stream()
                .filter(o -> !o.hidden() && !isGlobal(o))
                .toList();
        return renderOptionRows(options, help.colorScheme().ansi().enabled());
    }

    /** Render the {@link GlobalOptions} mixin's options as styled rows. */
    private static String renderStyledGlobalOptionList(Help help) {
        var options = help.commandSpec().options().stream()
                .filter(o -> !o.hidden() && isGlobal(o))
                .toList();
        return renderOptionRows(options, help.colorScheme().ansi().enabled());
    }

    /**
     * Option name aliases that still parse but are kept out of {@code --help}.
     * Lets us retire short flags from the help screen without breaking users
     * (or tests) that already depend on them.
     */
    private static final Set<String> HIDDEN_OPTION_NAMES = Set.of("-C");

    /**
     * Format a list of options as `  -x, --xx <param>  description` rows.
     * Name block is bold-cyan; param label (when present) is plain cyan;
     * description is unstyled. Boolean flags (arity 0) skip the label.
     */
    private static String renderOptionRows(List<picocli.CommandLine.Model.OptionSpec> options, boolean ansi) {
        if (options.isEmpty()) return "";
        record Row(String namePart, String labelPart) {
            int width() { return namePart.length() + (labelPart.isEmpty() ? 0 : labelPart.length() + 1); }
        }
        List<Row> rows = new ArrayList<>(options.size());
        int width = 0;
        for (var opt : options) {
            String namePart = java.util.Arrays.stream(opt.names())
                    .filter(n -> !HIDDEN_OPTION_NAMES.contains(n))
                    .collect(java.util.stream.Collectors.joining(", "));
            String labelPart = (opt.arity().max() > 0 && opt.paramLabel() != null && !opt.paramLabel().isEmpty())
                    ? opt.paramLabel()
                    : "";
            Row row = new Row(namePart, labelPart);
            rows.add(row);
            width = Math.max(width, row.width());
        }
        width += 3; // gutter between name/label block and description

        var sb = new StringBuilder();
        String nl = System.lineSeparator();
        for (int i = 0; i < options.size(); i++) {
            Row row = rows.get(i);
            String desc = options.get(i).description().length > 0 ? options.get(i).description()[0] : "";
            sb.append("  ").append(brightCyan(row.namePart, ansi));
            if (!row.labelPart.isEmpty()) {
                sb.append(" ").append(ansi ? "\033[96m" + row.labelPart + "\033[0m" : row.labelPart);
            }
            sb.append(" ".repeat(width - row.width())).append(desc).append(nl);
        }
        return sb.toString();
    }

    private static String brightGreen(String text, boolean ansi) {
        return ansi ? "\033[1;92m" + text + "\033[0m" : text;
    }

    private static String brightCyan(String text, boolean ansi) {
        return ansi ? "\033[1;96m" + text + "\033[0m" : text;
    }

    /**
     * Render parameter-parse failures in a cargo-style block: a bold red
     * {@code error:} line, an optional bright-green {@code tip:} suggestion,
     * a colored {@code Usage:} line for the command where parsing failed, and
     * a {@code --help} hint. Returns picocli's
     * {@link CommandSpec#exitCodeOnInvalidInput} as the exit code.
     */
    private static int handleParameterException(ParameterException ex, String[] args) {
        CommandLine cmd = ex.getCommandLine();
        PrintWriter err = cmd.getErr();
        boolean ansi = cmd.getColorScheme().ansi().enabled();

        String wrong = null;
        String suggestion = null;
        if (ex instanceof UnmatchedArgumentException uae && !uae.getUnmatched().isEmpty()) {
            wrong = uae.getUnmatched().get(0);
            List<String> suggestions = uae.getSuggestions();
            if (!suggestions.isEmpty()) {
                String prefix = cmd.getCommandSpec().qualifiedName() + " ";
                suggestion = suggestions.get(0);
                if (suggestion.startsWith(prefix)) suggestion = suggestion.substring(prefix.length());
            }
        }

        if (wrong != null) {
            boolean isOption = wrong.startsWith("-");
            String label = isOption ? "unrecognized option" : "unrecognized subcommand";
            err.println(formatErrorLine(ansi, label, wrong));
        } else {
            err.println(formatErrorLine(ansi, "error", ex.getMessage()));
        }
        err.println();
        if (suggestion != null) {
            err.println(formatTipLine(ansi, suggestion));
            err.println();
        }
        err.println(formatUsageLine(ansi, cmd.getCommandSpec().qualifiedName()));
        err.println();
        err.println(formatHelpHint(ansi));
        err.flush();
        return cmd.getCommandSpec().exitCodeOnInvalidInput();
    }

    private static String formatErrorLine(boolean ansi, String label, String value) {
        if (ansi) {
            return "\033[1;91merror:\033[0m " + label + " '\033[33m" + value + "\033[0m'";
        }
        return "error: " + label + " '" + value + "'";
    }

    private static String formatTipLine(boolean ansi, String suggestion) {
        if (ansi) {
            return "  \033[92mtip:\033[0m did you mean '\033[92m" + suggestion + "\033[0m'?";
        }
        return "  tip: did you mean '" + suggestion + "'?";
    }

    private static String formatUsageLine(boolean ansi, String name) {
        String suffix = " <COMMAND> [OPTIONS]";
        if (ansi) {
            return "\033[1;92mUsage:\033[0m \033[1;96m" + name + "\033[0m\033[96m" + suffix + "\033[0m";
        }
        return "Usage: " + name + suffix;
    }

    private static String formatHelpHint(boolean ansi) {
        if (ansi) {
            return "For more information, try '\033[1;96m--help\033[0m'";
        }
        return "For more information, try '--help'";
    }

    /** Flat command list (canonical names only, registration order) for nested subcommand help. */
    private static String renderFlatSubcommands(Help help) {
        Map<String, Help> byName = new LinkedHashMap<>();
        int width = 0;
        for (Help sub : help.subcommands().values()) {
            if (sub.commandSpec().usageMessage().hidden()) continue;
            String name = sub.commandSpec().name();
            byName.put(name, sub);
            width = Math.max(width, name.length());
        }
        width += 2;
        boolean ansi = help.colorScheme().ansi().enabled();
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Help> e : byName.entrySet()) {
            appendCommandRow(out, e.getKey(), e.getValue(), width, ansi);
        }
        return out.toString();
    }

    private static void appendCommandRow(StringBuilder out, String name, Help sub, int width, boolean ansi) {
        String[] desc = sub.commandSpec().usageMessage().description();
        String firstLine = desc.length > 0 ? desc[0] : "";
        // Pad after the colorized name based on the plain length — ANSI codes
        // are zero-width on screen but counted by String.format width specifiers.
        String padding = " ".repeat(width - name.length());
        out.append("  ").append(brightCyan(name, ansi)).append(padding).append(firstLine).append(System.lineSeparator());
    }

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // No subcommand: print the curated short-help screen. The full screen
        // (every verb, every global option) is one keystroke away via --help.
        boolean ansi = spec.commandLine().getColorScheme().ansi().enabled();
        printShortHelp(spec, System.out, ansi);
    }

    /**
     * Render the abbreviated help shown when the user runs bare {@code jk}.
     * Lists a curated subset of verbs (see {@link #SHORT_COMMAND_GROUPS}) plus
     * a "More commands:" footer pointing at {@code jk --help}. Reuses the
     * bright-green heading / bright-cyan name styling from the full screen.
     */
    static void printShortHelp(CommandSpec rootSpec, java.io.PrintStream out, boolean ansi) {
        for (String line : rootSpec.usageMessage().description()) {
            out.println(line);
        }
        out.println();
        String qualifiedName = rootSpec.qualifiedName();
        if (ansi) {
            out.println("\033[1;92mUsage:\033[0m \033[1;96m" + qualifiedName
                    + "\033[0m\033[96m <COMMAND> [OPTIONS]\033[0m");
        } else {
            out.println("Usage: " + qualifiedName + " <COMMAND> [OPTIONS]");
        }
        out.println();

        Map<String, CommandLine> subs = rootSpec.subcommands();
        int nameWidth = 0;
        for (CommandGroup group : SHORT_COMMAND_GROUPS) {
            for (String n : group.names()) {
                if (subs.containsKey(n)) nameWidth = Math.max(nameWidth, n.length());
            }
        }
        int descCol = 2 + nameWidth + 4;

        for (CommandGroup group : SHORT_COMMAND_GROUPS) {
            out.println(brightGreen(group.heading(), ansi));
            for (String n : group.names()) {
                CommandLine sub = subs.get(n);
                if (sub == null) continue;
                if (sub.getCommandSpec().usageMessage().hidden()) continue;
                String[] desc = sub.getCommandSpec().usageMessage().description();
                String firstLine = desc.length > 0 ? desc[0] : "";
                String padding = " ".repeat(descCol - 2 - n.length());
                out.println("  " + brightCyan(n, ansi) + padding + firstLine);
            }
        }

        out.println(brightGreen("More commands:", ansi));
        String ellipsis = "...";
        String ellipsisPad = " ".repeat(descCol - 4 - ellipsis.length());
        String prefix = "See all commands and options by running ";
        String helpCmd = "jk --help";
        String coloredHelp = ansi ? "\033[33m" + helpCmd + "\033[0m" : helpCmd;
        out.println("    " + brightCyan(ellipsis, ansi) + ellipsisPad + prefix + coloredHelp);
    }
}
