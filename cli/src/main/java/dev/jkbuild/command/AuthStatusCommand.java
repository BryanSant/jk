// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.forge.ResolvedToken;
import dev.jkbuild.forge.TokenSource;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@code jk auth status} — report which forges jk is authenticated with. */
public final class AuthStatusCommand implements CliCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show which forges jk is authenticated with";
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
        return List.of(Param.of("provider", Arity.ZERO_OR_ONE, "Limit to one provider. Default: report all."));
    }

    @Override
    public int run(Invocation in) {
        String provider = in.positionals().isEmpty() ? null : in.positionals().get(0);
        String host = in.value("host").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);

        List<ForgeKind> kinds =
                (provider != null) ? List.of(AuthCommand.requireKind(provider)) : List.of(ForgeKind.values());
        ForgeAuth auth = AuthCommand.authFor(credentialsDir);
        boolean anyAuthenticated = false;

        Theme t = Theme.active();
        for (ForgeKind kind : kinds) {
            if (host == null && kind.defaultHost().isEmpty()) {
                System.out.println(label(kind, t, "(pass --host to check)"));
                continue;
            }
            String resolvedHost = ForgeAuth.resolveHost(kind, host);
            Optional<ResolvedToken> token = auth.resolveSilently(kind, resolvedHost);
            if (token.isPresent()) {
                anyAuthenticated = true;
                System.out.println(label(
                        kind,
                        t,
                        Theme.colorize(resolvedHost, t.settled())
                                + " — "
                                + Theme.colorize(Glyphs.CHECK + " authenticated", t.success())
                                + " ("
                                + describe(token.get().source()) + ")"));
            } else {
                System.out.println(label(
                        kind,
                        t,
                        Theme.colorize(resolvedHost, t.settled())
                                + " — "
                                + Theme.colorize(Glyphs.CROSS + " not authenticated", t.error())));
            }
        }
        return anyAuthenticated ? 0 : 1;
    }

    private static String label(ForgeKind kind, Theme t, String detail) {
        return Theme.colorize(String.format("%-10s", kind.id()), t.cyan()) + " " + detail;
    }

    private static String describe(TokenSource source) {
        return switch (source) {
            case JK_ENV -> "from JK_…_TOKEN env var";
            case NATIVE_ENV -> "from native env var";
            case NATIVE_CLI -> "via native CLI";
            case STORE -> "jk login";
            case DEVICE_FLOW -> "device flow";
            case PAT -> "token";
        };
    }
}
