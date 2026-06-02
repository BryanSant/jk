// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import java.util.List;

/**
 * Normalized, render-ready view of a single command's help metadata. Built from
 * picocli's reflective {@code CommandSpec} by {@link CommandModelExtractor}; the
 * {@link HelpRenderer} paints from this model and never touches picocli's spec
 * directly. {@code *Model} naming avoids collisions with picocli's own
 * {@code OptionSpec}/{@code PositionalParamSpec} types.
 *
 * @param qualifiedName the fully-qualified command name (e.g. {@code "jk tool install"})
 * @param description    the command's description lines (may be empty)
 * @param leaf           true when the command has no subcommands
 * @param parameters     visible positional parameters, in declaration order
 * @param options        command-specific (non-global) visible options
 * @param globalOptions  options contributed by the {@code GlobalOptions} mixin
 * @param subcommands    visible subcommands, in registration order
 */
public record CommandModel(
        String qualifiedName,
        String[] description,
        boolean leaf,
        List<ParameterModel> parameters,
        List<OptionModel> options,
        List<OptionModel> globalOptions,
        List<SubcommandModel> subcommands) {}
