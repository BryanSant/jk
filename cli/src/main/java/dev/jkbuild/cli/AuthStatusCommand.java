// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.ResolvedToken;
import dev.jkbuild.forge.TokenSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk auth status [<provider>] [--host H]} — report, per provider,
 * whether jk can find a token and where it comes from. Never prints the
 * token itself. With no provider it summarizes every known forge; with one
 * it reports just that forge.
 *
 * <p>Exit code is 0 when at least one provider is authenticated, 1 when none
 * are — so {@code jk auth status <provider>} doubles as a check in scripts.
 */
@Command(name = "status", description = "Show which forges jk is authenticated with")
public final class AuthStatusCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<provider>",
            description = "Limit to one provider. Default: report all.")
    String provider;

    @Option(names = "--host", paramLabel = "<HOST>",
            description = "Forge host. Defaults per provider; required for Gitea/Forgejo.")
    String host;

    @Option(names = "--credentials-dir", hidden = true,
            description = "Override the credentials directory. Default: ~/.jk/credentials.")
    java.nio.file.Path credentialsDir;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        List<ForgeKind> kinds = (provider != null)
                ? List.of(AuthCommand.requireKind(spec, provider))
                : List.of(ForgeKind.values());

        ForgeAuth auth = AuthCommand.authFor(credentialsDir);
        boolean anyAuthenticated = false;

        for (ForgeKind kind : kinds) {
            // Providers without a default host can't be probed without --host.
            if (host == null && kind.defaultHost().isEmpty()) {
                System.out.println(label(kind, "(pass --host to check)"));
                continue;
            }
            String resolvedHost = ForgeAuth.resolveHost(kind, host);
            Optional<ResolvedToken> token = auth.resolveSilently(kind, resolvedHost);
            if (token.isPresent()) {
                anyAuthenticated = true;
                System.out.println(label(kind, resolvedHost
                        + " — authenticated (" + describe(token.get().source()) + ")"));
            } else {
                System.out.println(label(kind, resolvedHost + " — not authenticated"));
            }
        }

        return anyAuthenticated ? 0 : 1;
    }

    private static String label(ForgeKind kind, String detail) {
        return String.format("%-10s %s", kind.id(), detail);
    }

    private static String describe(TokenSource source) {
        return switch (source) {
            case JK_ENV      -> "from JK_" + "…_TOKEN env var";
            case NATIVE_ENV  -> "from native env var";
            case NATIVE_CLI  -> "via native CLI";
            case STORE       -> "jk login";
            case DEVICE_FLOW -> "device flow";
            case PAT         -> "token";
        };
    }
}
