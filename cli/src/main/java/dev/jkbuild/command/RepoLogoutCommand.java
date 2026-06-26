// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.RepoCredentialStore;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jk repo logout <id>} — forget stored credentials for an artifact repository. Leaves env
 * vars and {@code ~/.m2/settings.xml} untouched.
 */
public final class RepoLogoutCommand implements CliCommand {

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "Remove stored credentials for an artifact repository";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.value(
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
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        RepoCredentialStore store =
                credentialsDir != null ? new RepoCredentialStore(credentialsDir) : new RepoCredentialStore();
        store.clear(id);
        if (!global.quiet) {
            System.out.println("Removed stored credentials for repository '" + id + "'.");
        }
        return 0;
    }
}
