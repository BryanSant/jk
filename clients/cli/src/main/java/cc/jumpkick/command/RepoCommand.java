// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.GroupCommand;
import java.util.List;

/**
 * {@code jk repo} parent command — manage artifact-repository credentials (the package/artifact plane,
 * distinct from {@code jk auth}'s git-forge plane). Stores credentials keyed by repository id under
 * {@code ~/.jk/repo-credentials/} for use when resolving from / publishing to private Nexus,
 * Artifactory, WebDAV, and other authenticated Maven repositories. See docs/artifact-repos.md.
 */
public final class RepoCommand extends GroupCommand {

    @Override
    public String name() {
        return "repo";
    }

    @Override
    public String description() {
        return "Manage artifact repository credentials";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new RepoLoginCommand(), new RepoLogoutCommand());
    }
}
