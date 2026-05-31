// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jk repo} parent verb — manage artifact-repository credentials (the
 * package/artifact plane, distinct from {@code jk auth}'s git-forge plane).
 * Stores credentials keyed by repository id under {@code ~/.jk/repo-credentials/}
 * for use when resolving from / publishing to private Nexus, Artifactory,
 * WebDAV, and other authenticated Maven repositories. See
 * docs/artifact-repos.md.
 */
@Command(name = "repo",
        description = "Manage artifact repository credentials",
        subcommands = {
                RepoLoginCommand.class,
                RepoLogoutCommand.class,
        })
public final class RepoCommand implements Callable<Integer> {

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 64;
    }
}
