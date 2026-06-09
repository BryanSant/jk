// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import java.util.List;

/**
 * Optional escape hatch a command's user object MAY implement to express help
 * details not captured in the {@link dev.jkbuild.model.command.CliCommand} defaults.
 * No command uses this yet; it exists as an extension point for future commands.
 */
public interface DescribesUsage {

    default String[] longDescription() {
        return null;
    }

    default List<CommandGroup> commandGroups() {
        return null;
    }
}
