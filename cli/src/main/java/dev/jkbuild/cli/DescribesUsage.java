// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Help;

import java.util.List;

/**
 * Optional escape hatch a command's user object MAY implement to express help
 * details that picocli annotations can't capture. The {@link HelpRenderer}
 * consults this interface when a command's user object implements it; commands
 * that don't implement it get the standard annotation-derived rendering.
 *
 * <p>All hooks default to {@code null}, meaning "no override — use the standard
 * behavior". No command needs to implement this yet; it exists so future
 * commands can add a long description, custom command groups, or take over
 * rendering entirely without changing the renderer.
 */
public interface DescribesUsage {

    /**
     * A multi-line long description to render in place of (or in addition to)
     * the annotation's {@code description}. Return {@code null} to use the
     * annotation description unchanged.
     */
    default String[] longDescription() {
        return null;
    }

    /**
     * Custom command groupings for a parent command's subcommand list. Return
     * {@code null} to use the standard grouping.
     */
    default List<CommandGroup> commandGroups() {
        return null;
    }

    /**
     * Fully custom rendering hook. When non-{@code null}, the returned string is
     * emitted verbatim and the standard painter is bypassed entirely. Return
     * {@code null} to keep the standard painter.
     */
    default String renderHelp(Help help, CommandModel model) {
        return null;
    }
}
