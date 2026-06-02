// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.config.JkConfig;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.UsageMessageSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wires {@link HelpRenderer}'s section renderers into picocli and applies the
 * resolved {@code --color} choice. Owns the section ordering (parent vs leaf),
 * the global-options injection, and the color-scheme application that used to
 * live in {@code Jk}.
 */
public final class HelpLayout {

    private HelpLayout() {}

    /** Subcommands whose unmatched options forward to a wrapped tool — `--help` must pass through. */
    static final Set<String> PASSTHROUGH_COMMANDS = Set.of("mvn", "gradle");

    /** Mixin key used to register {@code GlobalOptions} on every {@link CommandSpec}. */
    private static final String GLOBAL_OPTIONS_MIXIN_KEY = "global";

    /** Custom section key for the global-options heading + list. */
    private static final String SECTION_KEY_GLOBAL_OPTIONS_HEADING = "jkGlobalOptionsHeading";
    private static final String SECTION_KEY_GLOBAL_OPTIONS = "jkGlobalOptions";

    /** Picocli's default section order — used to restore leaf commands after the parent layout leaks via spec inheritance. */
    private static final List<String> DEFAULT_SECTION_KEYS = List.of(
            UsageMessageSpec.SECTION_KEY_HEADER_HEADING,
            UsageMessageSpec.SECTION_KEY_HEADER,
            UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
            UsageMessageSpec.SECTION_KEY_SYNOPSIS,
            UsageMessageSpec.SECTION_KEY_DESCRIPTION_HEADING,
            UsageMessageSpec.SECTION_KEY_DESCRIPTION,
            UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_AT_FILE_PARAMETER,
            UsageMessageSpec.SECTION_KEY_PARAMETER_LIST,
            UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_OPTION_LIST,
            UsageMessageSpec.SECTION_KEY_END_OF_OPTIONS,
            UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
            UsageMessageSpec.SECTION_KEY_EXIT_CODE_LIST_HEADING,
            UsageMessageSpec.SECTION_KEY_EXIT_CODE_LIST,
            UsageMessageSpec.SECTION_KEY_FOOTER_HEADING,
            UsageMessageSpec.SECTION_KEY_FOOTER);

    /**
     * Install jk's help system onto {@code cmd}: the global-options mixin on
     * every command, the parent layout on the root and nested parents, the leaf
     * layout on styled leaves, and the error renderers. Mirrors the wiring that
     * used to live in {@code Jk.newCommandLine()}.
     */
    public static void install(CommandLine cmd) {
        installGlobalOptionsEverywhere(cmd);
        applyParentLayout(cmd);
        applyParentLayoutRecursively(cmd);
        cmd.setParameterExceptionHandler(ErrorRenderer::handleParameterException);
        cmd.setExecutionExceptionHandler(ErrorRenderer::handleExecutionException);
    }

    private static void installGlobalOptionsEverywhere(CommandLine cmd) {
        if (!PASSTHROUGH_COMMANDS.contains(cmd.getCommandName())) {
            // Skip commands that already declared `@Mixin GlobalOptions` themselves
            // (so they can READ the values); picocli has already registered it for them.
            boolean alreadyHas = cmd.getCommandSpec().mixins().values().stream()
                    .anyMatch(spec -> spec.userObject() instanceof GlobalOptions);
            if (!alreadyHas) {
                // Fresh instance per command — picocli writes parsed values into the
                // mixin's fields, so sharing one instance would race during parsing.
                cmd.getCommandSpec().addMixin(GLOBAL_OPTIONS_MIXIN_KEY,
                        CommandSpec.forAnnotatedObject(new GlobalOptions()));
            }
        }
        for (CommandLine sub : cmd.getSubcommands().values()) {
            installGlobalOptionsEverywhere(sub);
        }
    }

    private static void applyParentLayoutRecursively(CommandLine parent) {
        for (CommandLine sub : parent.getSubcommands().values()) {
            if (!sub.getSubcommands().isEmpty()) {
                applyParentLayout(sub);
            } else if (PASSTHROUGH_COMMANDS.contains(sub.getCommandName())) {
                // Passthroughs (mvn, gradle) forward --help to the wrapped tool; don't
                // intercept their help layout.
                sub.setHelpSectionKeys(DEFAULT_SECTION_KEYS);
            } else {
                // Every non-passthrough leaf gets the styled "description / Usage /
                // Parameters / Options" layout that matches the parent help screens.
                applyLeafLayout(sub);
            }
            applyParentLayoutRecursively(sub);
        }
    }

