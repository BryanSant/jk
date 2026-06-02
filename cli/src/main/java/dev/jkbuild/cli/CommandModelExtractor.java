// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single bridge from picocli's reflective {@code CommandSpec} to jk's
 * normalized {@link CommandModel}. Faithfully reproduces the help logic that
 * used to live in {@code Jk}: positional label rules, global-vs-command option
 * partitioning via the long-name set, hidden-option filtering, and the hidden
 * {@code -C} alias-name suppression.
 */
public final class CommandModelExtractor {

    private CommandModelExtractor() {}

    /**
     * Option name aliases that still parse but are kept out of {@code --help}.
     * Lets us retire short flags from the help screen without breaking users
     * (or tests) that already depend on them.
     */
    private static final Set<String> HIDDEN_OPTION_NAMES = Set.of("-C");

    /** Build the render model for {@code spec}. */
    public static CommandModel extract(CommandSpec spec) {
        boolean leaf = spec.subcommands().isEmpty();

        List<ParameterModel> parameters = new ArrayList<>();
        for (PositionalParamSpec p : spec.positionalParameters()) {
            if (p.hidden()) continue;
            parameters.add(new ParameterModel(positionalLabel(p), p.description()));
        }

        List<OptionModel> options = new ArrayList<>();
        List<OptionModel> globalOptions = new ArrayList<>();
        for (OptionSpec opt : spec.options()) {
            if (opt.hidden()) continue;
            if (isGlobal(opt)) {
                globalOptions.add(toOptionModel(opt));
            } else {
                options.add(toOptionModel(opt));
            }
        }

        List<SubcommandModel> subcommands = new ArrayList<>();
        for (CommandSpec sub : distinctSubcommands(spec)) {
            subcommands.add(new SubcommandModel(
                    sub.name(),
                    sub.usageMessage().description(),
                    sub.usageMessage().hidden()));
        }

        return new CommandModel(
                spec.qualifiedName(),
                spec.usageMessage().description(),
                leaf,
                parameters,
                options,
                globalOptions,
                subcommands);
    }

    /**
     * Subcommand specs keyed by canonical name, in registration order. Picocli's
     * {@code subcommands()} map aliases entries as "name, alias"; index by each
     * subcommand's canonical {@code name()} so duplicates (alias keys) collapse.
     */
    private static List<CommandSpec> distinctSubcommands(CommandSpec spec) {
        List<CommandSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var sub : spec.subcommands().values()) {
            CommandSpec ss = sub.getCommandSpec();
            if (seen.add(ss.name())) out.add(ss);
        }
        return out;
    }

    private static OptionModel toOptionModel(OptionSpec opt) {
        String namePart = Arrays.stream(opt.names())
                .filter(n -> !HIDDEN_OPTION_NAMES.contains(n))
                .collect(Collectors.joining(", "));
        String labelPart = (opt.arity().max() > 0 && opt.paramLabel() != null && !opt.paramLabel().isEmpty())
                ? opt.paramLabel()
                : "";
        return new OptionModel(namePart, labelPart, opt.description());
    }

    /**
     * Display form for a positional parameter, per CLI convention:
     * {@code <name>} when required, {@code [name]} when optional. Any
     * brackets/angles already in the stored paramLabel are stripped first,
     * so we never emit {@code [<name>]} or {@code [[name]]}.
     */
    public static String positionalLabel(PositionalParamSpec p) {
        String bare = p.paramLabel().replaceAll("[<>\\[\\]]", "");
        return p.arity().min() == 0 ? "[" + bare + "]" : "<" + bare + ">";
    }

    /** True when this option came from the {@code GlobalOptions} mixin. */
    public static boolean isGlobal(OptionSpec opt) {
        for (String name : opt.names()) {
            if (UsageGroups.GLOBAL_OPTION_LONG_NAMES.contains(name)) return true;
        }
        return false;
    }
}
