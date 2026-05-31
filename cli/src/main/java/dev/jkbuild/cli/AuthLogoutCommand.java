// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * {@code jk auth logout <provider> [--host H]} — forget jk's stored
 * credential for a host. Leaves environment variables and native-CLI logins
 * ({@code gh}, {@code glab}) untouched — those aren't jk's to remove.
 */
@Command(name = "logout", description = "Remove jk's stored token for a forge host")
public final class AuthLogoutCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<provider>",
            description = "github | gitlab | gitea (forgejo/codeberg) | bitbucket")
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
        ForgeKind kind = AuthCommand.requireKind(spec, provider);
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, host);
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        AuthCommand.authFor(credentialsDir).logout(kind, resolvedHost);
        if (!global.quiet) {
            System.out.println("Logged out of " + kind.displayName() + " (" + resolvedHost + ").");
        }
        return 0;
    }
}
