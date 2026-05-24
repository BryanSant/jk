// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

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
        mixinStandardHelpOptions = true,
        version = "jk " + Jk.VERSION,
        description = "A fast build tool and package manager for Java/Kotlin",
        subcommands = {
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
                WhyRebuiltCommand.class,
                JdkCommand.class,
                EnvCommand.class,
                HookCommand.class,
                ShellCommand.class,
                MvnCommand.class,
                GradleCommand.class,
                ImportCommand.class,
                ExportCommand.class,
                ExecCommand.class,
                InstallCommand.class,
                RunCommand.class,
                PublishCommand.class,
                AuditCommand.class,
                DenyCommand.class,
                ImageCommand.class,
                NativeCommand.class,
                VerifyBuildCommand.class,
                ToolCommand.class,
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
            Map.entry("generate", List.of("init")),           // Maven mvn archetype:generate
            Map.entry("dependencies", List.of("tree")),       // Gradle gradle dependencies
            Map.entry("package", List.of("build")),           // Maven mvn package
            Map.entry("deploy", List.of("publish")),          // Maven mvn deploy
            Map.entry("upgrade", List.of("update")),          // npm/yarn/apt vocabulary
            Map.entry("sh", List.of("shell")),
            Map.entry("bash", List.of("shell")),
            Map.entry("nativeCompile", List.of("native")),    // Gradle :nativeCompile task
            Map.entry("verify-target", List.of("verify-build")),
            Map.entry("check", List.of("compile")));          // renamed verb; check kept for back-compat

    public static void main(String[] args) {
        dev.jkbuild.cli.tui.GlobalCancel.install();
        System.exit(execute(args));
    }

    /** Run jk with the given argv. The first positional is rewritten if it's a known alias. */
    public static int execute(String... args) {
        return newCommandLine().execute(rewriteAlias(args));
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
        // Enable `-h` / `--help` on every command at every depth. Skip mvn/gradle so their
        // `--help` continues to forward to the wrapped tool. (The top-level Jk already
        // declares `mixinStandardHelpOptions = true`; the call is idempotent.)
        enableHelpEverywhere(cmd);
        // Apply the custom "description / Usage / commands" layout to every command that
        // has subcommands — top-level `jk` plus nested parents like `jk jdk`, `jk tool`,
        // `jk cache`. Leaf commands keep picocli's default formatting so their option /
        // parameter lists stay visible.
        applyParentLayout(cmd);
        applyParentLayoutRecursively(cmd);
        // Replace picocli's default "Unmatched argument …" error with a cargo-style
        // "error: / tip: / Usage: / For more information" block. Propagates to subcommands.
        cmd.setParameterExceptionHandler(Jk::handleParameterException);
        return cmd;
    }

    private static void enableHelpEverywhere(CommandLine cmd) {
        if (!PASSTHROUGH_COMMANDS.contains(cmd.getCommandName())) {
            cmd.getCommandSpec().mixinStandardHelpOptions(true);
        }
        for (CommandLine sub : cmd.getSubcommands().values()) {
            enableHelpEverywhere(sub);
        }
    }

    private static void applyParentLayoutRecursively(CommandLine parent) {
        for (CommandLine sub : parent.getSubcommands().values()) {
            if (!sub.getSubcommands().isEmpty()) {
                applyParentLayout(sub);
            } else {
                // Picocli's UsageMessageSpec inherits sectionKeys from the parent's spec, so
                // our parent layout would otherwise leak into leaf commands. Reset to defaults
                // so leaves still surface their option / parameter lists.
                sub.setHelpSectionKeys(DEFAULT_SECTION_KEYS);
            }
            applyParentLayoutRecursively(sub);
        }
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
    }

    /**
     * Top-level verb groupings for --help. Order within each group is rough
     * lifecycle / workflow order (create → manage → build → distribute →
     * verify), not alphabetical. Any registered subcommand that doesn't
     * appear here is listed under "Other commands:" as a safety net.
     */
    private record CommandGroup(String heading, List<String> names) {}

    private static final List<CommandGroup> COMMAND_GROUPS = List.of(
            new CommandGroup("Project commands:", List.of(
                    "init",
                    "add", "remove",
                    "lock", "update", "sync",
                    "tree", "why",
                    "compile", "build", "test", "run", "clean",
                    "explain", "why-rebuilt",
                    "install", "publish", "image", "native",
                    "audit", "deny", "verify-build")),
            new CommandGroup("Toolchain commands:", List.of(
                    "jdk", "env", "shell", "hook",
                    "tool", "exec")),
            new CommandGroup("Interop commands:", List.of(
                    "import", "mvn", "gradle", "export")),
            new CommandGroup("System commands:", List.of(
                    "doctor", "cache")));

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
        width += 2;

        boolean ansi = help.colorScheme().ansi().enabled();
        Set<String> placed = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        // Separate this section from the preceding options block.
        out.append(nl);
        boolean first = true;
        for (CommandGroup group : COMMAND_GROUPS) {
            List<String> visible = group.names().stream()
                    .filter(byName::containsKey)
                    .toList();
            if (visible.isEmpty()) continue;
            if (!first) out.append(nl);
            first = false;
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
            if (!first) out.append(nl);
            out.append(brightGreen("Other commands:", ansi)).append(nl);
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
     * "{@code <name> <COMMAND> [OPTIONS]}" — name is bold+bright-cyan, the rest is plain
     * bright-cyan. Replaces picocli's auto-generated synopsis (which would list every
     * registered flag) with a fixed, abstract form.
     */
    private static String renderSynopsis(Help help) {
        boolean ansi = help.colorScheme().ansi().enabled();
        String name = help.commandSpec().qualifiedName();
        String suffix = " <COMMAND> [OPTIONS]";
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
        // No subcommand: print help via the active CommandLine so the grouped
        // subcommand renderer registered in newCommandLine() is used. A fresh
        // `new CommandLine(this)` would bypass that customization.
        spec.commandLine().usage(System.out);
    }
}
