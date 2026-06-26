// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ResolvedToken;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
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
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(target.kind(), target.host());
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        Optional<ResolvedToken> token =
                AuthCommand.authFor(credentialsDir).resolveSilently(target.kind(), resolvedHost);
        if (token.isEmpty()) {
            System.err.println("error: not logged in to " + target.kind().displayName() + " (" + resolvedHost
                    + "). Run: jk auth login " + target.kind().id());
            return 1;
        }
        System.out.println(token.get().value());
        return 0;
    }
}
