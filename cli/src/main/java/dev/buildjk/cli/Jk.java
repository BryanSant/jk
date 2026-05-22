// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Map;

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
                FetchCommand.class,
                CompileCommand.class,
                BuildCommand.class,
                TestCommand.class,
                CleanCommand.class,
                ExplainCommand.class,
                WhyRebuiltCommand.class,
                JdkCommand.class,
                EnvCommand.class,
                ShellCommand.class,
                MvnCommand.class,
                GradleCommand.class,
                ImportCommand.class,
                ExportCommand.class,
                InstallCommand.class,
                JkxCommand.class,
                RunCommand.class,
                PublishCommand.class,
                AuditCommand.class,
                DenyCommand.class,
                ImageCommand.class,
                NativeCommand.class,
                VerifyBuildCommand.class,
                ToolReconcileCommand.class,
                CacheCommand.class,
        })
public final class Jk implements Runnable {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    /**
     * Hidden verb aliases for ergonomic migration from other build tools.
     * Documented in {@code docs/aliases.md}. Keys are alias names; values
     * are the canonical jk verb. We don't register these with picocli (so
     * they stay out of {@code --help} and shell completion); instead we
     * rewrite the first positional arg before parsing.
     */
    static final Map<String, String> VERB_ALIASES = Map.ofEntries(
            Map.entry("generate", "init"),         // Maven mvn archetype:generate
            Map.entry("dependencies", "tree"),     // Gradle gradle dependencies
            Map.entry("package", "build"),         // Maven mvn package
            Map.entry("deploy", "publish"),        // Maven mvn deploy
            Map.entry("upgrade", "update"),        // npm/yarn/apt vocabulary
            Map.entry("sh", "shell"),
            Map.entry("bash", "shell"),
            Map.entry("nativeCompile", "native"),  // Gradle :nativeCompile task
            Map.entry("verify-target", "verify-build"),
            Map.entry("check", "compile"));        // renamed verb; check kept for back-compat

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** Run jk with the given argv. The first positional is rewritten if it's a known alias. */
    public static int execute(String... args) {
        return newCommandLine().execute(rewriteAlias(args));
    }

    static String[] rewriteAlias(String[] args) {
        if (args.length == 0) return args;
        String mapped = VERB_ALIASES.get(args[0]);
        if (mapped == null) return args;
        String[] out = args.clone();
        out[0] = mapped;
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
        return cmd;
    }

    @Override
    public void run() {
        // No subcommand: print help and exit non-zero per the CLI usage convention.
        new CommandLine(this).usage(System.out);
    }
}
