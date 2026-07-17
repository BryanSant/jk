// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.repo.RepoCredentialStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk repo login <id>} — store credentials for an artifact repository, keyed by the
 * repository id used in {@code jk.toml} / {@code settings.xml}.
 *
 * <p>The secret is read from stdin (never an argv flag, so it stays out of shell history and
 * process listings). With {@code --username} the secret is treated as a password (HTTP Basic);
 * otherwise it's a bearer token.
 */
public final class RepoLoginCommand implements CliCommand {

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Store credentials for an artifact repository";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<USER>", "HTTP Basic username (else stdin = bearer token)", "--username"),
                Opt.value(
                                "<dir>",
                                "Override the credentials directory. Default: ~/.jk/repo-credentials.",
                                "--credentials-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("id", Arity.ONE, "Repository id (matches [repositories.<id>] in jk.toml)."));
    }

    @Override
    public int run(Invocation in) {
        String id = in.positionals().get(0);
        String username = in.value("username").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        String secret;
        try {
            secret = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            CliOutput.err("error: could not read secret from stdin: " + e.getMessage());
            return 1;
        }
        if (secret.isBlank()) {
            CliOutput.err("error: no " + (username != null ? "password" : "token") + " supplied on stdin.");
            return 1;
        }

        RepoCredential cred = (username != null && !username.isBlank())
                ? new RepoCredential.Basic(username.strip(), secret)
                : new RepoCredential.Bearer(secret);
        store(credentialsDir).write(id, cred);

        if (!global.quiet) {
            CliOutput.out(
                    "Stored " + (username != null ? "basic" : "token") + " credentials for repository '" + id + "'.");
        }
        return 0;
    }

    private static RepoCredentialStore store(Path credentialsDir) {
        return credentialsDir != null ? new RepoCredentialStore(credentialsDir) : new RepoCredentialStore();
    }
}
