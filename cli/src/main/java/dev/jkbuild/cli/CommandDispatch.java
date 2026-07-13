// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.args.Abbreviations;
import dev.jkbuild.cli.args.ArgParser;
import dev.jkbuild.cli.args.ParseException;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.command.ActivateCommand;
import dev.jkbuild.command.AddCommand;
import dev.jkbuild.command.AuditCommand;
import dev.jkbuild.command.AuthCommand;
import dev.jkbuild.command.BuildCommand;
import dev.jkbuild.command.CacheCommand;
import dev.jkbuild.command.CleanCommand;
import dev.jkbuild.command.CompileCommand;
import dev.jkbuild.command.DevCommand;
import dev.jkbuild.command.EngineCommand;
import dev.jkbuild.command.SelfCommand;
import dev.jkbuild.command.WrapperCommand;
import dev.jkbuild.command.HistoryCommand;
import dev.jkbuild.command.DeactivateCommand;
import dev.jkbuild.command.DenyCommand;
import dev.jkbuild.command.DoctorCommand;
import dev.jkbuild.command.ExplainCommand;
import dev.jkbuild.command.ExportCommand;
import dev.jkbuild.command.FormatCommand;
import dev.jkbuild.command.GradleCommand;
import dev.jkbuild.command.HookEnvCommand;
import dev.jkbuild.command.IdeCommand;
import dev.jkbuild.command.IdeaCommand;
import dev.jkbuild.command.ImageCommand;
import dev.jkbuild.command.ImportCommand;
import dev.jkbuild.command.InitCommand;
import dev.jkbuild.command.InstallCommand;
import dev.jkbuild.command.JdkCommand;
import dev.jkbuild.command.LibraryCommand;
import dev.jkbuild.command.LockCommand;
import dev.jkbuild.command.MvnCommand;
import dev.jkbuild.command.NativeCommand;
import dev.jkbuild.command.NewCommand;
import dev.jkbuild.command.OutdatedCommand;
import dev.jkbuild.command.PublishCommand;
import dev.jkbuild.command.RemoveCommand;
import dev.jkbuild.command.RepoCommand;
import dev.jkbuild.command.RunCommand;
import dev.jkbuild.command.ShellCommand;
import dev.jkbuild.command.StatusCommand;
import dev.jkbuild.command.SyncCommand;
import dev.jkbuild.command.TestCommand;
import dev.jkbuild.command.ToolCommand;
import dev.jkbuild.command.ToolInstallCommand;
import dev.jkbuild.command.ToolRunCommand;
import dev.jkbuild.command.TrustCommand;
import dev.jkbuild.command.TreeCommand;
import dev.jkbuild.command.UpdateCommand;
import dev.jkbuild.command.VerifyBuildCommand;
import dev.jkbuild.command.VscodeCommand;
import dev.jkbuild.command.WhyCommand;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Command;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.worker.WorkerJarNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes verbs that have been ported off picocli through jk's own {@link ArgParser} + {@link
 * HelpRenderer}; everything else falls back to picocli. This is the coexistence seam for Phase 3
 * (docs/plugin-refactor.md §5): commands move into the registry below one tranche at a time, and
 * picocli is deleted once it's empty on the other side.
 *
 * <p>Handles both leaf verbs and parent groups (a {@link CliCommand} with subcommands) — {@code jk
 * jdk install …} descends into the parent, finds the sub-verb, and dispatches it; {@code jk
 * jdk}/{@code jk jdk --help} prints the parent's command list.
 */
public final class CommandDispatch {

    private CommandDispatch() {}

