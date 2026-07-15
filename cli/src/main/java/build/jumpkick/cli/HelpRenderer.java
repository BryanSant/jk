// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli;

import build.jumpkick.cli.theme.Theme;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.jline.utils.AttributedStyle;

/**
 * The help-screen painter for jk's model-driven rendering path. Paints from a {@link CommandModel}
 * record (built by {@link CommandModels}). Colors come from the active {@link Theme}; escapes from
 * {@link Ansi}.
 */
public final class HelpRenderer {

    private HelpRenderer() {}

    // --- theme-sourced colorizing ---------------------------------------

    /**
     * Wrap {@code text} in the SGR for {@code style} when {@code ansi} is true, delegating to {@link
     * Theme#colorize(String, AttributedStyle)} so the canonical attribute-leading byte order is owned
     * by the theme layer. When {@code ansi} is false the text is returned unstyled.
     */
    static String paint(String text, AttributedStyle style, boolean ansi) {
        return ansi ? Theme.colorize(text, style) : text;
    }

    private static String heading(String text, boolean ansi) {
        return paint(text, Theme.active().sectionHeading(), ansi);
    }

    private static String commandName(String text, boolean ansi) {
        return paint(text, Theme.active().commandName(), ansi);
    }

    private static String paramLabel(String text, boolean ansi) {
        return paint(text, Theme.active().paramLabel(), ansi);
    }

    private static String highlight(String text, boolean ansi) {
        return paint(text, Theme.active().highlight(), ansi);
    }

    // --- model-driven full-screen render (picocli-free path) -------------

    /**
     * Assemble a complete help screen from a {@link CommandModel} for commands parsed by jk's own
     * {@link build.jumpkick.cli.args.ArgParser} (no picocli {@code Help}). Mirrors the section order and
     * styling of the {@code IHelpSectionRenderer} methods above and reuses the same private painters,
     * so a ported command's help is byte-identical to an unported one.
     */
    public static String renderHelp(CommandModel model, boolean ansi) {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();

        for (String line : model.description()) {
            out.append(line).append(nl);
        }
        if (model.description().length > 0) out.append(nl);

        StringBuilder suffix = new StringBuilder();
        if (!model.leaf()) {
            suffix.append(" <COMMAND>");
        } else {
            for (ParameterModel p : model.parameters()) suffix.append(" ").append(p.label());
        }
        suffix.append(" [OPTIONS]");
        out.append(heading("Usage:", ansi)).append(" ");
        if (ansi) {
            out.append(commandName(model.qualifiedName(), true)).append(paramLabel(suffix.toString(), true));
        } else {
            out.append(model.qualifiedName()).append(suffix);
        }
        out.append(nl);

        if (!model.parameters().isEmpty()) {
            out.append(nl).append(heading("Parameters:", ansi)).append(nl);
            out.append(parameterRows(model.parameters(), ansi));
        }
        if (!model.options().isEmpty()) {
            out.append(nl).append(heading("Options:", ansi)).append(nl);
            out.append(renderOptionRows(model.options(), ansi));
        }
        if (!model.subcommands().isEmpty()) {
            out.append(nl).append(heading("Commands:", ansi)).append(nl);
            int width = model.subcommands().stream()
                            .filter(s -> !s.hidden())
                            .mapToInt(s -> s.name().length())
                            .max()
                            .orElse(0)
                    + 2;
            for (SubcommandModel sub : model.subcommands()) {
                if (sub.hidden()) continue;
                appendCommandRow(out, sub.name(), sub, width, ansi);
            }
        }
        if (!model.globalOptions().isEmpty()) {
            out.append(nl).append(heading("Global options:", ansi)).append(nl);
            out.append(renderOptionRows(model.globalOptions(), ansi));
        }
        return out.toString();
    }

    /** Shared positional-parameter row painter. */
    private static String parameterRows(List<ParameterModel> params, boolean ansi) {
        if (params.isEmpty()) return "";
        int width = params.stream().mapToInt(p -> p.label().length()).max().orElse(0) + 2;
        String indent = " ".repeat(2 + width);
        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        for (ParameterModel p : params) {
            String label = p.label();
            String[] desc = p.description();
            sb.append("  ")
                    .append(paramLabel(label, ansi))
                    .append(" ".repeat(width - label.length()))
                    .append(desc.length > 0 ? desc[0] : "")
                    .append(nl);
            for (int i = 1; i < desc.length; i++)
                sb.append(indent).append(desc[i]).append(nl);
        }
        return sb.toString();
    }

