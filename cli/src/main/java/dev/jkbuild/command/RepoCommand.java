// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import java.util.List;

/**
 * {@code jk repo} parent verb — manage artifact-repository credentials (the
 * package/artifact plane, distinct from {@code jk auth}'s git-forge plane).
 * Stores credentials keyed by repository id under {@code ~/.jk/repo-credentials/}
 * for use when resolving from / publishing to private Nexus, Artifactory,
 * WebDAV, and other authenticated Maven repositories. See
 * docs/artifact-repos.md.
 */
public final class RepoCommand implements CliCommand {

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

    /** Unreachable: the dispatcher prints the command list for a bare parent. */
    @Override
    public int run(Invocation in) {
        return 64;
    }
}