    /** Commands parsed/run by jk's own parser. Grows as commands are ported. */
    private static final List<CliCommand> COMMANDS = List.of(
            new CleanCommand(),
            new EngineCommand(),
            new SelfCommand(),
            new WrapperCommand(),
            new HistoryCommand(),
            new StatusCommand(),
            new TreeCommand(),
            new WhyCommand(),
            new ExplainCommand(),
            new DeactivateCommand(),
            new ShellCommand(),
            new HookEnvCommand(),
            new LockCommand(),
            new SyncCommand(),
            new AddCommand(),
            new RemoveCommand(),
            new UpdateCommand(),
            new OutdatedCommand(),
            new DoctorCommand(),
            new DenyCommand(),
            new VerifyBuildCommand(),
            new AuditCommand(),
            new AuthCommand(),
            new CacheCommand(),
            new JdkCommand(),
            new ToolCommand(),
            new TrustCommand(),
            new LibraryCommand(),
            new RepoCommand(),
            new IdeCommand(),
            new IdeaCommand(),
            new VscodeCommand(),
            new ExportCommand(),
            new ImportCommand(),
            new PublishCommand(),
            // `run` and `install` are the primary verbs; the same instances are also mounted
            // under `jk tool` (one implementation, two mounts — tool-targets-plan §9 inverted
            // 2026-07-09: the short spelling is canonical).
            new ToolRunCommand(),
            new ToolInstallCommand(),
            new CompileCommand(),
            new DevCommand(),
            new BuildCommand(),
            new TestCommand(),
            new FormatCommand(),
            new NativeCommand(),
            new ImageCommand(),
            new MvnCommand(),
            new GradleCommand(),
            new ActivateCommand(),
            new NewCommand(),
            new InitCommand());

    private static final Map<String, CliCommand> BY_NAME = index();

    private static Map<String, CliCommand> index() {
        Map<String, CliCommand> m = new LinkedHashMap<>();
        for (CliCommand c : COMMANDS) {
            m.put(c.name(), c);
            for (String alias : c.aliases()) m.put(alias, c);
        }
        return m;
    }

    /** The ported commands — used to list them in the top-level (picocli) help. */
    public static List<CliCommand> commands() {
        return COMMANDS;
    }

    /**
     * If the verb names a ported command, parse and run it (descending through subcommands as
     * needed), returning the exit code; otherwise return {@code null} so the caller falls back to
     * picocli.
     */
    public static Integer tryDispatch(String[] args) {
        List<String> all = List.of(args);
        int verbAt = verbIndex(all);
        if (verbAt < 0) return null;
        String verb = all.get(verbAt);
        Abbreviations.Result<CliCommand> r = Abbreviations.resolve(verb, BY_NAME);
        if (r.kind() == Abbreviations.Kind.AMBIGUOUS) {
            printAmbiguousVerb(verb, r.candidates(), ansiEnabled());
            return 2;
        }
        CliCommand cmd = r.value();
        if (cmd == null) {
            // Not a jk verb — a build plugin may declare it (build-plugins plan row 11).
            // Only when a project manifest is present, and never by SPAWNING an engine for a
            // typo: the hosted attempt requires a live socket; the test seam runs in-process.
            Integer pluginExit = tryPluginVerb(verb, all.subList(verbAt + 1, all.size()));
            if (pluginExit != null) return pluginExit;
            return null; // let the caller show top-level help
        }
        return dispatch(cmd, "jk " + cmd.name(), all.subList(verbAt + 1, all.size()), ansiEnabled());
    }

