// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.http.OfflineException;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.UnmatchedArgumentException;

import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders parse-time and expected-runtime errors as a cargo-style block: a
 * bold-red {@code error:} line, an optional {@code tip:} suggestion, a colored
 * {@code Usage:} line for the failing command, and a {@code --help} hint. Colors
 * come from the active {@link Theme}; escapes from {@link Ansi}.
 */
public final class ErrorRenderer {

    private ErrorRenderer() {}

    /**
     * Render parameter-parse failures in a cargo-style block. Returns picocli's
     * {@link CommandSpec#exitCodeOnInvalidInput} as the exit code.
     */
    public static int handleParameterException(ParameterException ex, String[] args) {
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

        if (ex instanceof MissingParameterException mpe) {
            err.println(formatMissingParameterLine(ansi, mpe));
        } else if (wrong != null) {
            boolean isOption = wrong.startsWith("-");
            String label = isOption ? "unrecognized option" : "unrecognized subcommand";
            err.println(formatErrorLine(ansi, label, wrong));
        } else {
            err.println(formatPlainErrorLine(ansi, ex.getMessage()));
        }
        err.println();
        if (suggestion != null) {
            err.println(formatTipLine(ansi, suggestion));
            err.println();
        }
        err.println(formatUsageLine(ansi, cmd.getCommandSpec()));
        err.println();
        err.println(formatHelpHint(ansi));
        err.flush();
        return cmd.getCommandSpec().exitCodeOnInvalidInput();
    }

    /**
     * Render expected runtime errors as a single {@code error:} line rather
     * than letting picocli print the whole stack trace. Anything we don't
     * recognise still gets the full trace so we don't silently swallow bugs.
     */
    public static int handleExecutionException(
            Exception ex,
            CommandLine cmd,
            ParseResult parseResult) throws Exception {
        if (ex instanceof OfflineException) {
            var err = cmd.getErr();
            boolean ansi = cmd.getColorScheme().ansi().enabled();
            String label = errorLabel(ansi) + " ";
            err.println(label + ex.getMessage());
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        }
        throw ex;
    }

    /** The bold-red {@code error:} prefix word, themed. */
    private static String errorLabel(boolean ansi) {
        return HelpRenderer.paint("error:", Theme.active().errorLabel(), ansi);
    }

    private static String formatErrorLine(boolean ansi, String label, String value) {
        return errorLabel(ansi) + " " + label + " '"
                + HelpRenderer.paint(value, Theme.active().highlight(), ansi) + "'";
    }

    /** A bold-red {@code error:} prefix followed by an already-formed message (no quoting). */
    private static String formatPlainErrorLine(boolean ansi, String message) {
        return errorLabel(ansi) + " " + message;
    }

    /**
     * A missing-required-argument notice in the softer "warning" register:
     * a yellow {@code ‼} sentinel, the plain {@code Missing required
     * parameter(s):} text, then the missing argument label(s) in yellow
     * (positionals as {@code <name>}, options by their longest name).
     */
    private static String formatMissingParameterLine(boolean ansi, MissingParameterException ex) {
        List<? extends ArgSpec> missing = ex.getMissing();
        String labels = missing.stream().map(ErrorRenderer::missingArgLabel)
                .collect(Collectors.joining(", "));
        String text = "Missing required parameter" + (missing.size() == 1 ? ": " : "s: ");
        return HelpRenderer.paint("‼", Theme.active().highlight(), ansi) + " " + text
                + HelpRenderer.paint(labels, Theme.active().highlight(), ansi);
    }

    /** Display label for a missing argument: {@code <name>} for positionals, the longest name for options. */
    private static String missingArgLabel(ArgSpec arg) {
        if (arg instanceof PositionalParamSpec p) return CommandModelExtractor.positionalLabel(p);
        if (arg instanceof OptionSpec o) return o.longestName();
        return arg.paramLabel();
    }

    private static String formatTipLine(boolean ansi, String suggestion) {
        if (ansi) {
            String accent = Theme.active().tip();
            String tip = Ansi.sgr(accent) + "tip:" + Ansi.RESET;
            String word = Ansi.sgr(accent) + suggestion + Ansi.RESET;
            return "  " + tip + " did you mean '" + word + "'?";
        }
        return "  tip: did you mean '" + suggestion + "'?";
    }

    private static String formatUsageLine(boolean ansi, CommandSpec spec) {
        CommandModel model = CommandModelExtractor.extract(spec);
        String name = model.qualifiedName();
        // Mirror renderSynopsis: parents show <COMMAND>, leaves list their
        // visible positionals before [OPTIONS].
        StringBuilder suffix = new StringBuilder();
        if (model.leaf()) {
            for (ParameterModel p : model.parameters()) {
                suffix.append(" ").append(p.label());
            }
        } else {
            suffix.append(" <COMMAND>");
        }
        suffix.append(" [OPTIONS]");
        if (ansi) {
            return HelpRenderer.paint("Usage:", Theme.active().sectionHeading(), true) + " "
                    + HelpRenderer.paint(name, Theme.active().commandName(), true)
                    + HelpRenderer.paint(suffix.toString(), Theme.active().paramLabel(), true);
        }
        return "Usage: " + name + suffix;
    }

    private static String formatHelpHint(boolean ansi) {
        if (ansi) {
            return "For more information, try '" + Ansi.sgr(Theme.active().helpHint()) + "--help" + Ansi.RESET + "'";
        }
        return "For more information, try '--help'";
    }
}
