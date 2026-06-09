// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.config.ForgeAuthConfig;
import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.DeviceCode;
import dev.jkbuild.forge.DeviceFlow;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.http.Http;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@code jk auth login <provider> [--host H]} — obtain and store a token for a forge.
 */
public final class AuthLoginCommand implements CliCommand {

    @Override public String name() { return "login"; }
    @Override public String description() { return "Log in to a git forge and store a token"; }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<HOST>", "Forge host. Defaults per provider; required for Gitea/Forgejo.", "--host"),
                Opt.flag("Read a token (PAT / app password) from stdin instead of the device flow.", "--with-token"),
                Opt.value("<SCOPE>", "OAuth scope to request (device flow only). Defaults per provider.", "--scope"),
                Opt.value("<dir>", "Override the credentials directory. Default: ~/.jk/credentials.", "--credentials-dir").hide());
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("provider", Arity.ZERO_OR_ONE,
                "github | gitlab | gitea (forgejo/codeberg) | bitbucket. Omit to auto-detect from this repo's git remote."));
    }

    private String scope;
    private GlobalOptions global;

    @Override
    public int run(Invocation in) {
        String provider = in.positionals().isEmpty() ? null : in.positionals().get(0);
        String host = in.value("host").orElse(null);
        boolean withToken = in.isSet("with-token");
        this.scope = in.value("scope").orElse(null);
        Path credentialsDir = in.value("credentials-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        AuthCommand.Target target;
        try {
            target = AuthCommand.resolveTarget(provider, host, global.workingDir());
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage()); return 2;
        }
        ForgeKind kind = target.kind();
        ForgeAuth auth = AuthCommand.authFor(credentialsDir);
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, target.host());
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage()); return 2;
        }

        try {
            String token = withToken ? readTokenFromStdin() : runDeviceFlow(kind, resolvedHost);
            auth.store(kind, resolvedHost, token);
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage()); return 1;
        } catch (IOException e) {
            System.err.println("error: could not read token from stdin: " + e.getMessage()); return 1;
        }

        if (!global.quiet) System.out.println("Logged in to " + kind.displayName() + " (" + resolvedHost + ").");
        return 0;
    }

    private String readTokenFromStdin() throws IOException {
        String token = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (token.isBlank()) throw new AuthException("No token supplied on stdin.");
        return token;
    }

    private String runDeviceFlow(ForgeKind kind, String resolvedHost) {
        if (!kind.supportsDeviceFlow()) throw new AuthException(kind.displayName()
                + " has no device flow — log in with: jk auth login " + kind.id()
                + " --with-token  (paste a token / app password).");
        ForgeAuthConfig forgeConfig = ForgeAuthConfig.discover(
                global.workingDir(), global.noConfig, Optional.ofNullable(global.configFile));
        String clientId = oauthClientId(kind, resolvedHost, System::getenv, forgeConfig).orElse(null);
        if (clientId == null) throw new AuthException("No OAuth client configured for " + kind.displayName()
                + ". Set " + kind.oauthClientIdEnvVar() + ", add [forge." + kind.id()
                + "] client-id to your jk.toml, or use --with-token.");
        String requestedScope = (scope != null && !scope.isBlank()) ? scope.strip() : defaultScope(kind);
        DeviceFlow flow = DeviceFlow.forHost(new Http(), kind, resolvedHost, clientId, requestedScope);
        return flow.run(this::prompt);
    }

    private void prompt(DeviceCode dc) {
        String url = (dc.verificationUriComplete() != null && !dc.verificationUriComplete().isBlank())
                ? dc.verificationUriComplete() : dc.verificationUri();
        System.out.println("\n  First copy your one-time code: " + dc.userCode());
        System.out.println("  Then open:                     " + dc.verificationUri());
        System.out.println("\n  Waiting for authorization…");
        openBrowser(url);
    }

    static Optional<String> oauthClientId(ForgeKind kind, String host,
                                           Function<String, String> env, ForgeAuthConfig config) {
        String fromEnv = env.apply(kind.oauthClientIdEnvVar());
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv.strip());
        Optional<String> fromConfig = config.oauthClientId(kind.id(), host);
        if (fromConfig.isPresent()) return fromConfig;
        boolean isDefaultHost = kind.defaultHost().map(dh -> dh.equalsIgnoreCase(host)).orElse(false);
        return isDefaultHost ? kind.defaultOAuthClientId() : Optional.empty();
    }

    private static String defaultScope(ForgeKind kind) {
        return switch (kind) {
            case GITHUB -> "read:packages"; case GITLAB -> "read_api";
            case GITEA -> "read:package";   case BITBUCKET -> "";
        };
    }

    private static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.contains("mac") ? List.of("open", url)
                : os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : List.of("xdg-open", url);
        try {
            new ProcessBuilder(cmd).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD).start();
        } catch (IOException ignored) {}
    }
}
