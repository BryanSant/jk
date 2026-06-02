// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.theme.Theme;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The help-screen painter. Each {@code render*} method is an
 * {@link CommandLine.IHelpSectionRenderer} that builds a {@link CommandModel}
 * (via {@link CommandModelExtractor}) and paints it. Every color comes from the
 * active {@link Theme}; escapes come from {@link Ansi}.
 *
 * <p>Colors come from the active {@link Theme} and are emitted via
 * {@link Theme#colorize(String, AttributedStyle)}, which produces jk's canonical
 * attribute-leading SGR ({@code 1;38;2;r;g;b}). When ansi is disabled the text
 * is returned unstyled.
 */
public final class HelpRenderer {

    private HelpRenderer() {}

    // --- theme-sourced colorizing ---------------------------------------

    /**
     * Wrap {@code text} in the SGR for {@code style} when {@code ansi} is true,
     * delegating to {@link Theme#colorize(String, AttributedStyle)} so the
     * canonical attribute-leading byte order is owned by the theme layer. When
     * {@code ansi} is false the text is returned unstyled.
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

    // --- DescribesUsage escape hatch -------------------------------------

    private static DescribesUsage describesUsage(Help help) {
        Object user = help.commandSpec().userObject();
        return user instanceof DescribesUsage du ? du : null;
    }

    /**
     * The full custom-render override for {@code help}, or {@code null} when the
     * command doesn't take over rendering. When non-null the standard painter is
     * bypassed entirely: the description section emits this string verbatim and
     * every other section emits nothing (see {@link #customTakeover(Help)}).
     */
    private static String customRender(Help help) {
        DescribesUsage du = describesUsage(help);
        if (du == null) return null;
        return du.renderHelp(help, CommandModelExtractor.extract(help.commandSpec()));
    }

    /** True when this command supplies a full custom render that suppresses standard sections. */
    private static boolean customTakeover(Help help) {
        return customRender(help) != null;
    }

    // --- section renderers (parent + leaf) -------------------------------

    public static String renderDescription(Help help) {
        // Full custom-render escape hatch: a command's DescribesUsage.renderHelp()
        // takes over the whole screen, emitted here (the first section) verbatim.
        String custom = customRender(help);
        if (custom != null) return custom;
        DescribesUsage du = describesUsage(help);
        String[] desc = (du != null && du.longDescription() != null)
                ? du.longDescription()
                : help.commandSpec().usageMessage().description();
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

    public static String renderSynopsisHeading(Help help) {
        if (customTakeover(help)) return "";
        return heading("Usage:", help.colorScheme().ansi().enabled()) + " ";
    }

    /**
     * "{@code <name> [...args] [OPTIONS]}" — name is bold cyan, the rest is plain
     * cyan. Replaces picocli's auto-generated synopsis with a fixed, abstract
     * form. Parents render as {@code <name> <COMMAND> [OPTIONS]}; leaves include
     * any visible positional parameters in declaration order before
     * {@code [OPTIONS]}.
     */
    public static String renderSynopsis(Help help) {
        if (customTakeover(help)) return "";
        boolean ansi = help.colorScheme().ansi().enabled();
        CommandModel model = CommandModelExtractor.extract(help.commandSpec());
        String name = model.qualifiedName();
        StringBuilder suffix = new StringBuilder();
        if (!model.leaf()) {
            suffix.append(" <COMMAND>");
        } else {
            for (ParameterModel p : model.parameters()) {
                suffix.append(" ").append(p.label());
            }
        }
        suffix.append(" [OPTIONS]");
        String nl = System.lineSeparator();
        if (ansi) {
            // Command name uses the theme's bold cyan; suffix uses plain cyan.
            // Concatenate so the name's RESET is immediately followed by the
            // plain-cyan suffix, matching jk's historical bytes.
            return commandName(name, true) + paramLabel(suffix.toString(), true) + nl;
        }
        return name + suffix + nl;
    }

    public static String renderCommandsHeading(Help help) {
        if (customTakeover(help)) return "";
        String nl = System.lineSeparator();
        return nl + heading("Commands:", help.colorScheme().ansi().enabled()) + nl;
    }

    public static String renderOptionsHeading(Help help) {
        if (customTakeover(help)) return "";
        boolean anyVisible = help.commandSpec().options().stream()
                .anyMatch(o -> !o.hidden() && !CommandModelExtractor.isGlobal(o));
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + heading("Options:", help.colorScheme().ansi().enabled()) + nl;
    }

    public static String renderGlobalOptionsHeading(Help help) {
        if (customTakeover(help)) return "";
        boolean anyVisible = help.commandSpec().options().stream()
                .anyMatch(o -> !o.hidden() && CommandModelExtractor.isGlobal(o));
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + heading("Global options:", help.colorScheme().ansi().enabled()) + nl;
    }

    public static String renderArgumentsHeading(Help help) {
        if (customTakeover(help)) return "";
        boolean anyVisible = help.commandSpec().positionalParameters().stream().anyMatch(p -> !p.hidden());
        if (!anyVisible) return "";
        String nl = System.lineSeparator();
        return nl + heading("Parameters:", help.colorScheme().ansi().enabled()) + nl;
    }

    /**
     * Render visible positional parameters as `  <param>  description` rows.
     * Param labels are plain cyan (matching the synopsis's `[OPTIONS]`).
     * A multi-line description renders each extra line on its own row, indented
     * to hang under the first line's text.
     */
    public static String renderStyledParameterList(Help help) {
        if (customTakeover(help)) return "";
        boolean ansi = help.colorScheme().ansi().enabled();
        List<ParameterModel> params = CommandModelExtractor.extract(help.commandSpec()).parameters();
        if (params.isEmpty()) return "";
        int width = params.stream().mapToInt(p -> p.label().length()).max().orElse(0) + 2;
        String indent = " ".repeat(2 + width); // continuation lines align under the first description char
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
            for (int i = 1; i < desc.length; i++) {
                sb.append(indent).append(desc[i]).append(nl);
            }
        }
        return sb.toString();
    }

    /**
     * Render command-specific options (those <em>not</em> from the
     * {@code GlobalOptions} mixin) as styled rows.
     */
    public static String renderStyledOptionList(Help help) {
        if (customTakeover(help)) return "";
        List<OptionModel> options = CommandModelExtractor.extract(help.commandSpec()).options();
        return renderOptionRows(options, help.colorScheme().ansi().enabled());
    }

    /**
     * Render the {@code GlobalOptions} mixin's options (reflected into the model's
     * {@link CommandModel#globalOptions()}) as styled rows. Shared by every
     * command/subcommand so the "Global options" section reads identically
     * everywhere; styling matches {@link #renderOptionRows} (name block bold-cyan,
     * value label plain cyan, description unstyled).
     *
     * <p>Rendered through the single {@link #renderOptionRows} path, so the two
     * option lists (command-specific and global) share one row renderer and one
     * width/gutter calculation.
     */
    public static String renderStyledGlobalOptionList(Help help) {
        if (customTakeover(help)) return "";
        List<OptionModel> options = CommandModelExtractor.extract(help.commandSpec()).globalOptions();
        return renderOptionRows(options, help.colorScheme().ansi().enabled());
    }

    /**
     * Format a list of options as `  -x, --xx <param>  description` rows.
     * Name block is bold-cyan; param label (when present) is plain cyan;
     * description is unstyled. Boolean flags (arity 0) skip the label.
     */
    private static String renderOptionRows(List<OptionModel> options, boolean ansi) {
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
        return opt.namePart().length() + (opt.labelPart().isEmpty() ? 0 : opt.labelPart().length() + 1);
    }

    // --- subcommand lists ------------------------------------------------

    public static String renderGroupedSubcommands(Help help) {
        if (customTakeover(help)) return "";
        // Drive off the model's subcommand list (canonical names, aliases already
        // collapsed) rather than picocli's Help.subcommands() map directly.
        Map<String, SubcommandModel> byName = new LinkedHashMap<>();
        int width = 0;
        for (SubcommandModel sub : CommandModelExtractor.extract(help.commandSpec()).subcommands()) {
            if (sub.hidden()) continue;
            String name = sub.name();
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
        // A command may override the verb groupings via DescribesUsage.commandGroups();
        // otherwise use the canonical top-level groups.
        DescribesUsage du = describesUsage(help);
        List<CommandGroup> groups = (du != null && du.commandGroups() != null)
                ? du.commandGroups()
                : UsageGroups.COMMAND_GROUPS;
        boolean firstGroup = true;
        for (CommandGroup group : groups) {
            List<String> visible = group.names().stream()
                    .filter(byName::containsKey)
                    .toList();
            if (visible.isEmpty()) continue;
            if (!firstGroup) out.append(nl);
            firstGroup = false;
            out.append(heading(group.heading(), ansi)).append(nl);
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
            if (!firstGroup) out.append(nl);
            out.append(heading("Shell integration commands:", ansi)).append(nl);
            for (String name : leftover) {
                appendCommandRow(out, name, byName.get(name), width, ansi);
            }
        }
        return out.toString();
    }

    /** Flat command list (canonical names only, registration order) for nested subcommand help. */
    public static String renderFlatSubcommands(Help help) {
        if (customTakeover(help)) return "";
        int width = 0;
        List<SubcommandModel> subs = new ArrayList<>();
        for (SubcommandModel sub : CommandModelExtractor.extract(help.commandSpec()).subcommands()) {
            if (sub.hidden()) continue;
            subs.add(sub);
            width = Math.max(width, sub.name().length());
        }
        width += 2;
        boolean ansi = help.colorScheme().ansi().enabled();
        StringBuilder out = new StringBuilder();
        for (SubcommandModel sub : subs) {
            appendCommandRow(out, sub.name(), sub, width, ansi);
        }
        return out.toString();
    }

    private static void appendCommandRow(StringBuilder out, String name, SubcommandModel sub, int width, boolean ansi) {
        String[] desc = sub.description();
        String firstLine = desc.length > 0 ? desc[0] : "";
        // Pad after the colorized name based on the plain length — ANSI codes
        // are zero-width on screen but counted by String.format width specifiers.
        String padding = " ".repeat(width - name.length());
        out.append("  ").append(commandName(name, ansi)).append(padding).append(firstLine).append(System.lineSeparator());
    }

    // --- short (bare `jk`) help screen -----------------------------------

    /**
     * Render the abbreviated help shown when the user runs bare {@code jk}.
     * Lists a curated subset of verbs (see {@link UsageGroups#SHORT_COMMAND_GROUPS})
     * plus a "More commands:" footer pointing at {@code jk --help}. Reuses the
     * heading / command-name styling from the full screen.
     */
    public static void printShortHelp(CommandSpec rootSpec, PrintStream out, boolean ansi) {
        for (String line : rootSpec.usageMessage().description()) {
            out.println(line);
        }
        out.println();
        String qualifiedName = rootSpec.qualifiedName();
        if (ansi) {
            out.println(heading("Usage:", true) + " "
                    + commandName(qualifiedName, true) + paramLabel(" <COMMAND> [OPTIONS]", true));
        } else {
            out.println("Usage: " + qualifiedName + " <COMMAND> [OPTIONS]");
        }
        out.println();

        Map<String, CommandLine> subs = rootSpec.subcommands();
        int nameWidth = 0;
        for (CommandGroup group : UsageGroups.SHORT_COMMAND_GROUPS) {
            for (String n : group.names()) {
                if (subs.containsKey(n)) nameWidth = Math.max(nameWidth, n.length());
            }
        }
        int descCol = 2 + nameWidth + 4;

        boolean firstGroup = true;
        for (CommandGroup group : UsageGroups.SHORT_COMMAND_GROUPS) {
            if (!firstGroup) out.println();
            firstGroup = false;
            out.println(heading(group.heading(), ansi));
            for (String n : group.names()) {
                CommandLine sub = subs.get(n);
                if (sub == null) continue;
                if (sub.getCommandSpec().usageMessage().hidden()) continue;
                String[] desc = sub.getCommandSpec().usageMessage().description();
                String firstLine = desc.length > 0 ? desc[0] : "";
                String padding = " ".repeat(descCol - 2 - n.length());
                out.println("  " + commandName(n, ansi) + padding + firstLine);
            }
        }

        out.println();
        out.println(heading("More commands:", ansi));
        String ellipsis = "...";
        String ellipsisPad = " ".repeat(descCol - 2 - ellipsis.length());
        String prefix = "See all commands and options by running ";
        String helpCmd = "jk --help";
        String coloredHelp = highlight(helpCmd, ansi);
        out.println("  " + commandName(ellipsis, ansi) + ellipsisPad + prefix + coloredHelp);
    }
}
