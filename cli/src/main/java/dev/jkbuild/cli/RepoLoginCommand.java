// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.repo.RepoCredentialStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk repo login <id>} — store credentials for an artifact repository,
 * keyed by the repository id used in {@code jk.toml} / {@code settings.xml}.
 *
 * <p>The secret is read from stdin (never an argv flag, so it stays out of
 * shell history and process listings). With {@code --username} the secret is
 * treated as a password (HTTP Basic); otherwise it's a bearer token.
 */
@Command(name = "login", description = "Store credentials for an artifact repository")
public final class RepoLoginCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<id>",
            description = "Repository id (matches [repositories.<id>] in jk.toml).")
    String id;

    @Option(names = "--username", paramLabel = "<USER>",
            description = "Use HTTP Basic auth with this username; read the password from stdin. "
                    + "Without it, the stdin value is stored as a bearer token.")
    String username;

    @Option(names = "--credentials-dir", hidden = true,
            description = "Override the credentials directory. Default: ~/.jk/repo-credentials.")
    Path credentialsDir;

    @Mixin GlobalOptions global;

    @Override
    public Integer call() {
        String secret;
        try {
            secret = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            System.err.println("error: could not read secret from stdin: " + e.getMessage());
            return 1;
        }
        if (secret.isBlank()) {
            System.err.println("error: no "
                    + (username != null ? "password" : "token") + " supplied on stdin.");
            return 1;
        }

        RepoCredential cred = (username != null && !username.isBlank())
                ? new RepoCredential.Basic(username.strip(), secret)
                : new RepoCredential.Bearer(secret);
        store().write(id, cred);

        if (!global.quiet) {
            System.out.println("Stored " + (username != null ? "basic" : "token")
                    + " credentials for repository '" + id + "'.");
        }
        return 0;
    }

    private RepoCredentialStore store() {
        return credentialsDir != null
                ? new RepoCredentialStore(credentialsDir)
                : new RepoCredentialStore();
    }
}
