// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.GroupCommand;
import java.util.List;

/** {@code jk library} parent verb — manage the short-name library catalog. */
public final class LibraryCommand extends GroupCommand {

    @Override
    public String name() {
        return "library";
    }

    @Override
    public String description() {
        return "Manage the short-name-to-coordinate library catalog";
    }

    @Override
    public List<String> aliases() {
        return List.of("lib");
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new LibraryUpdateCommand(), new LibraryListCommand(), new LibrarySearchCommand());
    }
}
