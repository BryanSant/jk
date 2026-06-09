// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.args.ArgParser;
import dev.jkbuild.cli.args.ParseException;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.command.CleanCommand;
import dev.jkbuild.command.DeactivateCommand;
import dev.jkbuild.command.ExplainCommand;
import dev.jkbuild.command.TreeCommand;
import dev.jkbuild.command.WhyCommand;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Command;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes verbs that have been ported off picocli through jk's own
 * {@link ArgParser} + {@link HelpRenderer}; everything else falls back to
 * picocli. This is the coexistence seam for Phase 3 (docs/plugin-refactor.md §5):
 * commands move into the registry below one tranche at a time, and picocli is
 * deleted once it's empty on the other side.
 */
public final class CommandDispatch {

    private CommandDispatch() {}

    /** Commands parsed/run by jk's own parser. Grows as commands are ported. */
    private static final List<CliCommand> COMMANDS = List.of(
            new CleanCommand(),
            new TreeCommand(),
            new WhyCommand(),
            new ExplainCommand(),
            new DeactivateCommand());

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
     * If {@code args[0]} names a ported command, parse and run it, returning the
     * exit code; otherwise return {@code null} so the caller falls back to
     * picocli. Global flags ({@code -q}, {@code -C}, …) are accepted (their
     * effect was already folded into {@link ActiveConfig} before dispatch);
     * {@code --help} / {@code --version} are handled here.
     */
    public static Integer tryDispatch(String[] args) {
        int verbAt = verbIndex(args);
        if (verbAt < 0) return null;
        CliCommand cmd = BY_NAME.get(args[verbAt]);
        if (cmd == null) return null;

        boolean ansi = ansiEnabled();
        // Args after the verb. Global options before the verb were already folded
        // into ActiveConfig by Jk.applyCliOverrides, so dropping them here is safe.
        List<String> rest = List.of(args).subList(verbAt + 1, args.length);

        Invocation in;
        try {
            in = ArgParser.parse(withGlobals(cmd), rest);
        } catch (ParseException e) {
            printError(cmd, e, ansi);
            return 2;
        }
        if (in.isSet("help")) {
            System.out.print(renderHelp(cmd, ansi));
            return 0;
        }
        if (in.isSet("version")) {
            System.out.println("jk " + Jk.VERSION);
            return 0;
        }
        try {
            return cmd.run(in);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println(HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi) + " " + msg);
            return 1;
        }
    }

    /** A parse model = the command's own options plus the shared global options. */
    private static Command withGlobals(CliCommand cmd) {
        List<Opt> opts = new ArrayList<>(cmd.options());
        opts.addAll(GlobalOptions.globalOpts());
        return new Command() {
            @Override public String name() { return cmd.name(); }
            @Override public String description() { return cmd.description(); }
            @Override public List<Opt> options() { return opts; }
            @Override public List<Param> parameters() { return cmd.parameters(); }
        };
    }

    private static String renderHelp(CliCommand cmd, boolean ansi) {
        List<OptionModel> globals = GlobalOptions.globalOpts().stream()
                .filter(o -> !o.hidden()).map(CommandModels::option).toList();
        CommandModel model = CommandModels.from(cmd, "jk " + cmd.name(), globals);
        return HelpRenderer.renderHelp(model, ansi);
    }

    private static void printError(CliCommand cmd, ParseException e, boolean ansi) {
        String label = HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi);
        String message = switch (e.kind()) {
            case UNKNOWN_OPTION -> "unrecognized option '"
                    + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi) + "'";
            case MISSING_REQUIRED -> "missing required argument "
                    + HelpRenderer.paint(e.token(), Theme.active().highlight(), ansi);
            default -> e.getMessage();
        };
        System.err.println(label + " " + message);
        System.err.println();
        System.err.println(usageLine(cmd, ansi));
        System.err.println();
        System.err.println(ansi
                ? "For more information, try '" + Ansi.sgr(Theme.active().helpHint()) + "--help" + Ansi.RESET + "'"
                : "For more information, try '--help'");
    }

    private static String usageLine(CliCommand cmd, boolean ansi) {
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
        String name = "jk " + cmd.name();
        if (ansi) {
            return HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true) + " "
                    + HelpRenderer.paint(name, Theme.active().commandName(), true)
                    + HelpRenderer.paint(suffix.toString(), Theme.active().paramLabel(), true);
        }
        return "Usage: " + name + suffix;
    }

    /**
     * Index of the verb — the first non-option token, skipping any leading
     * global options (and the value of a value-taking one, e.g. {@code -C dir}).
     * Returns -1 when there's no verb (bare {@code jk}, or {@code jk --help}).
     */
    static int verbIndex(String[] args) {
        java.util.Set<String> valueGlobals = new java.util.HashSet<>();
        for (Opt o : GlobalOptions.globalOpts()) {
            if (o.takesValue()) valueGlobals.addAll(o.names());
        }
        int i = 0;
        while (i < args.length) {
            String a = args[i];
            if (a.equals("--")) return i + 1 < args.length ? i + 1 : -1;
            if (!a.startsWith("-") || a.equals("-")) return i;   // first positional = verb
            String name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
            // A value-taking global in `--name value` form consumes the next token too.
            i += (valueGlobals.contains(name) && !a.contains("=")) ? 2 : 1;
        }
        return -1;
    }

    static boolean ansiEnabled() {
        JkConfig.ColorChoice choice = ActiveConfig.get().colorOr(JkConfig.ColorChoice.AUTO);
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
