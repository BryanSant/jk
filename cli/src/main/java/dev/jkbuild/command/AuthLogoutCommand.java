// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.nio.file.Path;
import java.util.List;

/** {@code jk auth logout <provider>} — forget jk's stored credential for a host. */
public final class AuthLogoutCommand implements CliCommand {

    @Override
    public String name() {
        return "logout";
    }

    @Override
    public String description() {
        return "Remove jk's stored token for a forge host";
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
        return List.of(Param.of("provider", Arity.ONE, "github | gitlab | gitea (forgejo/codeberg) | bitbucket"));
    }

    @Override
    public int run(Invocation in) {
        String provider = in.positionals().get(0);
        String host = in.value("host").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        GlobalOptions global = GlobalOptions.from(in);

        ForgeKind kind;
        try {
            kind = AuthCommand.requireKind(provider);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, host);
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        AuthCommand.authFor(credentialsDir).logout(kind, resolvedHost);
        if (!global.quiet) System.out.println("Logged out of " + kind.displayName() + " (" + resolvedHost + ").");
        return 0;
    }
}
