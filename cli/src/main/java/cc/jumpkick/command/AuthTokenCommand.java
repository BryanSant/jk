// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.forge.AuthException;
import cc.jumpkick.forge.ForgeAuth;
import cc.jumpkick.forge.ResolvedToken;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@code jk auth token} — print the resolved token for a forge. */
public final class AuthTokenCommand implements CliCommand {

    @Override
    public String name() {
        return "token";
    }

    @Override
    public String description() {
        return "Print the resolved token for a forge";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<HOST>", "Forge host (required for Gitea/Forgejo)", "--host"),
                Opt.value(
                                "<dir>",
                                "Override the credentials directory. Default: ~/.jk/credentials.",
                                "--credentials-dir")
                        .hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "provider",
                Arity.ZERO_OR_ONE,
                "github | gitlab | gitea (forgejo/codeberg) | bitbucket.\nOmit to auto-detect."));
    }

    @Override
    public int run(Invocation in) {
        String provider = in.positionals().isEmpty() ? null : in.positionals().get(0);
        String host = in.value("host").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        AuthCommand.Target target;
        try {
            target = AuthCommand.resolveTarget(provider, host, global.workingDir());
        } catch (IllegalArgumentException e) {
            CliOutput.err("error: " + e.getMessage());
            return Exit.CONFIG;
        }
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(target.kind(), target.host());
        } catch (AuthException e) {
            CliOutput.err("error: " + e.getMessage());
            return Exit.CONFIG;
        }

        Optional<ResolvedToken> token =
                AuthCommand.authFor(credentialsDir).resolveSilently(target.kind(), resolvedHost);
        if (token.isEmpty()) {
            CliOutput.err("error: not logged in to "
                    + target.kind().displayName()
                    + " ("
                    + resolvedHost
                    + "). Run: jk auth login "
                    + target.kind().id());
            return 1;
        }
        CliOutput.out(token.get().value());
        return 0;
    }
}