    private static void appendCommandRow(StringBuilder out, String name, SubcommandModel sub, int width, boolean ansi) {
        String[] desc = sub.description();
        String firstLine = desc.length > 0 ? desc[0] : "";
        String padding = " ".repeat(width - name.length());
        out.append("  ")
                .append(commandName(name, ansi))
                .append(padding)
                .append(firstLine)
                .append(System.lineSeparator());
    }

    /**
     * Format a list of options as ` -x, --xx <param> description` rows. Name block is bold-cyan;
     * param label (when present) is plain cyan; description is unstyled. Boolean flags (arity 0) skip
     * the label.
     */
    static String renderOptionRows(List<OptionModel> options, boolean ansi) {
        if (options.isEmpty()) return "";
        int width = 0;
        for (OptionModel opt : options) {
            width = Math.max(width, rowWidth(opt));
        }
        width += 3; // gutter between name/label block and description

        StringBuilder sb = new StringBuilder();
        String nl = System.lineSeparator();
        for (OptionModel opt : options) {
            String desc = opt.description().length > 0 ? opt.description()[0] : "";
            sb.append("  ").append(commandName(opt.namePart(), ansi));
            if (!opt.labelPart().isEmpty()) {
                sb.append(" ").append(paramLabel(opt.labelPart(), ansi));
            }
            sb.append(" ".repeat(width - rowWidth(opt))).append(desc).append(nl);
        }
        return sb.toString();
    }

    private static int rowWidth(OptionModel opt) {
        return opt.namePart().length()
                + (opt.labelPart().isEmpty() ? 0 : opt.labelPart().length() + 1);
    }

    // --- short (bare `jk`) help screen -----------------------------------

    /**
     * Render the abbreviated help shown when the user runs bare {@code jk}. Lists a curated subset of
     * commands (see {@link UsageGroups#SHORT_COMMAND_GROUPS}) plus a "More commands:" footer pointing at
     * {@code jk --help}. Reuses the heading / command-name styling from the full screen.
     */
    /**
     * Model-driven short-help screen: looks up commands from the {@link CommandDispatch} registry
     * instead of picocli's CommandSpec map. Called from {@link Jk#run()} now that all commands are
     * ported.
     */
    public static void printShortHelp(
            List<build.jumpkick.model.command.CliCommand> commands,
            String rootDescription,
            String qualifiedName,
            PrintStream out,
            boolean ansi) {
        out.println(rootDescription);
        out.println();
        if (ansi) {
            out.println(heading("Usage:", true)
                    + " "
                    + commandName(qualifiedName, true)
                    + paramLabel(" <COMMAND> [OPTIONS]", true));
        } else {
            out.println("Usage: " + qualifiedName + " <COMMAND> [OPTIONS]");
        }
        out.println();

        // Index by name for group lookup
        Map<String, build.jumpkick.model.command.CliCommand> byName = new java.util.LinkedHashMap<>();
        for (build.jumpkick.model.command.CliCommand c : commands) {
            if (!c.hidden()) byName.put(c.name(), c);
        }
        int nameWidth = 0;
        for (CommandGroup group : UsageGroups.SHORT_COMMAND_GROUPS) {
            for (String n : group.names()) {
                if (byName.containsKey(n)) nameWidth = Math.max(nameWidth, n.length());
            }
        }
        int descCol = 2 + nameWidth + 4;
        boolean firstGroup = true;
        for (CommandGroup group : UsageGroups.SHORT_COMMAND_GROUPS) {
            if (!firstGroup) out.println();
            firstGroup = false;
            out.println(heading(group.heading(), ansi));
            for (String n : group.names()) {
                build.jumpkick.model.command.CliCommand sub = byName.get(n);
                if (sub == null) continue;
                String padding = " ".repeat(descCol - 2 - n.length());
                out.println("  " + commandName(n, ansi) + padding + sub.description());
            }
        }
        out.println();
        out.println(heading("More commands:", ansi));
        String ellipsis = "...";
        String ellipsisPad = " ".repeat(descCol - 2 - ellipsis.length());
        String helpCmd = "jk --help";
        if (ansi) {
            out.println("  "
                    + paramLabel(ellipsis, true)
                    + ellipsisPad
                    + "See all commands and options by running "
                    + commandName(helpCmd, true));
        } else {
            out.println("  " + ellipsis + ellipsisPad + "See all commands and options by running " + helpCmd);
        }
    }
}
