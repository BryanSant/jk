// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.UsageMessageSpec;
import picocli.CommandLine.Spec;

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
        description = "Single-binary build tool for Java and Kotlin",
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

    /** Picocli root, configured for jk's passthrough semantics. */
    public static CommandLine newCommandLine() {
        CommandLine cmd = new CommandLine(new Jk());
        // mvn/gradle are passthroughs: jk owns flags listed before the tool's
        // own args, everything else (including unknown `-X` style flags) gets
        // forwarded as positional to the child process.
        for (String name : new String[] {"mvn", "gradle"}) {
            CommandLine sub = cmd.getSubcommands().get(name);
            if (sub != null) {
                sub.setUnmatchedOptionsArePositionalParams(true);
            }
        }
        // Render --help with verbs partitioned into named sections (see
        // COMMAND_GROUPS) instead of one flat "Commands:" list.
        cmd.getHelpSectionMap().remove(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING);
        cmd.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, Jk::renderGroupedSubcommands);
        // Nested subcommands (`jk jdk --help`, etc.) use picocli's default
        // renderer which prints "name, alias" for aliased verbs. Strip aliases
        // there too — they remain functional, just hidden from --help.
        installAliasFreeRenderer(cmd);
        return cmd;
    }

    private static void installAliasFreeRenderer(CommandLine parent) {
        for (CommandLine sub : parent.getSubcommands().values()) {
            sub.getHelpSectionMap().put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, Jk::renderFlatSubcommands);
            installAliasFreeRenderer(sub);
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
            out.append(group.heading()).append(nl);
            for (String name : visible) {
                appendCommandRow(out, name, byName.get(name), width);
                placed.add(name);
            }
        }

        List<String> leftover = byName.keySet().stream()
                .filter(n -> !placed.contains(n))
                .sorted()
                .toList();
        if (!leftover.isEmpty()) {
            if (!first) out.append(nl);
            out.append("Other commands:").append(nl);
            for (String name : leftover) {
                appendCommandRow(out, name, byName.get(name), width);
            }
        }
        return out.toString();
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
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Help> e : byName.entrySet()) {
            appendCommandRow(out, e.getKey(), e.getValue(), width);
        }
        return out.toString();
    }

    private static void appendCommandRow(StringBuilder out, String name, Help sub, int width) {
        String[] desc = sub.commandSpec().usageMessage().description();
        String firstLine = desc.length > 0 ? desc[0] : "";
        out.append(String.format("  %-" + width + "s%s%n", name, firstLine));
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