    /**
     * Attempt {@code verb} as a plugin-declared command. Returns the exit code when a plugin
     * owned and ran it, else {@code null} (no jk.toml, no reachable engine, no owning plugin —
     * the normal unknown-verb help follows).
     */
    private static Integer tryPluginVerb(String verb, List<String> args) {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(dir.resolve("jk.toml"))) return null;
        try {
            dev.jkbuild.engine.protocol.PluginVerbReport report;
            if (Boolean.getBoolean("jk.test.noEngine")
                    || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"))) {
                report = dev.jkbuild.cli.engine.InProcessEngine.require()
                        .pluginVerb(dir, dev.jkbuild.util.JkDirs.cache(), verb, args);
            } else {
                var paths = dev.jkbuild.engine.EnginePaths.current();
                if (!dev.jkbuild.cli.engine.EngineClient.ping(paths.socket())) return null;
                report = dev.jkbuild.cli.engine.EngineClient.pluginVerb(
                        paths, dir, dev.jkbuild.util.JkDirs.cache(), verb, args);
            }
            if (!report.found()) return null;
            if (report.error() != null) {
                CliOutput.err("jk " + verb + ": " + report.error());
                return 1;
            }
            for (String line : report.output()) CliOutput.out(line);
            return report.exit();
        } catch (Exception e) {
            return null; // best-effort — fall back to the normal help
        }
    }

    /** Dispatch {@code cmd} against {@code rest} (its arguments), descending into subcommands. */
    private static int dispatch(CliCommand cmd, String qualified, List<String> rest, boolean ansi) {
        if (!cmd.subcommands().isEmpty()) {
            int subAt = verbIndex(rest);
            if (subAt < 0) {
                // No subcommand: print the group's command list. `--help` is a
                // request (exit 0); bare `jk <group>` is a usage error (64).
                System.out.print(renderHelp(cmd, qualified, ansi));
                return helpRequested(cmd, rest) ? 0 : 64;
            }
            String subName = rest.get(subAt);
            Abbreviations.Result<CliCommand> r = resolveSub(cmd, subName);
            if (r.kind() == Abbreviations.Kind.AMBIGUOUS) {
                printAmbiguousSubcommand(cmd, qualified, subName, r.candidates(), ansi);
                return 2;
            }
            CliCommand sub = r.value();
            if (sub == null) {
                printUnknownSubcommand(cmd, qualified, subName, ansi);
                return 2;
            }
            return dispatch(sub, qualified + " " + sub.name(), rest.subList(subAt + 1, rest.size()), ansi);
        }

        // --help wins over parse validation (e.g. a missing required argument),
        // matching picocli — so `jk <cmd> --help` always shows help.
        if (helpRequested(cmd, rest)) {
            System.out.print(renderHelp(cmd, qualified, ansi));
            return 0;
        }
        Invocation in;
        try {
            in = ArgParser.parse(withGlobals(cmd), rest, cmd.passthrough());
        } catch (ParseException e) {
            printError(qualified, cmd, e, ansi);
            return 2;
        }
        if (in.isSet("version")) {
            System.out.println("jk " + Jk.VERSION);
            return 0;
        }
        try {
            return cmd.run(in);
        } catch (WorkerJarNotFoundException e) {
            printWorkerJarError(e, ansi);
            return 1;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi) + " " + msg);
            return 1;
        }
    }

    /** Resolve a sub-verb (exact or unique prefix) against a parent's subcommands and their aliases. */
    private static Abbreviations.Result<CliCommand> resolveSub(CliCommand parent, String name) {
        Map<String, CliCommand> byName = new LinkedHashMap<>();
        for (CliCommand sub : parent.subcommands()) {
            byName.put(sub.name(), sub);
            for (String alias : sub.aliases()) byName.put(alias, sub);
        }
        return Abbreviations.resolve(name, byName);
    }

    /** A parse model = the command's own options plus the shared global options. */
    private static Command withGlobals(CliCommand cmd) {
        List<Opt> opts = new ArrayList<>(cmd.options());
        opts.addAll(GlobalOptions.globalOpts());
        return new Command() {
            @Override
            public String name() {
                return cmd.name();
            }

            @Override
            public String description() {
                return cmd.description();
            }

            @Override
            public List<Opt> options() {
                return opts;
            }

            @Override
            public List<Param> parameters() {
                return cmd.parameters();
            }
        };
    }

    private static String renderHelp(CliCommand cmd, String qualified, boolean ansi) {
        CommandModel model = CommandModels.from(cmd, qualified, List.of());
        return HelpRenderer.renderHelp(model, ansi);
    }

    private static void printWorkerJarError(WorkerJarNotFoundException e, boolean ansi) {
        Theme t = Theme.active();
        String label = HelpRenderer.paint(Glyphs.CROSS + " Error:", t.errorLabel(), ansi);
        String jar = HelpRenderer.paint(e.artifactId() + ".jar", t.warning(), ansi);
        String coord = HelpRenderer.paint(e.coordinate(), t.cyan(), ansi);
        String jk = ansi ? Ansi.sgr(t.helpHint()) + "jk" + Ansi.RESET : "jk";
        System.err.println(label + " " + jar + " not found.");
        System.err.println("  Coordinate: " + coord);
        for (java.nio.file.Path p : e.pathsChecked()) {
            System.err.println("  Checked:    " + HelpRenderer.paint(p.toString(), t.path(), ansi));
        }
        System.err.println("  Reinstall " + jk + " or set -D" + e.jarProperty() + "=<path>");
    }

    private static void printError(String qualified, CliCommand cmd, ParseException e, boolean ansi) {
        String label = HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi);
        String message =
                switch (e.kind()) {
                    case UNKNOWN_OPTION ->
                        "unrecognized option '"
                                + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi)
                                + "'";
                    case MISSING_REQUIRED ->
                        "missing required argument "
                                + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi);
                    default -> e.getMessage();
                };
        System.err.println(label + " " + message);
        System.err.println();
        System.err.println(usageLine(cmd, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printAmbiguousVerb(String verb, List<String> candidates, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " '"
                + HelpRenderer.paint(verb, Theme.active().highlight(), ansi)
                + "' is ambiguous: "
                + String.join(", ", candidates));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printAmbiguousSubcommand(
            CliCommand parent, String qualified, String sub, List<String> candidates, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " subcommand '"
                + HelpRenderer.paint(sub, Theme.active().highlight(), ansi)
                + "' is ambiguous: "
                + String.join(", ", candidates));
        System.err.println();
        System.err.println(usageLine(parent, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static void printUnknownSubcommand(CliCommand parent, String qualified, String sub, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " unrecognized subcommand '"
                + HelpRenderer.paint(sub, Theme.active().highlight(), ansi)
                + "'");
        System.err.println();
        System.err.println(usageLine(parent, qualified, ansi));
        System.err.println();
        System.err.println(helpHint(ansi));
    }

    private static String usageLine(CliCommand cmd, String qualified, boolean ansi) {
        StringBuilder suffix = new StringBuilder();
        if (cmd.isLeaf()) {
            for (Param p : cmd.parameters()) {
                if (p.hidden()) continue;
                suffix.append(" ").append(p.arity().required() ? "<" + p.name() + ">" : "[" + p.name() + "]");
            }
        } else {
            suffix.append(" <COMMAND>");
        }
        suffix.append(" [OPTIONS]");
        if (ansi) {
            return HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true)
                    + " "
                    + HelpRenderer.paint(qualified, Theme.active().commandName(), true)
                    + HelpRenderer.paint(suffix.toString(), Theme.active().paramLabel(), true);
        }
        return "Usage: " + qualified + suffix;
    }

    private static String helpHint(boolean ansi) {
        return ansi
                ? "For more information, try '" + Ansi.sgr(Theme.active().helpHint()) + "--help" + Ansi.RESET + "'"
                : "For more information, try '--help'";
    }

    /**
     * Index of the verb — the first non-option token, skipping any leading global options (and the
     * value of a value-taking one, e.g. {@code -C dir}). Returns -1 when there's none (bare {@code
     * jk}, or {@code jk --help}).
     */
    static int verbIndex(List<String> args) {
        Map<String, Opt> valueGlobals = new LinkedHashMap<>();
        for (Opt o : GlobalOptions.globalOpts()) {
            if (o.takesValue()) {
                for (String n : o.names()) valueGlobals.put(n, o);
            }
        }
        int i = 0;
        while (i < args.size()) {
            String a = args.get(i);
            if (a.equals("--")) return i + 1 < args.size() ? i + 1 : -1;
            if (!a.startsWith("-") || a.equals("-")) return i; // first positional = verb
            String name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
            // A value-taking global before the verb (exact or unique prefix, e.g. --dir for
            // --directory) consumes the next token, so skip both to reach the verb.
            boolean consumesNext = !a.contains("=") && Abbreviations.resolve(name, valueGlobals).resolved();
            i += consumesNext ? 2 : 1;
        }
        return -1;
    }

    /**
     * True when the argument list requests help — {@code -h}/{@code --help} exactly, or a unique
     * {@code --} prefix of {@code --help} that isn't ambiguous with any of this command's other
     * options (e.g. {@code --hel}). An ambiguous prefix is left for the parser to reject.
     */
    private static boolean helpRequested(CliCommand cmd, List<String> args) {
        Map<String, Opt> byName = new LinkedHashMap<>();
        for (Opt o : withGlobals(cmd).options()) {
            for (String n : o.names()) byName.put(n, o);
        }
        Opt help = byName.get("--help");
        for (String tok : args) {
            if (tok.equals("--")) break;
            if (tok.equals("-h") || tok.equals("--help")) return true;
            if (help != null && tok.startsWith("--")) {
                String name = tok.contains("=") ? tok.substring(0, tok.indexOf('=')) : tok;
                Abbreviations.Result<Opt> r = Abbreviations.resolve(name, byName);
                if (r.resolved() && r.value() == help) return true;
            }
        }
        return false;
    }

    static boolean ansiEnabled() {
        JkConfig.ColorChoice choice = dev.jkbuild.config.SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
        return switch (choice) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> {
                String nc = System.getenv("NO_COLOR");
                yield nc == null || nc.isEmpty();
            }
        };
    }
}
