// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.GroupCommand;
import java.util.List;

/** {@code jk library} parent command — manage the short-name library catalog. */
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
