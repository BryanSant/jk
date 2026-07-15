// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.forge.AuthException;
import build.jumpkick.forge.ForgeAuth;
import build.jumpkick.forge.ForgeKind;
import build.jumpkick.model.command.Arity;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.model.command.Param;
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
            CliOutput.err("error: " + e.getMessage());
            return Exit.CONFIG;
        }
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, host);
        } catch (AuthException e) {
            CliOutput.err("error: " + e.getMessage());
            return Exit.CONFIG;
        }

        AuthCommand.authFor(credentialsDir).logout(kind, resolvedHost);
        if (!global.quiet) CliOutput.out("Logged out of " + kind.displayName() + " (" + resolvedHost + ").");
        return 0;
    }
}
