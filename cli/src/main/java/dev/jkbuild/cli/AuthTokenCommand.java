// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.ResolvedToken;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk auth token [<provider>] [--host H]} — print the token jk would use
 * for a forge to stdout, and nothing else. Mirrors {@code gh auth token} so
 * it can be piped into other tooling ({@code jk auth token github | …}).
 * Resolves through the full silent chain (env → native CLI → stored), so it
 * works whether or not the user logged in via jk. The provider may be omitted
 * to auto-detect from this repo's git remote.
 */
@Command(name = "token", description = "Print the resolved token for a forge")
public final class AuthTokenCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<provider>",
            description = "github | gitlab | gitea (forgejo/codeberg) | bitbucket. "
                    + "Omit to auto-detect from this repo's git remote.")
    String provider;

    @Option(names = "--host", paramLabel = "<HOST>",
            description = "Forge host. Defaults per provider; required for Gitea/Forgejo.")
    String host;

    @Option(names = "--credentials-dir", hidden = true,
            description = "Override the credentials directory. Default: ~/.jk/credentials.")
    java.nio.file.Path credentialsDir;

    @Mixin GlobalOptions global;
    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        AuthCommand.Target target = AuthCommand.resolveTarget(spec, provider, host, global.workingDir());
        ForgeKind kind = target.kind();
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, target.host());
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        Optional<ResolvedToken> token =
                AuthCommand.authFor(credentialsDir).resolveSilently(kind, resolvedHost);
        if (token.isEmpty()) {
            System.err.println("error: not logged in to " + kind.displayName()
                    + " (" + resolvedHost + "). Run: jk auth login " + kind.id());
            return 1;
        }
        System.out.println(token.get().value());
        return 0;
    }
}
