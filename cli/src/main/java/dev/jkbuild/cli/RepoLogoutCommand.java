// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.repo.RepoCredentialStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk repo logout <id>} — forget stored credentials for an artifact
 * repository. Leaves env vars and {@code ~/.m2/settings.xml} untouched.
 */
@Command(name = "logout", description = "Remove stored credentials for an artifact repository")
public final class RepoLogoutCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<id>",
            description = "Repository id (matches [repositories.<id>] in jk.toml).")
    String id;

    @Option(names = "--credentials-dir", hidden = true,
            description = "Override the credentials directory. Default: ~/.jk/repo-credentials.")
    Path credentialsDir;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        RepoCredentialStore store = credentialsDir != null
                ? new RepoCredentialStore(credentialsDir)
                : new RepoCredentialStore();
        store.clear(id);
        if (!global.quiet) {
            System.out.println("Removed stored credentials for repository '" + id + "'.");
        }
        return 0;
    }
}