    /**
     * Layout for a styled leaf command: {description, Usage, Parameters,
     * Options, Global options}. Mirrors {@link #applyParentLayout} but
     * swaps the command-list section for parameter and option renderers
     * that match the styling of parent help screens.
     */
    private static void applyLeafLayout(CommandLine cmd) {
        cmd.setHelpSectionKeys(List.of(
                UsageMessageSpec.SECTION_KEY_DESCRIPTION,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS,
                UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING,
                UsageMessageSpec.SECTION_KEY_PARAMETER_LIST,
                UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING,
                UsageMessageSpec.SECTION_KEY_OPTION_LIST,
                SECTION_KEY_GLOBAL_OPTIONS_HEADING,
                SECTION_KEY_GLOBAL_OPTIONS));
        Map<String, CommandLine.IHelpSectionRenderer> sections = cmd.getHelpSectionMap();
        sections.put(UsageMessageSpec.SECTION_KEY_DESCRIPTION, HelpRenderer::renderDescription);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING, HelpRenderer::renderSynopsisHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS, HelpRenderer::renderSynopsis);
        sections.put(UsageMessageSpec.SECTION_KEY_PARAMETER_LIST_HEADING, HelpRenderer::renderArgumentsHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_PARAMETER_LIST, HelpRenderer::renderStyledParameterList);
        sections.put(UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING, HelpRenderer::renderOptionsHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_OPTION_LIST, HelpRenderer::renderStyledOptionList);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS_HEADING, HelpRenderer::renderGlobalOptionsHeading);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS, HelpRenderer::renderStyledGlobalOptionList);
    }

    /**
     * Layout for any command that has subcommands. Reorders sections to
     * {description, Usage line, command list}, drops the option list, and wires
     * in the styled renderers. The top-level uses the verb-grouped subcommand
     * list (Project / Toolchain / …); nested parents use a single "Commands:"
     * heading over a flat list.
     */
    private static void applyParentLayout(CommandLine cmd) {
        boolean topLevel = cmd.getCommandSpec().parent() == null;
        List<String> keys = new ArrayList<>(List.of(
                UsageMessageSpec.SECTION_KEY_DESCRIPTION,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING,
                UsageMessageSpec.SECTION_KEY_SYNOPSIS));
        if (!topLevel) {
            keys.add(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING);
        }
        keys.add(UsageMessageSpec.SECTION_KEY_COMMAND_LIST);
        keys.add(SECTION_KEY_GLOBAL_OPTIONS_HEADING);
        keys.add(SECTION_KEY_GLOBAL_OPTIONS);
        cmd.setHelpSectionKeys(keys);

        Map<String, CommandLine.IHelpSectionRenderer> sections = cmd.getHelpSectionMap();
        sections.put(UsageMessageSpec.SECTION_KEY_DESCRIPTION, HelpRenderer::renderDescription);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING, HelpRenderer::renderSynopsisHeading);
        sections.put(UsageMessageSpec.SECTION_KEY_SYNOPSIS, HelpRenderer::renderSynopsis);
        if (topLevel) {
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, HelpRenderer::renderGroupedSubcommands);
        } else {
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING, HelpRenderer::renderCommandsHeading);
            sections.put(UsageMessageSpec.SECTION_KEY_COMMAND_LIST, HelpRenderer::renderFlatSubcommands);
        }
        sections.put(SECTION_KEY_GLOBAL_OPTIONS_HEADING, HelpRenderer::renderGlobalOptionsHeading);
        sections.put(SECTION_KEY_GLOBAL_OPTIONS, HelpRenderer::renderStyledGlobalOptionList);
    }

    /**
     * Force picocli's help renderer to honor the resolved {@code --color}
     * choice in {@link ActiveConfig}. Without this, picocli would do its
     * own auto-detection independently of the user's explicit flag.
     */
    public static void applyColorScheme(CommandLine cmd) {
        var choice = ActiveConfig.get().colorOr(JkConfig.ColorChoice.AUTO);
        Help.Ansi ansi = switch (choice) {
            case ALWAYS -> Help.Ansi.ON;
            case NEVER -> Help.Ansi.OFF;
            // AUTO matches Theme.colorEnabled(): on unless NO_COLOR is set.
            case AUTO -> {
                var nc = System.getenv("NO_COLOR");
                yield (nc == null || nc.isEmpty())
                        ? Help.Ansi.ON
                        : Help.Ansi.OFF;
            }
        };
        cmd.setColorScheme(Help.defaultColorScheme(ansi));
    }
}
