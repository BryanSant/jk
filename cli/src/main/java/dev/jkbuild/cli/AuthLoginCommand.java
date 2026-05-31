// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.ForgeAuthConfig;
import dev.jkbuild.forge.AuthException;
import dev.jkbuild.forge.DeviceCode;
import dev.jkbuild.forge.DeviceFlow;
import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.forge.ForgeKind;
import dev.jkbuild.http.Http;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import java.util.concurrent.Callable;

/**
 * {@code jk auth login <provider> [--host H]} — obtain and store a token for
 * a forge.
 *
 * <p>Default path is the OAuth device flow (RFC 8628): jk prints a one-time
 * code and a URL, the user approves it in a browser, and jk stores the
 * resulting token under {@code ~/.jk/credentials/<host>}. With
 * {@code --with-token} jk instead reads a personal access token (or app
 * password) from stdin — the universal fallback for CI, headless sessions,
 * and providers without a device flow (Bitbucket).
 */
@Command(name = "login", description = "Log in to a git forge and store a token")
public final class AuthLoginCommand implements Callable<Integer> {

    @Parameters(arity = "0..1", paramLabel = "<provider>",
            description = "github | gitlab | gitea (forgejo/codeberg) | bitbucket. "
                    + "Omit to auto-detect from this repo's git remote.")
    String provider;

    @Option(names = "--host", paramLabel = "<HOST>",
            description = "Forge host. Defaults per provider; required for Gitea/Forgejo.")
    String host;

    @Option(names = "--with-token",
            description = "Read a token (PAT / app password) from stdin instead of the device flow.")
    boolean withToken;

    @Option(names = "--scope", paramLabel = "<SCOPE>",
            description = "OAuth scope to request (device flow only). Defaults per provider.")
    String scope;

    @Option(names = "--credentials-dir", hidden = true,
            description = "Override the credentials directory. Default: ~/.jk/credentials.")
    java.nio.file.Path credentialsDir;

    @Mixin GlobalOptions global;
    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        AuthCommand.Target target = AuthCommand.resolveTarget(spec, provider, host, global.workingDir());
        ForgeKind kind = target.kind();
        ForgeAuth auth = AuthCommand.authFor(credentialsDir);
        String resolvedHost;
        try {
            resolvedHost = ForgeAuth.resolveHost(kind, target.host());
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 2;
        }

        try {
            String token = withToken
                    ? readTokenFromStdin()
                    : runDeviceFlow(kind, resolvedHost);
            auth.store(kind, resolvedHost, token);
        } catch (AuthException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("error: could not read token from stdin: " + e.getMessage());
            return 1;
        }

        if (!global.quiet) {
            System.out.println("Logged in to " + kind.displayName() + " (" + resolvedHost + ").");
        }
        return 0;
    }

    private String readTokenFromStdin() throws IOException {
        String token = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (token.isBlank()) {
            throw new AuthException("No token supplied on stdin.");
        }
        return token;
    }

    private String runDeviceFlow(ForgeKind kind, String resolvedHost) {
        if (!kind.supportsDeviceFlow()) {
            throw new AuthException(kind.displayName()
                    + " has no device flow — log in with: jk auth login " + kind.id()
                    + " --with-token  (paste a token / app password).");
        }
        ForgeAuthConfig forgeConfig = ForgeAuthConfig.discover(
                global.workingDir(), global.noConfig, Optional.ofNullable(global.configFile));
        String clientId = oauthClientId(kind, resolvedHost, System::getenv, forgeConfig).orElse(null);
        if (clientId == null) {
            throw new AuthException("No OAuth client configured for " + kind.displayName()
                    + ". Set " + kind.oauthClientIdEnvVar() + ", add [forge." + kind.id()
                    + "] client-id to your jk.toml, or use --with-token.");
        }
        String requestedScope = (scope != null && !scope.isBlank()) ? scope.strip() : defaultScope(kind);

        DeviceFlow flow = DeviceFlow.forHost(new Http(), kind, resolvedHost, clientId, requestedScope);
        return flow.run(this::prompt);
    }

    /** Show the user code + URL, then best-effort open a browser. */
    private void prompt(DeviceCode dc) {
        String url = (dc.verificationUriComplete() != null && !dc.verificationUriComplete().isBlank())
                ? dc.verificationUriComplete() : dc.verificationUri();
        System.out.println();
        System.out.println("  First copy your one-time code: " + dc.userCode());
        System.out.println("  Then open:                     " + dc.verificationUri());
        System.out.println();
        System.out.println("  Waiting for authorization…");
        openBrowser(url);
    }

    /**
     * Resolve the OAuth client id for {@code kind} on {@code host}, in
     * precedence order: {@code JK_<PROVIDER>_OAUTH_CLIENT_ID} env var, then
     * config files (per-host override &gt; provider default), then jk's
     * built-in default — but the built-in applies <em>only</em> on the
     * provider's default host (github.com's app id is meaningless to a
     * self-hosted instance). Pure and side-effect-free so it can be
     * unit-tested without the network.
     */
    static Optional<String> oauthClientId(ForgeKind kind, String host,
                                          Function<String, String> env, ForgeAuthConfig config) {
        String fromEnv = env.apply(kind.oauthClientIdEnvVar());
        if (fromEnv != null && !fromEnv.isBlank()) return Optional.of(fromEnv.strip());

        Optional<String> fromConfig = config.oauthClientId(kind.id(), host);
        if (fromConfig.isPresent()) return fromConfig;

        boolean isDefaultHost = kind.defaultHost()
                .map(dh -> dh.equalsIgnoreCase(host))
                .orElse(false);
        return isDefaultHost ? kind.defaultOAuthClientId() : Optional.empty();
    }

    private static String defaultScope(ForgeKind kind) {
        return switch (kind) {
            case GITHUB    -> "read:packages";
            case GITLAB    -> "read_api";
            case GITEA     -> "read:package";
            case BITBUCKET -> "";   // unreachable: no device flow
        };
    }

    private static void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd;
        if (os.contains("mac")) {
            cmd = List.of("open", url);
        } else if (os.contains("win")) {
            cmd = List.of("rundll32", "url.dll,FileProtocolHandler", url);
        } else {
            cmd = List.of("xdg-open", url);
        }
        try {
            new ProcessBuilder(cmd)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .start();
        } catch (IOException ignored) {
            // Headless / no browser — the printed URL is enough.
        }
    }
}
