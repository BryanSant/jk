// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Command;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the render-ready {@link CommandModel} from jk's own {@link Command} model — the
 * picocli-free counterpart to {@link CommandModelExtractor} (which builds the same {@code
 * CommandModel} from a picocli {@code CommandSpec}). Both feed {@link HelpRenderer}, so a command
 * renders identically whether it's been ported off picocli yet or not.
 */
public final class CommandModels {

    private CommandModels() {}

    /**
     * @param command the command to model
     * @param qualifiedName fully-qualified name for the synopsis (e.g. {@code "jk tool install"})
     * @param globalOptions the shared global options to show in the "Global options" section
     */
    public static CommandModel from(CliCommand command, String qualifiedName, List<OptionModel> globalOptions) {
        List<ParameterModel> params = new ArrayList<>();
        for (Param p : command.parameters()) {
            if (p.hidden()) continue;
            params.add(new ParameterModel(label(p), descLines(p.description())));
        }
        List<OptionModel> options = new ArrayList<>();
        for (Opt o : command.options()) {
            if (o.hidden()) continue;
            options.add(option(o));
        }
        List<SubcommandModel> subs = new ArrayList<>();
        for (CliCommand sub : command.subcommands()) {
            subs.add(new SubcommandModel(sub.name(), descLines(sub.description()), false));
        }
        return new CommandModel(
                qualifiedName,
                descLines(command.description()),
                command.isLeaf(),
                params,
                options,
                globalOptions,
                subs);
    }

    static OptionModel option(Opt o) {
        String namePart = String.join(", ", o.names());
        String labelPart = o.takesValue() && o.paramLabel() != null ? o.paramLabel() : "";
        return new OptionModel(namePart, labelPart, descLines(o.description()));
    }

    private static String label(Param p) {
        return p.arity().required() ? "<" + p.name() + ">" : "[" + p.name() + "]";
    }

    private static String[] descLines(String description) {
        if (description == null || description.isEmpty()) return new String[0];
        return description.split("\n", -1);
    }
}
