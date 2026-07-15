// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

import build.jumpkick.cli.args.Abbreviations;
import build.jumpkick.cli.args.ArgParser;
import build.jumpkick.cli.args.ParseException;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.command.ActivateCommand;
import build.jumpkick.command.AddCommand;
import build.jumpkick.command.AuditCommand;
import build.jumpkick.command.AuthCommand;
import build.jumpkick.command.BuildCommand;
import build.jumpkick.command.CacheCommand;
import build.jumpkick.command.CleanCommand;
import build.jumpkick.command.CompileCommand;
import build.jumpkick.command.DevCommand;
import build.jumpkick.command.EngineCommand;
import build.jumpkick.command.SelfCommand;
import build.jumpkick.command.WrapperCommand;
import build.jumpkick.command.HistoryCommand;
import build.jumpkick.command.DeactivateCommand;
import build.jumpkick.command.DenyCommand;
import build.jumpkick.command.DoctorCommand;
import build.jumpkick.command.ExplainCommand;
import build.jumpkick.command.ExportCommand;
import build.jumpkick.command.FormatCommand;
import build.jumpkick.command.GradleCommand;
import build.jumpkick.command.HookEnvCommand;
import build.jumpkick.command.IdeCommand;
import build.jumpkick.command.ImageCommand;
import build.jumpkick.command.ImportCommand;
import build.jumpkick.command.InitCommand;
import build.jumpkick.command.InstallCommand;
import build.jumpkick.command.JdkCommand;
import build.jumpkick.command.LibraryCommand;
import build.jumpkick.command.LockCommand;
import build.jumpkick.command.MvnCommand;
import build.jumpkick.command.NativeCommand;
import build.jumpkick.command.NewCommand;
import build.jumpkick.command.OutdatedCommand;
import build.jumpkick.command.PublishCommand;
import build.jumpkick.command.RemoveCommand;
import build.jumpkick.command.RepoCommand;
import build.jumpkick.command.RunCommand;
import build.jumpkick.command.ShellCommand;
import build.jumpkick.command.StatusCommand;
import build.jumpkick.command.SyncCommand;
import build.jumpkick.command.TestCommand;
import build.jumpkick.command.ToolCommand;
import build.jumpkick.command.ToolInstallCommand;
import build.jumpkick.command.ToolRunCommand;
import build.jumpkick.command.TrustCommand;
import build.jumpkick.command.TreeCommand;
import build.jumpkick.command.UpdateCommand;
import build.jumpkick.command.VerifyBuildCommand;
import build.jumpkick.command.VscodeCommand;
import build.jumpkick.command.WhyCommand;
import build.jumpkick.config.JkConfig;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Command;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
import build.jumpkick.worker.WorkerJarNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes every command through jk's own {@link ArgParser} + {@link HelpRenderer} (picocli is long
 * gone; the registry below is the complete command surface).
 *
 * <p>Handles both leaf commands and parent groups (a {@link CliCommand} with subcommands) — {@code jk
 * jdk install …} descends into the parent, finds the sub-command, and dispatches it; {@code jk
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
            new VscodeCommand(),
            new ExportCommand(),
            new ImportCommand(),
            new PublishCommand(),
            // `run` and `install` are the primary commands; the same instances are also mounted
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
     * If the command names a ported command, parse and run it (descending through subcommands as
     * needed), returning the exit code; otherwise return {@code null} so the caller falls back to
     * picocli.
     */
    public static Integer tryDispatch(String[] args) {
        List<String> all = List.of(args);
        int commandAt = commandIndex(all);
        if (commandAt < 0) return null;
        String command = all.get(commandAt);
        Abbreviations.Result<CliCommand> r = Abbreviations.resolve(command, BY_NAME);
        if (r.kind() == Abbreviations.Kind.AMBIGUOUS) {
            printAmbiguousCommand(command, r.candidates(), ansiEnabled());
            return 2;
        }
        CliCommand cmd = r.value();
        if (cmd == null) {
            // Not a jk command — a build plugin may declare it (build-plugins plan row 11).
            // Only when a project manifest is present, and never by SPAWNING an engine for a
            // typo: the hosted attempt requires a live socket; the test seam runs in-process.
            Integer pluginExit = tryPluginCommand(command, all.subList(commandAt + 1, all.size()));
            if (pluginExit != null) return pluginExit;
            return null; // let the caller show top-level help
        }
        return dispatch(cmd, "jk " + cmd.name(), all.subList(commandAt + 1, all.size()), ansiEnabled());
    }

    /**
     * Attempt {@code command} as a plugin-declared command. Returns the exit code when a plugin
     * owned and ran it, else {@code null} (no jk.toml, no reachable engine, no owning plugin —
     * the normal unknown-command help follows).
     */
    private static Integer tryPluginCommand(String command, List<String> args) {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(dir.resolve("jk.toml"))) return null;
        try {
            build.jumpkick.engine.protocol.PluginCommandReport report;
            if (Boolean.getBoolean("jk.test.noEngine")
                    || "build.jumpkick.testrunner.TestRunnerPlugin".equals(System.getProperty("jk.plugin.class"))) {
                report = build.jumpkick.cli.engine.InProcessEngine.require()
                        .pluginCommand(dir, build.jumpkick.util.JkDirs.cache(), command, args);
            } else {
                var paths = build.jumpkick.engine.EnginePaths.current();
                if (!build.jumpkick.cli.engine.EngineClient.ping(build.jumpkick.engine.EnginePaths.activeSocket(paths))) return null;
                report = build.jumpkick.cli.engine.EngineClient.pluginCommand(
                        paths, dir, build.jumpkick.util.JkDirs.cache(), command, args);
            }
            if (!report.found()) return null;
            if (report.error() != null) {
                CliOutput.err("jk " + command + ": " + report.error());
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
            int subAt = commandIndex(rest);
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

    /** Resolve a sub-command (exact or unique prefix) against a parent's subcommands and their aliases. */
    private static Abbreviations.Result<CliCommand> resolveSub(CliCommand parent, String name) {
        Map<String, CliCommand> byName = new LinkedHashMap<>();
        for (CliCommand sub : parent.subcommands()) {
            byName.put(sub.name(), sub);
            for (String alias : sub.aliases()) byName.put(alias, sub);
        }
        return Abbreviations.resolve(name, byName);
    }

    /**
     * A parse model = the command's own options plus the shared global options. A name collision
     * between the two is a programming error and fails loudly: ArgParser indexes names last-wins,
     * so a silent merge would let a global quietly replace a command's option (this once broke
     * `jk self update <version>` — the global -V/--version flag ate the command's value option).
     */
    private static Command withGlobals(CliCommand cmd) {
        List<Opt> opts = new ArrayList<>(cmd.options());
        java.util.Set<String> own = new java.util.HashSet<>();
        for (Opt opt : opts) own.addAll(opt.names());
        for (Opt global : GlobalOptions.globalOpts()) {
            for (String n : global.names()) {
                if (own.contains(n)) {
                    throw new IllegalStateException("command '" + cmd.name() + "' declares option " + n
                            + ", which collides with the global option " + n
                            + " — rename the command's option");
                }
            }
        }
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
        List<build.jumpkick.cli.OptionModel> globals = new ArrayList<>();
        for (Opt g : GlobalOptions.globalOpts()) {
            if (!g.hidden()) globals.add(CommandModels.option(g));
        }
        CommandModel model = CommandModels.from(cmd, qualified, globals);
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

    private static void printAmbiguousCommand(String command, List<String> candidates, boolean ansi) {
        System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi)
                + " '"
                + HelpRenderer.paint(command, Theme.active().highlight(), ansi)
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
     * Index of the command — the first non-option token, skipping any leading global options (and the
     * value of a value-taking one, e.g. {@code -C dir}). Returns -1 when there's none (bare {@code
     * jk}, or {@code jk --help}).
     */
    static int commandIndex(List<String> args) {
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
            if (!a.startsWith("-") || a.equals("-")) return i; // first positional = command
            String name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
            // A value-taking global before the command (exact or unique prefix, e.g. --dir for
            // --directory) consumes the next token, so skip both to reach the command.
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
        JkConfig.ColorChoice choice = build.jumpkick.config.SessionContext.current().config().colorOr(JkConfig.ColorChoice.AUTO);
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
